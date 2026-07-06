package io.github.gustavlindberg99.photos.storage_client

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.github.gustavlindberg99.androidsuspendutils.flow
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import com.github.gustavlindberg99.androidsuspendutils.withContext
import com.onedrive.sdk.authentication.MSAAuthenticator
import com.onedrive.sdk.concurrency.ICallback
import com.onedrive.sdk.core.ClientException
import com.onedrive.sdk.core.DefaultClientConfig
import com.onedrive.sdk.core.IClientConfig
import com.onedrive.sdk.extensions.Folder
import com.onedrive.sdk.extensions.IItemCollectionPage
import com.onedrive.sdk.extensions.IOneDriveClient
import com.onedrive.sdk.extensions.Item
import com.onedrive.sdk.extensions.OneDriveClient
import com.onedrive.sdk.generated.IBaseItemRequestBuilder
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.OneDriveFileHandle
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.PhotoManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OneDriveStorageClient private constructor(
    private val _context: StorageManagerActivity,
    private val _client: IOneDriveClient
) : StorageClient {
    public override val name = this._context.getString(R.string.oneDrive)

    private val _photosFolderManager = object : PhotosFolderManager<Item>(
        { photosFolder(this._context) }
    ) {
        protected override suspend fun getSubFolders(parent: Item?): List<Item> {
            val parentRequest =
                if (parent == null) this@OneDriveStorageClient._client.drive.root
                else this@OneDriveStorageClient._client.drive.getItems(parent.id)
            return suspendCancellableCoroutine {
                parentRequest.children.buildRequest().get(SuspendableCallback(it))
            }?.currentPage ?: emptyList()
        }

        protected override suspend fun createFolder(parent: Item?, name: String): Item {
            val newFolder = Item()
            newFolder.name = name
            newFolder.folder = Folder()
            val parentRequest =
                if (parent == null) this@OneDriveStorageClient._client.drive.root
                else this@OneDriveStorageClient._client.drive.getItems(parent.id)
            val createdFolder: Item = suspendCancellableCoroutine {
                parentRequest.children.buildRequest()
                    .post(newFolder, SuspendableCallback(it))
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

    public override fun getAllPhotos(): Flow<Photo> = flow { f ->
        val request =
            if (photosFolder(this._context) == "") this._client.drive.root
            else this._client.drive.getItems(
                this._photosFolderManager.getPhotosFolder()?.id ?: return@flow
            )
        val allFiles = this.photosInFolder(request)
        for (file in allFiles) {
            // If the sha1 hash is not present, skip. This can happen in the following cases:
            //  1. The file was recently uploaded and the server isn't done calculating its SHA1. Then we skip it for now and we will find it later instead.
            //  2. The file type doesn't support SHA1. Then it's not a photo so we would skip it anyway.
            //  3. A network error occurred and the API response is incomplete. Then again we will find it later.
            val sha1 = file.file.hashes?.sha1Hash ?: continue
            val photo = getCachedPhotoBySha1(
                this._context,
                file.name,
                file.file.mimeType,
                sha1,
                mutableMapOf(OneDriveStorageClient::class to OneDriveFileHandle(file.id))
            ) ?: continue
            f.emit(photo)
        }
    }

    public override suspend fun allPhotoHandles(): Set<OneDriveFileHandle> {
        val request =
            if (photosFolder(this._context) == "") this._client.drive.root
            else this._client.drive.getItems(
                this._photosFolderManager.getPhotosFolder()?.id ?: return emptySet()
            )
        return this.photosInFolder(request).map { OneDriveFileHandle(it.id) }.toSet()
    }

    public override suspend fun save(photo: Photo) {
        // Create the Pictures folder if it doesn't already exist
        val request =
            if (photosFolder(this._context) == "") this._client.drive.root
            else this._client.drive.getItems(
                this._photosFolderManager.getPhotosFolder()?.id
                    ?: this._photosFolderManager.createPhotosFolder().id
            )

        // Check if the file is already uploaded
        val existingFiles = photosInFolder(request)
        val existingFile = existingFiles.find { it.file.hashes?.sha1Hash == photo.sha1 }

        // Upload the file
        val id: String
        if (existingFile == null) {
            val bytes =
                photo.getInputStream(this._context)
                    .useWithContext(Dispatchers.IO) { it.readBytes() }
            val createdFile: Item = suspendCancellableCoroutine {
                request.children
                    .byId(photo.fileName)
                    .content
                    .buildRequest()
                    .put(bytes, SuspendableCallback(it))
            } ?: throw IOException("Failed to create file")
            id = createdFile.id
        }
        else {
            id = existingFile.id
        }
        photo.handles[this::class] = OneDriveFileHandle(id)
        PhotoManager.update(this._context, photo)
    }

    public override suspend fun overwrite(oldPhoto: Photo, newBytes: ByteArray): Photo {
        val handle = oldPhoto.handles[this::class] as OneDriveFileHandle
        val newFile: Item = suspendCancellableCoroutine {
            this._client.drive.getItems(handle.id).content.buildRequest()
                .put(newBytes, SuspendableCallback(it))
        } ?: throw IOException("Failed to overwrite file")
        val sha1 = ByteString.of(*newBytes).sha1().hex()
        val newPhoto = getCachedPhotoBySha1(
            this._context,
            newFile.name,
            oldPhoto.mimeType,
            sha1,
            mutableMapOf(this::class to OneDriveFileHandle(newFile.id))
        ) ?: throw IOException("Cannot read from newly created photo")
        oldPhoto.handles.remove(this::class)
        PhotoManager.update(this._context, oldPhoto)
        return PhotoManager.update(this._context, newPhoto)
    }

    public override suspend fun delete(photo: Photo) {
        val handle = photo.handles[this::class] as OneDriveFileHandle
        suspendCancellableCoroutine {
            this._client.drive.getItems(handle.id).buildRequest().delete(SuspendableCallback(it))
        }
        photo.handles.remove(this::class)
        PhotoManager.update(this._context, photo)
    }

    /**
     * Gets the input stream of the file with the given ID.
     *
     * @param id    The ID of the file.
     *
     * @return The input stream of the file.
     *
     * @throws IOException If the file could not be retrieved.
     */
    public suspend fun getInputStream(id: String): InputStream = withContext(Dispatchers.IO) {
        return@withContext this._client.drive.getItems(id).content.buildRequest().get()
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
        val folder: IItemCollectionPage = suspendCancellableCoroutine {
            parentRequest.children.buildRequest().get(SuspendableCallback(it))
        } ?: return@withContext emptySet()

        val result = mutableSetOf<Item>()
        for (item in folder.currentPage) {
            if (item?.folder != null) {
                result.addAll(
                    photosInFolder(
                        this._client.drive.getItems(item.id)
                    )
                )
            }
            else if (item?.file != null) {
                result.add(item)
            }
        }
        return@withContext result
    }

    companion object : StorageClient.Companion {
        /**
         * Keys for shared preferences.
         */
        public override val PREFERENCES_KEY = "oneDrive"
        public override val DEFAULT_FOLDER = "Pictures"
        private const val SIGNED_IN = "signedIn"

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
         * @param context       The context to use.
         * @param allowSignIn   True if the user should be prompted to sign in if they aren't already signed in.
         *
         * @return The OneDrive client, or null if the user isn't signed in and [allowSignIn] is false.
         *
         * @throws ClientException If authentication failed.
         */
        public suspend fun getClient(
            context: Activity,
            allowSignIn: Boolean
        ): IOneDriveClient? {
            val sharedPreferences =
                context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
            val signedIn = sharedPreferences.getBoolean(SIGNED_IN, false)
            if (!signedIn && !allowSignIn) {
                return null
            }

            val oneDriveConfig = createConfig()

            val client = suspendCancellableCoroutine {
                OneDriveClient.Builder()
                    .fromConfig(oneDriveConfig)
                    .loginAndBuildClient(context, SuspendableCallback(it))
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
            val client = getClient(context, allowSignIn) ?: return null
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
            getClient(context, allowSignIn)
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
            suspendCancellableCoroutine {
                oneDriveConfig.authenticator.logout(SuspendableCallback(it))
            }
        }
    }
}