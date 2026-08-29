package io.github.gustavlindberg99.photos.storage_client

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.github.gustavlindberg99.androidsuspendutils.async
import com.github.gustavlindberg99.androidsuspendutils.channelFlow
import com.github.gustavlindberg99.androidsuspendutils.concurrentForEach
import com.github.gustavlindberg99.androidsuspendutils.withContext
import com.onedrive.sdk.authentication.MSAAuthenticator
import com.onedrive.sdk.concurrency.ChunkedUploadProvider
import com.onedrive.sdk.concurrency.ICallback
import com.onedrive.sdk.concurrency.IProgressCallback
import com.onedrive.sdk.core.ClientException
import com.onedrive.sdk.core.DefaultClientConfig
import com.onedrive.sdk.core.IClientConfig
import com.onedrive.sdk.extensions.ChunkedUploadSessionDescriptor
import com.onedrive.sdk.extensions.Folder
import com.onedrive.sdk.extensions.IItemCollectionPage
import com.onedrive.sdk.extensions.IItemCollectionRequestBuilder
import com.onedrive.sdk.extensions.IOneDriveClient
import com.onedrive.sdk.extensions.Item
import com.onedrive.sdk.extensions.OneDriveClient
import com.onedrive.sdk.extensions.UploadSession
import com.onedrive.sdk.generated.IBaseItemRequestBuilder
import com.onedrive.sdk.http.OneDriveServiceException
import com.onedrive.sdk.options.HeaderOption
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.OneDriveFileHandle
import io.github.gustavlindberg99.photos.photo.Media
import io.github.gustavlindberg99.photos.storage_client_utils.PhotoManager
import io.github.gustavlindberg99.photos.storage_client_utils.PhotosFolderManager
import io.github.gustavlindberg99.photos.storage_client_utils.getCachedPhotoBySha1
import io.github.gustavlindberg99.photos.data_source.HttpDataSource
import io.github.gustavlindberg99.photos.file_handle.HandleList
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OneDriveStorageClient private constructor(
    private val _context: StorageManagerActivity,
    private var _client: IOneDriveClient?
) : StorageClient {
    private var _clientPromise: Deferred<IOneDriveClient>? = null

    public override val name = this._context.getString(R.string.oneDrive)

    private val _photosFolderManager = object : PhotosFolderManager<Item>(
        { photosFolder(this._context) }
    ) {
        protected override suspend fun getSubFolders(parent: Item?): List<Item> {
            val parentRequest =
                if (parent == null) this@OneDriveStorageClient.client().drive.root
                else this@OneDriveStorageClient.client().drive.getItems(parent.id)
            return awaitApiCall {
                parentRequest.children.buildRequest().get(it)
            }?.allPages()?.filterNotNull() ?: emptyList()
        }

        protected override suspend fun createFolder(parent: Item?, name: String): Item {
            val newFolder = Item()
            newFolder.name = name
            newFolder.folder = Folder()
            val parentRequest =
                if (parent == null) this@OneDriveStorageClient.client().drive.root
                else this@OneDriveStorageClient.client().drive.getItems(parent.id)
            val createdFolder: Item = awaitApiCall {
                parentRequest.children.buildRequest()
                    .post(newFolder, it)
            } ?: throw IOException("Failed to create Pictures folder")
            return createdFolder
        }

        protected override fun fileName(file: Item): String = file.name
    }

    public override fun equals(other: Any?): Boolean {
        return other is OneDriveStorageClient
    }

    public override fun hashCode(): Int {
        return OneDriveStorageClient::class::qualifiedName.hashCode()
    }

    public override fun getAllPhotos(): Flow<Media> = channelFlow { f ->
        val request =
            if (photosFolder(this._context) == "") this.client().drive.root
            else this.client().drive.getItems(
                this._photosFolderManager.getPhotosFolder()?.id ?: return@channelFlow
            )
        val existingSha1s = PhotoManager.allPhotos(this._context)
            .filter { it.handles.oneDriveHandle != null }
            .map { it.sha1 }.toSet()

        // Sort to put the SHA1s that don't exist yet first so that we don't need to wait too long to see new photos
        val allFiles = this.photosInFolder(request)
            .sortedBy { it.file.hashes?.sha1Hash in existingSha1s }

        allFiles.concurrentForEach(this._context, 10) { file ->
            // If the sha1 hash is not present, skip. This can happen in the following cases:
            //  1. The file was recently uploaded and the server isn't done calculating its SHA1. Then we skip it for now and we will find it later instead.
            //  2. The file type doesn't support SHA1. Then it's not a photo so we would skip it anyway.
            //  3. A network error occurred and the API response is incomplete. Then again we will find it later.
            val sha1 = file.file.hashes?.sha1Hash ?: return@concurrentForEach
            val photo = getCachedPhotoBySha1(
                this._context,
                file.name,
                file.file.mimeType,
                sha1,
                HandleList(oneDriveHandle = OneDriveFileHandle(file.id))
            ) ?: return@concurrentForEach
            f.send(photo)
        }
    }

    public override suspend fun allPhotoHandles(): Set<OneDriveFileHandle> {
        val request =
            if (photosFolder(this._context) == "") this.client().drive.root
            else this.client().drive.getItems(
                this._photosFolderManager.getPhotosFolder()?.id ?: return emptySet()
            )
        return this.photosInFolder(request).map { OneDriveFileHandle(it.id) }.toSet()
    }

    public override suspend fun save(photo: Media, progressListener: (Int) -> Unit) {
        // Create the Pictures folder if it doesn't already exist
        val request =
            if (photosFolder(this._context) == "") this.client().drive.root
            else this.client().drive.getItems(
                this._photosFolderManager.getPhotosFolder()?.id
                    ?: this._photosFolderManager.createPhotosFolder().id
            )

        // Check if the file is already uploaded
        val existingFiles = photosInFolder(request)
        val existingFile = existingFiles.find { it.file.hashes?.sha1Hash == photo.sha1 }

        // Upload the file
        val id: String
        if (existingFile == null) {
            val inputStream = photo.getInputStream(this._context)
            val size = photo.handles.preferredHandle().getSize(this._context).toInt()

            val uploadSession: UploadSession<Any> = awaitApiCall {
                request.children
                    .byId(photo.fileName)
                    .getCreateSession(ChunkedUploadSessionDescriptor())
                    .buildRequest()
                    .post(it)
            } ?: throw IOException("Failed to create upload session")

            val provider = ChunkedUploadProvider<Item>(
                uploadSession,
                this.client(),
                inputStream,
                size,
                Item::class.java
            )

            val createdFile: Item = withContext(Dispatchers.IO) {
                awaitApiCall {
                    provider.upload(
                        null,
                        object : IProgressCallback<Item> {
                            public override fun progress(current: Long, total: Long) {
                                progressListener((current * 100 / total).toInt())
                            }

                            public override fun success(result: Item) {
                                it.success(result)
                            }

                            public override fun failure(ex: ClientException) {
                                it.failure(ex)
                            }
                        }
                    )
                }
            } ?: throw IOException("Failed to create file")
            id = createdFile.id
        }
        else {
            id = existingFile.id
        }
        photo.handles.oneDriveHandle = OneDriveFileHandle(id)
        PhotoManager.update(this._context, photo)
    }

    public override suspend fun overwrite(oldPhoto: Media, newBytes: ByteArray): Media {
        val handle = oldPhoto.handles.oneDriveHandle
            ?: throw IOException("Photo is not on OneDrive")
        val client = this.client()
        val newFile: Item = awaitApiCall {
            client.drive.getItems(handle.id).content.buildRequest()
                .put(newBytes, it)
        } ?: throw IOException("Failed to overwrite file")
        val sha1 = ByteString.of(*newBytes).sha1().hex()
        val newPhoto = getCachedPhotoBySha1(
            this._context,
            newFile.name,
            oldPhoto.mimeType,
            sha1,
            HandleList(oneDriveHandle = OneDriveFileHandle(newFile.id))
        ) ?: throw IOException("Cannot read from newly created photo")
        oldPhoto.handles.oneDriveHandle = null
        PhotoManager.update(this._context, oldPhoto)
        return PhotoManager.update(this._context, newPhoto)
    }

    public override suspend fun delete(photo: Media) {
        val handle = photo.handles.oneDriveHandle ?: return
        val client = this.client()
        try {
            awaitApiCall<Void> {
                client.drive.getItems(handle.id).buildRequest().delete(it)
            }
        }
        catch (e: OneDriveServiceException) {
            // Ignore itemNotFound exceptions because that can happen if the photo is already deleted
            val message = e.message
            if (message == null || !message.contains("itemNotFound")) {
                throw e
            }
        }
        photo.handles.oneDriveHandle = null
        PhotoManager.update(this._context, photo, delete = true)
    }

    @OptIn(UnstableApi::class)
    public override suspend fun dataFactory(context: Context): DataSource.Factory {
        val httpClient = this.httpClient()
        return DataSource.Factory { HttpDataSource(httpClient) }
    }

    /**
     * Gets the input stream of the file with the given ID.
     *
     * @param id    The ID of the file.
     * @param range The range of the file to get. If null, the entire file is returned.
     *
     * @return The input stream of the file.
     *
     * @throws IOException If the file could not be retrieved.
     */
    public suspend fun getInputStream(
        id: String,
        range: LongRange? = null
    ): InputStream = withContext(Dispatchers.IO) {
        val options = if (range != null) {
            listOf(HeaderOption("Range", "bytes=${range.first}-${range.last}"))
        }
        else {
            emptyList()
        }
        return@withContext this.client().drive.getItems(id).content.buildRequest(options).get()
    }

    /**
     * Gets the direct playback URI for the given file ID.
     */
    public suspend fun getPlaybackUri(id: String): Uri = withContext(Dispatchers.IO) {
        val client = this.client()
        // Use the longer UPLOAD_TIMEOUT here instead of the default/short one
        val item: Item = awaitApiCall(UPLOAD_TIMEOUT) {
            client.drive.getItems(id).buildRequest().get(it)
        } ?: throw IOException("Failed to get item metadata")

        // The direct download URL is usually in the raw JSON
        val downloadUrl = item.rawObject?.get("@microsoft.graph.downloadUrl")?.asString
            ?: item.rawObject?.get("@content.downloadUrl")?.asString

        val uri = downloadUrl?.toUri()
            ?: "https://api.onedrive.com/v1.0/drive/items/$id/content".toUri()

        // LOG THIS to see if we are getting the direct link or the fallback
        println("OneDrive Playback URI: $uri")

        return@withContext uri
    }

    /**
     * Gets the size of the file with the given ID.
     *
     * @param id    The ID of the file.
     *
     * @return The size of the file.
     */
    public suspend fun getSize(id: String): Long = withContext(Dispatchers.IO) {
        val client = this.client()
        val item: Item = awaitApiCall {
            client.drive.getItems(id).buildRequest().get(it)
        } ?: throw IOException("Failed to get item metadata")
        item.size ?: 0L
    }

    /**
     * Gets all photos in the given folder recursively.
     *
     * @param parentRequest The request containing the folder to get the files from.
     *
     * @return [Item]s corresponding to all photos in the given folder. The `file` attribute of the items is guaranteed to be non-null.
     *
     * @throws ClientException If the folder could not be retrieved.
     */
    private suspend fun photosInFolder(
        parentRequest: IBaseItemRequestBuilder
    ): Set<Item> = withContext(Dispatchers.IO) {
        val folder: IItemCollectionPage = awaitApiCall {
            parentRequest.children.buildRequest().get(it)
        } ?: return@withContext emptySet()

        val result = mutableSetOf<Item>()
        for (item in folder.allPages()) {
            if (item?.folder != null) {
                result.addAll(
                    photosInFolder(
                        this.client().drive.getItems(item.id)
                    )
                )
            }
            else if (item?.file != null) {
                result.add(item)
            }
        }
        return@withContext result
    }

    /**
     * Gets the OneDrive client, or attempts to create it if it hasn't been created yet.
     *
     * @return The OneDrive client.
     *
     * @throws Exception If the client could not be created.
     */
    private suspend fun client(): IOneDriveClient {
        val client = this._client
        if (client != null) {
            return client
        }
        val promise = this._clientPromise ?: this._context.lifecycleScope.async {
            try {
                val createdClient = createClient(this._context, UPLOAD_TIMEOUT)
                this._client = createdClient
                return@async createdClient
            }
            catch (e: Exception) {
                this._clientPromise = null
                throw e
            }
        }
        this._clientPromise = promise
        return promise.await()
    }

    /**
     * Gets an HTTP client to make authenticated requests to OneDrive.
     *
     * @return The HTTP client.
     */
    private suspend fun httpClient(): HttpClient {
        val token = this.client().authenticator.accountInfo.accessToken
        return HttpClient(Android) {
            expectSuccess = true
            defaultRequest {
                header("Authorization", "Bearer $token")
            }
        }
    }

    companion object : StorageClient.Companion {
        /**
         * Keys for shared preferences.
         */
        public override val PREFERENCES_KEY = "oneDrive"
        public override val DEFAULT_FOLDER = "Pictures"
        private const val SIGNED_IN = "signedIn"

        private const val UPLOAD_TIMEOUT = 60000L
        private const val LOGIN_TIMEOUT = 1000L

        /**
         * Utility class to be able to use Kotlin coroutines with the OneDrive library.
         *
         * @param _continuation  The parameter passed to [suspendCancellableCoroutine].
         *
         * @return The result of the request.
         *
         * @throws ClientException If the request failed.
         */
        private class SuspendableCallback<T>(private val _continuation: CancellableContinuation<T?>) :
            ICallback<T> {
            public override fun success(result: T?) {
                this._continuation.resume(result)
            }

            public override fun failure(e: ClientException) {
                this._continuation.resumeWithException(e)
            }
        }

        /**
         * Gets all pages of a collection.
         *
         * @return All pages of the collection.
         */
        private suspend fun IItemCollectionPage.allPages(): List<Item?> {
            val result = mutableListOf<Item?>()
            var page: IItemCollectionPage? = this
            while (page != null) {
                result.addAll(page.currentPage)
                val nextPage: IItemCollectionRequestBuilder? = page.nextPage
                page =
                    if (nextPage == null) null
                    else awaitApiCall {
                        nextPage.buildRequest().get(it)
                    }
            }
            return result
        }

        /**
         * Creates a client config for OneDrive. This only supports personal accounts. To support business accounts as well, the documentation says to use `createWithAuthenticators` with an `ADALAuthenticator` with `getRedirectUrl` set to `"https://login.live.com/oauth20_desktop.srf"` but that causes a crash so this app doesn't support business accounts.
         *
         * @return The client config.
         */
        private fun createConfig(): IClientConfig {
            return DefaultClientConfig.createWithAuthenticator(
                // For personal accounts
                object : MSAAuthenticator() {
                    public override fun getClientId() = "a27cd3e4-2761-4038-9382-f09327256c9d"
                    public override fun getScopes() =
                        arrayOf("onedrive.readwrite", "wl.offline_access")
                }
            )
        }

        /**
         * Gets the OneDrive client.
         *
         * @param context   The context to use.
         * @param timeout   The timeout to use.
         *
         * @return The OneDrive client.
         *
         * @throws ClientException If authentication failed.
         */
        public suspend fun createClient(context: Activity, timeout: Long): IOneDriveClient {
            val oneDriveConfig = createConfig()

            val client = awaitApiCall(timeout) {
                OneDriveClient.Builder()
                    .fromConfig(oneDriveConfig)
                    .loginAndBuildClient(context, it)
            }
            if (client == null) {
                throw IOException("Failed to create OneDrive client")
            }
            context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                putBoolean(SIGNED_IN, true)
            }
            return client
        }

        /**
         * Authenticates with OneDrive.
         *
         * @param context       The context to use.
         * @param allowSignIn   True if the user should be prompted to sign in if they aren't already signed in.
         *
         * @return The authenticated client, or null if the user isn't signed in and [allowSignIn] is false.
         *
         * @throws ClientException If authentication failed.
         */
        public suspend fun authenticate(
            context: StorageManagerActivity,
            allowSignIn: Boolean
        ): OneDriveStorageClient? {
            if (!allowSignIn && !signedIn(context)) {
                return null
            }
            val client = try {
                createClient(context, LOGIN_TIMEOUT)
            }
            catch (e: Exception) {
                Log.w(this.javaClass.name, e.message, e)
                null
            }
            return OneDriveStorageClient(context, client)
        }

        /**
         * Authenticates with OneDrive. Overload that allows using any context but does not return the client, as the client needs a StorageManagerActivity context.
         *
         * @param context       The context to use.
         * @param allowSignIn   True if the user should be prompted to sign in if they aren't already signed in.
         *
         * @return The authenticated client, or null if the user isn't signed in and [allowSignIn] is false.
         *
         * @throws ClientException If authentication failed.
         */
        public suspend fun authenticate(context: Activity, allowSignIn: Boolean) {
            if (allowSignIn || signedIn(context)) {
                createClient(context, Long.MAX_VALUE)
            }
        }

        public override fun name(context: Context): String {
            return context.getString(R.string.oneDrive)
        }

        public override suspend fun signOut(context: Activity) {
            // Tell the app that the user is no longer signed in so that it doesn't attempt to sign in again
            context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                remove(SIGNED_IN)
            }

            // Remove credentials so that the user can sign in later with a different account if they want
            val oneDriveConfig = createConfig()
            oneDriveConfig.authenticator.init(
                oneDriveConfig.executors,
                oneDriveConfig.httpProvider,
                context,
                oneDriveConfig.logger
            )
            awaitApiCall(LOGIN_TIMEOUT) {
                oneDriveConfig.authenticator.logout(it)
            }
        }

        /**
         * Awaits a OneDrive API call.
         *
         * @param timeout   The timeout to use. Needed because the OneDrive API doesn't respond at all if the phone is offline.
         * @param callback  A callback containing the API call.
         */
        private suspend fun <T> awaitApiCall(
            timeout: Long = UPLOAD_TIMEOUT,
            callback: (SuspendableCallback<T>) -> Unit
        ): T? {
            @Suppress("ConvertLongToDuration")  // The Duration overload is buggy
            return withTimeout(timeout) {
                suspendCancellableCoroutine {
                    callback(SuspendableCallback(it))
                }
            }
        }

        /**
         * Checks if the user is signed in to OneDrive.
         *
         * @param context The context to use.
         */
        private fun signedIn(context: Context): Boolean {
            val sharedPreferences =
                context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
            return sharedPreferences.getBoolean(SIGNED_IN, false)
        }
    }
}