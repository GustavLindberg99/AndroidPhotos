package io.github.gustavlindberg99.photos.storage_client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.github.gustavlindberg99.androidsuspendutils.SuspendableLauncher
import com.github.gustavlindberg99.androidsuspendutils.channelFlow
import com.github.gustavlindberg99.androidsuspendutils.concurrentForEach
import com.github.gustavlindberg99.androidsuspendutils.withContext
import com.pcloud.sdk.ApiClient
import com.pcloud.sdk.ApiError
import com.pcloud.sdk.Authenticators
import com.pcloud.sdk.AuthorizationActivity
import com.pcloud.sdk.AuthorizationRequest
import com.pcloud.sdk.AuthorizationResult
import com.pcloud.sdk.DataSource
import com.pcloud.sdk.DownloadOptions
import com.pcloud.sdk.PCloudSdk
import com.pcloud.sdk.RemoteFile
import com.pcloud.sdk.RemoteFolder
import com.pcloud.sdk.UploadOptions
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.PCloudFileHandle
import io.github.gustavlindberg99.photos.photo.Media
import io.github.gustavlindberg99.photos.storage_client_utils.PhotoManager
import io.github.gustavlindberg99.photos.storage_client_utils.getCachedPhotoBySha1
import io.github.gustavlindberg99.photos.data_source.HttpDataSource
import io.github.gustavlindberg99.photos.file_handle.HandleList
import io.github.gustavlindberg99.photos.storage_client_utils.getCachedSha1
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import okio.BufferedSink
import okio.ByteString
import okio.source
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection

class PCloudClient private constructor(
    private val _context: StorageManagerActivity,
    token: String
) : StorageClient {
    private val _apiClient: ApiClient = PCloudSdk.newClientBuilder()
        .authenticator(Authenticators.newOAuthAuthenticator(token))
        .create()

    private val _httpClient = HttpClient(Android) {
        expectSuccess = true
        defaultRequest {
            header("Authorization", "Bearer $token")
        }
    }

    public override val name = this._context.getString(R.string.pCloud)

    public override fun equals(other: Any?): Boolean {
        return other is PCloudClient
    }

    public override fun hashCode(): Int {
        return PCloudClient::class::qualifiedName.hashCode()
    }

    public override fun getAllPhotos(): Flow<Media> = channelFlow { f ->
        val picturesFolder = this.getPicturesFolder() ?: return@channelFlow
        val existingFileNames = PhotoManager.allPhotos(this._context)
            .filter { it.handles.pCloudHandle != null }
            .map { it.sha1 }.toSet()

        // Sort to put the file names that don't exist yet first so that we don't need to wait too long to see new photos
        val photoFiles = this.photosInFolder(picturesFolder)
            .sortedBy { it.name() in existingFileNames }

        photoFiles.concurrentForEach(this._context, 10) { file ->
            val mimeType =
                URLConnection.guessContentTypeFromName(file.name()) ?: return@concurrentForEach
            val sha1 = file.sha1()
            val photo = getCachedPhotoBySha1(
                this._context,
                file.name(),
                mimeType,
                sha1,
                HandleList(pCloudHandle = PCloudFileHandle(file.fileId()))
            ) ?: return@concurrentForEach
            f.send(photo)
        }
    }

    public override suspend fun allPhotoHandles(): Set<PCloudFileHandle> {
        val picturesFolder = this.getPicturesFolder() ?: return emptySet()
        return withContext(Dispatchers.IO) {
            photosInFolder(picturesFolder)
                .map { PCloudFileHandle(it.fileId()) }
                .toSet()
        }
    }

    public override suspend fun save(photo: Media, progressListener: (Int) -> Unit) {
        // Create the "My Pictures" folder if it doesn't already exist
        val picturesFolder = getPicturesFolder() ?: withContext(Dispatchers.IO) {
            _apiClient.createFolder("/" + photosFolder(this._context)).execute()
        }

        // Check if the file is already uploaded
        val existingFiles = photosInFolder(picturesFolder)
        val existingFile =
            existingFiles.find { it.name() == photo.fileName && it.sha1() == photo.sha1 }

        // Upload the file
        val id: Long
        if (existingFile == null) {
            val inputStream = photo.getInputStream(this._context)
            val size = photo.handles.preferredHandle().getSize(this._context)

            val dataSource = object : DataSource() {
                public override fun contentLength() = size
                public override fun writeTo(sink: BufferedSink) {
                    inputStream.source().use { sink.writeAll(it) }
                }
            }

            val file = withContext(Dispatchers.IO) {
                this._apiClient.createFile(
                    picturesFolder,
                    photo.fileName,
                    dataSource,
                    photo.dateTime,
                    { done, total -> progressListener((done * 100 / total).toInt()) }
                ).execute()
            }

            id = file.fileId()
        }
        else {
            id = existingFile.fileId()
        }
        photo.handles.pCloudHandle = PCloudFileHandle(id)
        PhotoManager.update(this._context, photo)
    }

    public override suspend fun overwrite(oldPhoto: Media, newBytes: ByteArray): Media {
        val handle = oldPhoto.handles.pCloudHandle ?: throw IOException("Photo is not on PCloud")
        val remoteFile = withContext(Dispatchers.IO) {
            _apiClient.loadFile(handle.id).execute()
        }
        val parentFolderId = remoteFile.parentFolderId()
        val newFile = withContext(Dispatchers.IO) {
            _apiClient.createFile(
                parentFolderId,
                remoteFile.name(),
                DataSource.create(newBytes),
                UploadOptions.OVERRIDE_FILE
            ).execute()
        }
        val sha1 = ByteString.of(*newBytes).sha1().hex()
        val newPhoto = getCachedPhotoBySha1(
            this._context,
            newFile.name(),
            oldPhoto.mimeType,
            sha1,
            HandleList(pCloudHandle = PCloudFileHandle(newFile.fileId()))
        ) ?: throw IOException("Cannot read from newly created photo")
        oldPhoto.handles.pCloudHandle = null
        PhotoManager.update(this._context, oldPhoto)
        return PhotoManager.update(this._context, newPhoto)
    }

    public override suspend fun delete(photo: Media) {
        val id = photo.handles.pCloudHandle ?: return
        withContext(Dispatchers.IO) {
            _apiClient.deleteFile(id.id).execute()
        }
        photo.handles.pCloudHandle
        PhotoManager.update(this._context, photo, delete = true)
    }

    @OptIn(UnstableApi::class)
    public override suspend fun dataFactory(context: Context): androidx.media3.datasource.DataSource.Factory {
        return androidx.media3.datasource.DataSource.Factory { HttpDataSource(this._httpClient) }
    }

    /**
     * Gets the input stream of the file with the given ID.
     *
     * @param id    The ID of the file.
     *
     * @return The input stream of the file.
     *
     * @throws java.io.IOException If a network error occurred.
     * @throws ApiError If the file does not exist.
     */
    public suspend fun getInputStream(
        id: Long,
        range: LongRange? = null
    ): InputStream = withContext(Dispatchers.IO) {
        if (range == null) {
            return@withContext _apiClient.loadFile(id).execute().byteStream()
        }
        val fileLink = _apiClient.createFileLink(id, DownloadOptions.DEFAULT).execute()
        return@withContext HttpClient(Android) { expectSuccess = true }
            .get(fileLink.bestUrl().toString()) {
                header("Range", "bytes=${range.first}-${range.last}")
            }
            .bodyAsChannel()
            .toInputStream()
    }

    /**
     * Gets the playback URI of the file with the given ID.
     *
     * @param id    The ID of the file.
     *
     * @return The playback URI of the file.
     */
    public suspend fun getPlaybackUri(id: Long): Uri = withContext(Dispatchers.IO) {
        return@withContext this._apiClient
            .createFileLink(id, DownloadOptions.DEFAULT)
            .execute()
            .bestUrl()
            .toString()
            .toUri()
    }

    /**
     * Gets the size of the file with the given ID.
     *
     * @param id    The ID of the file.
     *
     * @return The size of the file.
     */
    public suspend fun getSize(id: Long): Long = withContext(Dispatchers.IO) {
        return@withContext this._apiClient.loadFile(id).execute().size()
    }

    /**
     * Gets all photos in the given folder recursively.
     *
     * @param folder    The folder to get the files from.
     *
     * @return All photos in the given folder.
     */
    private fun photosInFolder(folder: RemoteFolder): Set<RemoteFile> {
        val result = mutableSetOf<RemoteFile>()
        for (file in folder.children()) {
            if (file is RemoteFile) {
                result.add(file)
            }
            else if (file is RemoteFolder) {
                result.addAll(photosInFolder(file))
            }
        }
        return result
    }

    /**
     * Gets the "My Pictures" folder.
     *
     * @return The "My Pictures" folder, or null if it doesn't exist.
     */
    private suspend fun getPicturesFolder(): RemoteFolder? = withContext(Dispatchers.IO) {
        try {
            return@withContext this._apiClient
                .listFolder("/" + photosFolder(this._context))
                .execute()
        }
        catch (e: ApiError) {
            Log.w(this.javaClass.name, e.message, e)
            return@withContext null
        }
    }

    /**
     * Gets the SHA1 of the given file, reading it from cache if needed. Needed because sometimes sha1Checksum is null, in which case it will be cached based on PCloud's internal hash.
     *
     * @return The SHA1 of the file.
     */
    private suspend fun RemoteFile.sha1(): String {
        val fetchedSha1 = withContext(Dispatchers.IO) {
            this@PCloudClient._apiClient.getChecksums(this.fileId()).execute().sha1?.hex()
        }
        if (fetchedSha1 != null) {
            return fetchedSha1
        }
        val pCloudHash = this.hash()
        return getCachedSha1(
            this@PCloudClient._context,
            pCloudHash,
            { this@PCloudClient.getInputStream(this.fileId()) }
        )
    }

    companion object : StorageClient.Companion {
        /**
         * Keys for shared preferences.
         */
        public override val PREFERENCES_KEY = "pcloud"
        public override val DEFAULT_FOLDER = "My Pictures"
        private const val TOKEN = "token"

        /**
         * "Client ID" under https://docs.pcloud.com/my_apps/.
         */
        private const val PCLOUD_CLIENT_ID = "oW1Ww8e27pX"

        /**
         * Authenticates with PCloud.
         *
         * @param context           The context to use.
         * @param signInLauncher    The launcher to use to sign in if the user isn't already signed in. If null and the user isn't already signed in, the function will return null.
         *
         * @return The token, or null if the user isn't signed in.
         *
         * @throws Exception If authentication failed.
         */
        private suspend fun getToken(
            context: Context,
            signInLauncher: SuspendableLauncher<Intent, ActivityResult>?
        ): String? {
            val sharedPreferences =
                context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
            val token = sharedPreferences.getString(TOKEN, null)
            if (token != null) {
                return token
            }

            if (signInLauncher == null) {
                return null
            }

            val request = AuthorizationRequest.create()
                .setType(AuthorizationRequest.Type.TOKEN)
                .setClientId(PCLOUD_CLIENT_ID)
                .setForceAccessApproval(false)
                .build()

            val intent = AuthorizationActivity.createIntent(context, request)
            val activityResult = signInLauncher.launch(intent)
            val data = activityResult.data ?: return null
            val authData = AuthorizationActivity.getResult(data)

            if (authData.result == AuthorizationResult.ACCESS_GRANTED) {
                if (authData.token != null) {
                    context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                        putString(TOKEN, authData.token)
                    }
                }
                return authData.token
            }
            else {
                return null
            }
        }

        /**
         * Authenticates with PCloud.
         *
         * @param context           The context to use.
         * @param signInLauncher    The launcher to use to sign in if the user isn't already signed in. If null and the user isn't already signed in, the function will return null.
         *
         * @return The authenticated client, or null if the user isn't signed in.
         *
         * @throws Exception If authentication failed.
         */
        public suspend fun authenticate(
            context: StorageManagerActivity,
            signInLauncher: SuspendableLauncher<Intent, ActivityResult>?
        ): PCloudClient? {
            val token = getToken(context, signInLauncher) ?: return null
            return PCloudClient(context, token)
        }

        /**
         * Authenticates with PCloud. Overload that allows using any context but does not return the client, as the client needs a StorageManagerActivity context.
         *
         * @param context           The context to use.
         * @param signInLauncher    The launcher to use to sign in if the user isn't already signed in. If null and the user isn't already signed in, the function will return null.
         *
         * @throws Exception If authentication failed.
         */
        public suspend fun authenticate(
            context: Context,
            signInLauncher: SuspendableLauncher<Intent, ActivityResult>?
        ) {
            getToken(context, signInLauncher)
        }

        public override fun name(context: Context): String {
            return context.getString(R.string.pCloud)
        }

        public override suspend fun signOut(context: Activity) {
            context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                remove(TOKEN)
            }
        }
    }
}