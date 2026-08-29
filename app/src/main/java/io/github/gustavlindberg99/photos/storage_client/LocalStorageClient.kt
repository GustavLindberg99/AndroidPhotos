package io.github.gustavlindberg99.photos.storage_client

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import com.github.gustavlindberg99.androidsuspendutils.SuspendableLauncher
import com.github.gustavlindberg99.androidsuspendutils.channelFlow
import com.github.gustavlindberg99.androidsuspendutils.concurrentForEach
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import com.github.gustavlindberg99.androidsuspendutils.withContext
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.HandleList
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.photo.Media
import io.github.gustavlindberg99.photos.storage_client_utils.PhotoManager
import io.github.gustavlindberg99.photos.photo.Video
import io.github.gustavlindberg99.photos.storage_client_utils.getCachedPhotoBySha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class LocalStorageClient private constructor(
    private val _context: StorageManagerActivity,
    private val _intentSenderLauncher: SuspendableLauncher<IntentSenderRequest, ActivityResult>
) : StorageClient {
    private val _mutex = Mutex()

    public override val name = this._context.getString(R.string.localStorage)

    public override fun equals(other: Any?): Boolean {
        return other is LocalStorageClient
    }

    public override fun hashCode(): Int {
        return LocalStorageClient::class::qualifiedName.hashCode()
    }

    public override fun getAllPhotos(): Flow<Media> = channelFlow { f ->
        val existingFileNames = PhotoManager.allPhotos(this._context)
            .filter { it.handles.localStorageHandle != null }
            .map { it.fileName }.toSet()

        // Sort to put the file names that don't exist yet first so that we don't need to wait too long to see new photos (using the SHA1s would have slower but been more reliable, but since the only reason to sort this is for optimization, it's better to use the faster and less reliable option)
        val allMediaEntries = this.allMediaEntries().sortedBy { it.first in existingFileNames }

        allMediaEntries.concurrentForEach(this._context, 10) { (fileName, mimeType, uri) ->
            val photo = try {
                getCachedPhotoBySha1(
                    this._context,
                    fileName,
                    mimeType,
                    this.sha1FromUri(uri) ?: return@concurrentForEach,
                    HandleList(localStorageHandle = UriHandle(uri))
                ) ?: return@concurrentForEach
            }
            catch (e: Exception) {
                Log.w(this.javaClass.name, e.message, e)
                return@concurrentForEach
            }
            f.send(photo)
        }
    }

    public override suspend fun allPhotoHandles(): Set<UriHandle> {
        return this.allMediaEntries().map { UriHandle(it.third) }.toSet()
    }

    public override suspend fun save(photo: Media, progressListener: (Int) -> Unit) {
        val contentValues = ContentValues()
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, photo.fileName)
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, photo.mimeType)
        val dateTime = photo.dateTime
        if (dateTime != null) {
            contentValues.put(MediaStore.MediaColumns.DATE_TAKEN, dateTime.time)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + File.separator + "Camera"
            )
            // Keeps file exclusive to your app while writing
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        else {
            // On older versions, we manually construct the path in DCIM/Camera
            val directory =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val cameraDir = File(directory, "Camera")
            if (!cameraDir.exists()) {
                cameraDir.mkdirs()
            }
            val file = File(cameraDir, photo.fileName)
            contentValues.put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
            if (photo is Video) MediaStore.Video.Media.getContentUri(volume)
            else MediaStore.Images.Media.getContentUri(volume)
        }
        else {
            if (photo is Video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = this._context.contentResolver.insert(collection, contentValues)
            ?: throw IOException("Failed to create new media entry")
        val outputStream = this._context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Failed to open output stream")
        outputStream.use {
            photo.getInputStream(this._context).useWithContext(Dispatchers.IO) { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            this._context.contentResolver.update(uri, contentValues, null, null)
        }
        photo.handles.localStorageHandle = UriHandle(uri)
        PhotoManager.update(this._context, photo)
    }

    public override suspend fun overwrite(oldPhoto: Media, newBytes: ByteArray): Media {
        val handle = oldPhoto.handles.localStorageHandle
            ?: throw IOException("Photo is not on local storage")

        this.askForPermissionIfNeeded {
            this._context.contentResolver.openOutputStream(handle.uri())?.use { output ->
                newBytes.inputStream().useWithContext(Dispatchers.IO) { input ->
                    input.copyTo(output)
                }
            }
        }

        val newSha1 = newBytes.toByteString().sha1().hex()
        val newPhoto = getCachedPhotoBySha1(
            this._context,
            oldPhoto.fileName,
            oldPhoto.mimeType,
            newSha1,
            HandleList(localStorageHandle = handle)
        ) ?: throw IOException("Cannot read from newly created photo")
        oldPhoto.handles.localStorageHandle = null
        PhotoManager.update(this._context, oldPhoto)
        return PhotoManager.update(this._context, newPhoto)
    }

    public override suspend fun delete(photo: Media) {
        val handle = photo.handles.localStorageHandle ?: return

        this.askForPermissionIfNeeded {
            this._context.contentResolver.delete(handle.uri(), null, null)
        }

        photo.handles.localStorageHandle = null
        PhotoManager.update(this._context, photo, delete = true)
    }

    public override suspend fun dataFactory(context: Context): DataSource.Factory {
        return DefaultDataSource.Factory(context)
    }

    /**
     * Runs the given callback, and if it throws a RecoverableSecurityException, shows a dialog asking the user for permission and then runs it again.
     *
     * @param callback  The callback to run.
     */
    private suspend fun askForPermissionIfNeeded(callback: suspend () -> Unit) {
        // Lock the mutex since the SDK doesn't support handling one SecurityException while another one is already queued.
        this._mutex.withLock {
            try {
                callback()
            }
            catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                    val intentSender = e.userAction.actionIntent.intentSender
                    val intentSenderRequest = IntentSenderRequest.Builder(intentSender).build()

                    // Launch the system dialog asking the user for permission
                    this._intentSenderLauncher.launch(intentSenderRequest)

                    callback()
                }
                else {
                    throw e
                }
            }
        }
    }

    /**
     * Gets all media entries in the device's photo gallery.
     *
     * @return A list of triples with the file name, the MIME type, and the URI of the photo.
     */
    private suspend fun allMediaEntries(
    ): List<Triple<String, String, Uri>> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Triple<String, String, Uri>>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val collections = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        )
        for (collection in collections) {
            val query = this._context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )
            query?.use { cursor ->
                while (cursor.moveToNext()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeTypeColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                    val id = cursor.getLong(idColumn)
                    val fileName = cursor.getString(nameColumn)
                    val mimeType = cursor.getString(mimeTypeColumn)
                    val rawUri =
                        ContentUris.withAppendedId(collection, id)
                    // Set extra permissions on newer Android versions. Older versions don't need this.
                    val uri =
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) rawUri
                        else MediaStore.setRequireOriginal(rawUri)

                    result.add(Triple(fileName, mimeType, uri))
                }
            }
        }
        return@withContext result
    }

    /**
     * Gets the SHA1 hash of the file at the given URI.
     *
     * @param uri   The URI of the file. Must be a local URI.
     *
     * @return The SHA1 hash of the file, or null if the file doesn't exist.
     */
    private suspend fun sha1FromUri(uri: Uri): String? {
        val sha1 = HashingSink.sha1(blackholeSink())
        try {
            this._context.contentResolver.openInputStream(uri)
                ?.useWithContext(Dispatchers.IO) { it.source().buffer().readAll(sha1) }
                ?: return null
        }
        catch (_: FileNotFoundException) {
            // This race condition can happen after deleting a file. If it happens, ignore it, since it means the file is deleted.
            return null
        }
        return sha1.hash.hex()
    }

    companion object {
        /**
         * Requests permissions if necessary.
         *
         * @param context               The context to use.
         * @param permissionLauncher    The launcher to use to request permissions if the user doesn't already have permissions. If null and the user doesn't have permissions, the function will return null.
         * @param intentSenderLauncher  The launcher to use to handle recoverable security exceptions.
         *
         * @return The authenticated client, or null if the user isn't signed in.
         */
        public suspend fun authenticate(
            context: StorageManagerActivity,
            permissionLauncher: SuspendableLauncher<Array<String>, Map<String, Boolean>>?,
            intentSenderLauncher: SuspendableLauncher<IntentSenderRequest, ActivityResult>
        ): LocalStorageClient? {
            val permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            val checkPermissionResult = permissions.all {
                ContextCompat.checkSelfPermission(context, it) ==
                PackageManager.PERMISSION_GRANTED
            }
            if (checkPermissionResult) {
                return LocalStorageClient(context, intentSenderLauncher)
            }
            else if (permissionLauncher == null) {
                return null
            }
            else {
                val isGranted = permissionLauncher.launch(permissions).values.all { it }
                return if (isGranted) LocalStorageClient(context, intentSenderLauncher) else null
            }
        }
    }
}