package io.github.gustavlindberg99.photos.storage_client

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import com.github.gustavlindberg99.androidsuspendutils.SuspendableLauncher
import com.github.gustavlindberg99.androidsuspendutils.flow
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.photo.PhotoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    public override fun getAllPhotos(): Flow<Photo> = flow { f ->
        for ((fileName, mimeType, uri) in this.allMediaEntries()) {
            // Set extra permissions on newer Android versions. Older versions don't need this.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.setRequireOriginal(uri)
            }
            val sha1 = HashingSink.sha1(blackholeSink())
            try {
                this._context.contentResolver.openInputStream(uri)
                    ?.use { it.source().buffer().readAll(sha1) } ?: continue
            }
            catch (_: FileNotFoundException) {
                // This race condition can happen after deleting a file. If it happens, ignore it, since it means the file is deleted.
                continue
            }
            val photo = getCachedPhotoBySha1(
                this._context,
                fileName,
                mimeType,
                sha1.hash.hex(),
                mutableMapOf(LocalStorageClient::class to UriHandle(uri))
            ) ?: continue
            f.emit(photo)
        }
    }

    public override suspend fun allPhotoHandles(): Set<UriHandle> {
        return this.allMediaEntries().map { UriHandle(it.third) }.toSet()
    }

    public override suspend fun save(photo: Photo) {
        val contentValues = ContentValues()
        contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, photo.fileName)
        contentValues.put(MediaStore.Images.Media.MIME_TYPE, photo.mimeType)
        if (photo.dateTime != null) {
            contentValues.put(MediaStore.Images.Media.DATE_TAKEN, photo.dateTime.time)
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

        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

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
        photo.handles[this::class] = UriHandle(uri)
    }

    public override suspend fun overwrite(oldPhoto: Photo, newBytes: ByteArray): Photo {
        val handle = oldPhoto.handles[this::class] as UriHandle

        this.askForPermissionIfNeeded {
            this._context.contentResolver.openOutputStream(handle.uri)?.use { output ->
                newBytes.inputStream().useWithContext(Dispatchers.IO) { input ->
                    input.copyTo(output)
                }
            }
        }

        val sha1 = HashingSink.sha1(blackholeSink())
        this._context.contentResolver.openInputStream(handle.uri)
            ?.use { it.source().buffer().readAll(sha1) }
            ?: throw IOException("Failed to read SHA1 from overwritten photo")
        val newPhoto = getCachedPhotoBySha1(
            this._context,
            oldPhoto.fileName,
            oldPhoto.mimeType,
            sha1.hash.hex(),
            mutableMapOf(this::class to handle)
        ) ?: throw IOException("Cannot read from newly created photo")
        oldPhoto.handles.remove(this::class)
        PhotoManager.update(oldPhoto)
        return PhotoManager.update(newPhoto)
    }

    public override suspend fun delete(photo: Photo) {
        val handle = photo.handles[this::class] as UriHandle

        this.askForPermissionIfNeeded {
            this._context.contentResolver.delete(handle.uri, null, null)
        }

        photo.handles.remove(this::class)
        PhotoManager.update(photo)
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
    private fun allMediaEntries(): List<Triple<String, String, Uri>> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE
        )
        val query = this._context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        val result = mutableListOf<Triple<String, String, Uri>>()
        query?.use { cursor ->
            while (cursor.moveToNext()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                val id = cursor.getLong(idColumn)
                val fileName = cursor.getString(nameColumn)
                val mimeType = cursor.getString(mimeTypeColumn)
                val uri =
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                result.add(Triple(fileName, mimeType, uri))
            }
        }
        return result
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
            permissionLauncher: SuspendableLauncher<String, Boolean>?,
            intentSenderLauncher: SuspendableLauncher<IntentSenderRequest, ActivityResult>
        ): LocalStorageClient? {
            val permission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
            val checkPermissionResult = ContextCompat.checkSelfPermission(context, permission)
            if (checkPermissionResult == PackageManager.PERMISSION_GRANTED) {
                return LocalStorageClient(context, intentSenderLauncher)
            }
            else if (permissionLauncher == null) {
                return null
            }
            else {
                val isGranted = permissionLauncher.launch(permission)
                return if (isGranted) LocalStorageClient(context, intentSenderLauncher) else null
            }
        }
    }
}