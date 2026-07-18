package io.github.gustavlindberg99.photos.storage_client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.content.edit
import com.github.gustavlindberg99.androidsuspendutils.SuspendableLauncher
import com.github.gustavlindberg99.androidsuspendutils.flow
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import com.github.gustavlindberg99.androidsuspendutils.withContext
import com.pcloud.sdk.ApiClient
import com.pcloud.sdk.ApiError
import com.pcloud.sdk.Authenticators
import com.pcloud.sdk.AuthorizationActivity
import com.pcloud.sdk.AuthorizationRequest
import com.pcloud.sdk.AuthorizationResult
import com.pcloud.sdk.DataSource
import com.pcloud.sdk.PCloudSdk
import com.pcloud.sdk.RemoteFile
import com.pcloud.sdk.RemoteFolder
import com.pcloud.sdk.UploadOptions
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.PCloudFileHandle
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.PhotoManager
import io.github.gustavlindberg99.photos.storage_client_utils.getCachedPCloudSha1
import io.github.gustavlindberg99.photos.storage_client_utils.getCachedPhotoBySha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import okio.ByteString
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

    public override val name = this._context.getString(R.string.pCloud)

    public override fun equals(other: Any?): Boolean {
        return other is PCloudClient
    }

    public override fun hashCode(): Int {
        return PCloudClient::class::qualifiedName.hashCode()
    }

    public override fun getAllPhotos(): Flow<Photo> = flow { f ->
        val picturesFolder = this.getPicturesFolder() ?: return@flow
        val photoFiles = this.photosInFolder(picturesFolder)
        for (file in photoFiles) {
            val mimeType = URLConnection.guessContentTypeFromName(file.name()) ?: continue
            val sha1 = getCachedPCloudSha1(this._context, this, file, this._apiClient)
            val photo = getCachedPhotoBySha1(
                this._context,
                file.name(),
                mimeType,
                sha1,
                mutableMapOf(this::class to PCloudFileHandle(file.fileId()))
            ) ?: continue
            f.emit(photo)
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

    public override suspend fun save(photo: Photo) {
        // Create the "My Pictures" folder if it doesn't already exist
        val picturesFolder = getPicturesFolder() ?: withContext(Dispatchers.IO) {
            _apiClient.createFolder("/" + photosFolder(this._context)).execute()
        }

        // Check if the file is already uploaded
        val existingFiles = photosInFolder(picturesFolder)
        val existingFile = existingFiles.find {
            it.name() == photo.fileName &&
            getCachedPCloudSha1(this._context, this, it, this._apiClient) == photo.sha1
        }

        // Upload the file
        val id: Long
        if (existingFile == null) {
            val bytes = photo.getInputStream(this._context)
                .useWithContext(Dispatchers.IO) { it.readBytes() }
            val file = withContext(Dispatchers.IO) {
                this._apiClient.createFile(
                    picturesFolder,
                    photo.fileName,
                    DataSource.create(bytes)
                ).execute()
            }
            id = file.fileId()
        }
        else {
            id = existingFile.fileId()
        }
        photo.handles[this::class] = PCloudFileHandle(id)
        PhotoManager.update(this._context, photo)
    }

    public override suspend fun overwrite(oldPhoto: Photo, newBytes: ByteArray): Photo {
        val handle = oldPhoto.handles[this::class] as PCloudFileHandle
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
            mutableMapOf(this::class to PCloudFileHandle(newFile.fileId()))
        ) ?: throw IOException("Cannot read from newly created photo")
        oldPhoto.handles.remove(this::class)
        PhotoManager.update(this._context, oldPhoto)
        return PhotoManager.update(this._context, newPhoto)
    }

    public override suspend fun delete(photo: Photo) {
        val id = photo.handles[this::class] as PCloudFileHandle
        withContext(Dispatchers.IO) {
            _apiClient.deleteFile(id.id).execute()
        }
        photo.handles.remove(this::class)
        PhotoManager.update(this._context, photo, delete = true)
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
    public suspend fun getInputStream(id: Long): InputStream = withContext(Dispatchers.IO) {
        return@withContext _apiClient.loadFile(id).execute().byteStream()
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