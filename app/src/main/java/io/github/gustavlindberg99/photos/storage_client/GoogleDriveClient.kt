package io.github.gustavlindberg99.photos.storage_client

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.github.gustavlindberg99.androidsuspendutils.SuspendableLauncher
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import com.github.gustavlindberg99.androidsuspendutils.flow
import com.github.gustavlindberg99.androidsuspendutils.withContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2Credentials
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.GoogleDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.photo.PhotoManager
import io.github.gustavlindberg99.photos.utils.makeGeoPoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume

class GoogleDriveClient private constructor(
    private val _context: StorageManagerActivity,
    private val _signInLauncher: SuspendableLauncher<IntentSenderRequest, ActivityResult>?,
    private var _token: String
) : StorageClient {
    public override val name = this._context.getString(R.string.googleDrive)

    private val _httpClient = HttpClient(Android) {
        expectSuccess = true
        defaultRequest {
            header("Authorization", "Bearer $_token")
        }
    }

    private inner class RefreshableCredentials(accessToken: AccessToken) :
        OAuth2Credentials(accessToken) {
        override fun refreshAccessToken(): AccessToken {
            return runBlocking {
                val newToken = getToken(
                    this@GoogleDriveClient._context,
                    this@GoogleDriveClient._signInLauncher
                ) ?: throw IOException("Failed to refresh Google Drive token")
                this@GoogleDriveClient._token = newToken
                AccessToken(newToken, null)
            }
        }
    }

    private val _service = Drive.Builder(
        NetHttpTransport(),
        GsonFactory(),
        { request ->
            HttpCredentialsAdapter(RefreshableCredentials(AccessToken(_token, null)))
                .initialize(request)
            request.connectTimeout = 3 * 60000 // 3 minutes
            request.readTimeout = 3 * 60000    // 3 minutes
        }).setApplicationName("Photos").build()

    private val _photosFolderManager = object : PhotosFolderManager<File>(
        { photosFolder(this._context) }
    ) {
        protected override suspend fun getSubFolders(parent: File?): List<File> {
            val parentId = parent?.id ?: "root"
            val result = mutableListOf<File>()
            var pageToken: String? = null
            do {
                val fileList = this@GoogleDriveClient._service.files().list()
                    .setQ("mimeType = 'application/vnd.google-apps.folder' and '$parentId' in parents and trashed = false")
                    .setPageToken(pageToken)
                    .execute()
                result.addAll(fileList.files ?: emptyList())
                pageToken = fileList.nextPageToken
            } while (pageToken != null)
            return result
        }

        protected override suspend fun createFolder(parent: File?, name: String): File {
            val newFolder = File()
            newFolder.parents = listOf(parent?.id ?: "root")
            newFolder.name = name
            newFolder.mimeType = "application/vnd.google-apps.folder"
            return this@GoogleDriveClient._service.files()
                .create(newFolder).setFields("id")
                .execute()
        }

        protected override fun fileName(file: File): String = file.name
    }

    public override fun equals(other: Any?): Boolean {
        return other is GoogleDriveClient
    }

    public override fun hashCode(): Int {
        return GoogleDriveClient::class::qualifiedName.hashCode()
    }

    public override fun getAllPhotos(): Flow<Photo> = flow { f ->
        val photosFolderId =
            if (photosFolder(this._context) == "") "root"
            else this._photosFolderManager.getPhotosFolder()?.id ?: return@flow
        val files = this.allPhotoFiles(photosFolderId)
        for (file in files) {
            val photo = this.photoFromGoogleDriveFile(file) ?: continue
            f.emit(photo)
        }
    }

    public override suspend fun allPhotoHandles(): Set<GoogleDriveFileHandle> {
        val photosFolderId =
            if (photosFolder(this._context) == "") "root"
            else this._photosFolderManager.getPhotosFolder()?.id ?: return emptySet()
        return this.allPhotoFiles(photosFolderId).map { GoogleDriveFileHandle(it.id) }.toSet()
    }

    public override suspend fun save(photo: Photo) {
        // Create the Photos folder if it doesn't already exist
        val bytes =
            photo.getInputStream(this._context).useWithContext(Dispatchers.IO) { it.readBytes() }
        val mimeType = photo.mimeType
        val content = ByteArrayContent(mimeType, bytes)
        val photosFolderId =
            if (photosFolder(this._context) == "") "root"
            else this._photosFolderManager.getPhotosFolder()?.id
                ?: this._photosFolderManager.createPhotosFolder().id

        // Check if the file is already uploaded
        val existingFiles = this.allPhotoFiles(photosFolderId)
        val existingFile = existingFiles.find { it.sha1Checksum == photo.sha1 }

        // Upload the file
        val id: String
        if (existingFile == null) {
            val file = File()
            file.name = photo.fileName
            file.parents = listOf(photosFolderId)
            id = withContext(Dispatchers.IO) {
                this._service.files().create(file, content).setFields("id").execute().id
            }
        }
        else {
            id = existingFile.id
        }
        photo.handles[this::class] = GoogleDriveFileHandle(id)
        PhotoManager.update(this._context, photo)
    }

    public override suspend fun overwrite(oldPhoto: Photo, newBytes: ByteArray): Photo {
        val handle = oldPhoto.handles[this::class] as GoogleDriveFileHandle
        val newContent = ByteArrayContent(oldPhoto.mimeType, newBytes)
        val newFile = withContext(Dispatchers.IO) {
            // Using a new File() object for patch semantics ensures we only update the media content
            // and don't include read-only metadata fields that could cause 403 Forbidden errors.
            this._service.files()
                .update(handle.id, File(), newContent)
                .setFields(PHOTO_FIELDS)
                .execute()
        }
        val sha1 = ByteString.of(*newBytes).sha1().hex()
        val newPhoto = this.photoFromGoogleDriveFile(newFile, sha1)
            ?: throw IOException("Cannot read from newly created photo")
        oldPhoto.handles.remove(this::class)
        PhotoManager.update(this._context, oldPhoto)
        return PhotoManager.update(this._context, newPhoto)
    }

    public override suspend fun delete(photo: Photo) {
        val handle = photo.handles[this::class] as GoogleDriveFileHandle
        val content = File()
        content.trashed = true
        withContext(Dispatchers.IO) {
            this._service.files().update(handle.id, content).execute()
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
        return@withContext this._service.files().get(id).setAlt("media").executeMediaAsInputStream()
    }

    /**
     * Gets all the files in the given folder and its subfolders.
     *
     * @param parentId  The ID of the folder to get the photos from.
     *
     * @return A list of all the files in the folder and its subfolders.
     *
     * @throws IOException If the files could not be retrieved.
     */
    private suspend fun allPhotoFiles(parentId: String): List<File> {
        val result = mutableListOf<File>()
        var pageToken: String? = null
        do {
            val fileList = withContext(Dispatchers.IO) {
                this._service.files().list()
                    .setQ("'${parentId}' in parents and trashed = false")
                    .setFields("nextPageToken, files($PHOTO_FIELDS)")
                    .setPageToken(pageToken)
                    .execute()
            }
            for (file in fileList.files ?: emptyList()) {
                if (file.mimeType == "application/vnd.google-apps.folder") {
                    result.addAll(allPhotoFiles(file.id))
                }
                else {
                    result.add(file)
                }
            }
            pageToken = fileList.nextPageToken
        } while (pageToken != null)
        return result
    }

    /**
     * Gets a photo from a Google Drive File object.
     *
     * @param file  The Google Drive File object. Must have been created with `something.setFields(PHOTO_FIELDS).execute()` (or `PHOTO_LIST_FIELDS` for lists).
     * @param sha1  The SHA1 of the photo. Usually the same as the one from Google Drive (in which case this parameter doesn't need to be specified explicitly), but when overwriting a photo it can be different.
     *
     * @return The photo, or null if the File isn't a photo.
     */
    private suspend fun photoFromGoogleDriveFile(
        file: File,
        sha1: String = file.sha1Checksum
    ): Photo? {
        val metadata = file.imageMediaMetadata ?: return null
        val uri = this.idToUri(file.id)

        // We need to get the full EXIF data to know the timezone, as for some reason the Google Drive API includes the time but not the timezone.
        val dateTime: String?
        val timezone: String?
        try {
            val response = _httpClient.get(uri.toString()) {
                header("Range", "bytes=0-131071")
            }
            val headerBytes = response.body<ByteArray>()
            val exifInterface = ExifInterface(headerBytes.inputStream())
            dateTime = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exifInterface.getAttribute(ExifInterface.TAG_DATETIME)
            timezone = exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                ?: exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        }
        catch (_: Exception) {
            return null
        }

        val location =
            makeGeoPoint(metadata.location?.latitude, metadata.location?.longitude)
        val remoteThumbnailUri = file.thumbnailLink?.toUri() ?: uri
        val cachedThumbnailUri = getCachedThumbnailBySha1(
            this._context,
            sha1,
            UriHandle(remoteThumbnailUri)
        ) ?: return null
        return Photo(
            file.name,
            file.mimeType,
            metadata.width,
            metadata.height,
            location,
            sha1,
            dateTime,
            timezone,
            cachedThumbnailUri,
            mutableMapOf(GoogleDriveClient::class to GoogleDriveFileHandle(file.id))
        )
    }

    /**
     * Gets the URI for the file with the given ID.
     *
     * @param id    The ID to get the URI of.
     *
     * @return The URI of the file.
     */
    private fun idToUri(id: String): Uri {
        return "$URI_BASE/$id?alt=media".toUri()
    }

    companion object : StorageClient.Companion {
        public override val PREFERENCES_KEY = "googleDrive"
        public override val DEFAULT_FOLDER = "Photos"
        private const val URI_BASE = "https://www.googleapis.com/drive/v3/files"

        // List of scopes: https://developers.google.com/identity/protocols/oauth2/scopes
        private val SCOPE = listOf("https://www.googleapis.com/auth/drive")

        // The fields needed for photoFromGoogleDriveFile() to work properly
        private const val PHOTO_FIELDS =
            "id, name, mimeType, thumbnailLink, imageMediaMetadata, sha1Checksum"

        /**
         * Authenticates with Google Drive.
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
            signInLauncher: SuspendableLauncher<IntentSenderRequest, ActivityResult>?
        ): String? {
            val requestedScopes = SCOPE.map { Scope(it) }
            val authRequest = AuthorizationRequest.builder()
                .setRequestedScopes(requestedScopes)
                .build()

            val result = suspendCancellableCoroutine { continuation ->
                Identity.getAuthorizationClient(context)
                    .authorize(authRequest)
                    .addOnSuccessListener { authResult ->
                        if (authResult.hasResolution()) {
                            // User is not logged in to Google Drive
                            continuation.resume(authResult.pendingIntent)
                        }
                        else {
                            continuation.resume(authResult.accessToken)
                        }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.failedToLogIn, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                        continuation.resume(e)
                    }
            }
            when (result) {
                is PendingIntent -> {
                    if (signInLauncher == null) {
                        return null
                    }
                    val activityResult = signInLauncher.launch(
                        IntentSenderRequest.Builder(result.intentSender).build()
                    )
                    val authResult = Identity.getAuthorizationClient(context)
                        .getAuthorizationResultFromIntent(activityResult.data)
                    return authResult.accessToken
                }

                is String -> return result
                is Exception -> throw result
                else -> throw IllegalStateException()
            }
        }

        /**
         * Authenticates with Google Drive.
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
            signInLauncher: SuspendableLauncher<IntentSenderRequest, ActivityResult>?
        ): GoogleDriveClient? {
            val token = getToken(context, signInLauncher) ?: return null
            return GoogleDriveClient(context, signInLauncher, token)
        }

        /**
         * Authenticates with Google Drive. Overload that allows using any context but does not return the client, as the client needs a StorageManagerActivity context.
         *
         * @param context           The context to use.
         * @param signInLauncher    The launcher to use to sign in if the user isn't already signed in. If null and the user isn't already signed in, the function will return null.
         *
         * @throws Exception If authentication failed.
         */
        public suspend fun authenticate(
            context: Context,
            signInLauncher: SuspendableLauncher<IntentSenderRequest, ActivityResult>?
        ) {
            getToken(context, signInLauncher)
        }

        public override fun name(context: Context): String {
            return context.getString(R.string.googleDrive)
        }

        public override suspend fun signOut(context: Activity) {
            CredentialManager.create(context)
                .clearCredentialState(ClearCredentialStateRequest())
        }
    }
}