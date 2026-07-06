package io.github.gustavlindberg99.photos.photo

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import java.util.LinkedList
import java.util.Queue
import kotlin.reflect.full.companionObjectInstance

class AutosaveManager {
    private var _photosBeingUploaded = 0
    private val _pendingUploads: Queue<Photo> = LinkedList()

    companion object {
        private const val MAX_SIMULTANEOUS_UPLOADS = 10
    }

    /**
     * Automatically uploads the photo to all clients for which auto upload is enabled if needed.
     *
     * @param context   The context to use.
     * @param photo     The photo to upload.
     *
     * @throws Exception If it could not be determined whether the photo should be uploaded. If it should be uploaded but the upload itself failed, logs a toast instead. This is so to that it can be handled so that toasts aren't shown for every photo.
     */
    public suspend fun autoUpload(context: StorageManagerActivity, photo: Photo) {
        if (this._photosBeingUploaded >= MAX_SIMULTANEOUS_UPLOADS) {
            this._pendingUploads.add(photo)
            return
        }

        this._photosBeingUploaded++
        try {
            val clients = context.storageClients()
            for (client in clients) {
                this.uploadToClient(context, photo, client)
            }
        }
        finally {
            this._photosBeingUploaded--
            val nextWaitingPhoto: Photo? = this._pendingUploads.poll()
            if (nextWaitingPhoto != null) {
                this.autoUpload(context, nextWaitingPhoto)
            }
        }
    }

    /**
     * Auto-uploads the photo to the given client if needed.
     *
     * @param context   The context to use.
     * @param photo     The photo to upload.
     * @param client    The client to upload to.
     *
     * @throws Exception If it could not be determined whether the photo should be uploaded. If it should be uploaded but the upload itself failed, logs a toast instead. This is so to that it can be handled so that toasts aren't shown for every photo.
     */
    private suspend fun uploadToClient(context: Context, photo: Photo, client: StorageClient) {
        val companion = client::class.companionObjectInstance
        if (companion !is StorageClient.Companion) {
            return
        }
        val preferences = context.getSharedPreferences(
            companion.PREFERENCES_KEY,
            Context.MODE_PRIVATE
        )

        // If auto upload is disabled, skip
        val autoUpload = preferences.getBoolean(
            StorageClient.Companion.AUTOMATIC_UPLOAD,
            false
        )
        if (!autoUpload) {
            return
        }

        // If the photo is already on the client, skip
        if (client::class in photo.handles) {
            return
        }

        // If the photo is in the ignore list, skip
        val ignoreList = preferences.getStringSet(
            StorageClient.Companion.IGNORED_PHOTOS_FOR_AUTOMATIC_UPLOAD,
            null
        )?.toMutableSet() ?: mutableSetOf()
        if (photo.sha1 in ignoreList) {
            return
        }

        try {
            // Save the photo to the client
            client.save(photo)

            // Add the photo to the ignore list in case the user deletes it
            ignoreList.add(photo.sha1)
            preferences.edit {
                putStringSet(
                    StorageClient.Companion.IGNORED_PHOTOS_FOR_AUTOMATIC_UPLOAD,
                    ignoreList
                )
            }
        }
        catch (e: Exception) {
            Log.w(this.javaClass.name, e.message, e)
            Toast.makeText(
                context,
                context.getString(R.string.autoUploadFailed, photo.fileName),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}