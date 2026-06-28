package io.github.gustavlindberg99.photos.photo

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.launch
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import io.github.gustavlindberg99.photos.storage_client.getCachedPhotos
import io.github.gustavlindberg99.photos.storage_client.setCachedPhotos
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.yield
import java.util.Collections
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance

object PhotoManager {
    private val _allPhotos = getCachedPhotos()

    private val _photoAdded = MutableSharedFlow<Photo>()
    private val _photoRemoved = MutableSharedFlow<Photo>()

    /**
     * Resets the photo manager by clearing all photos.
     */
    @VisibleForTesting
    fun reset() {
        _allPhotos.clear()
    }

    /**
     * Gets all photos.
     */
    public fun allPhotos(): Set<Photo> {
        return this._allPhotos
    }

    /**
     * Sets a listener that will be called whenever a photo is added. If photos are already present when this function is called, the callback will be called immediately on those photos.
     *
     * @param listener  The listener to set. The listener will be called with the photo being added and its new index as parameters.
     */
    public fun setPhotoAddedListener(context: LifecycleOwner, listener: (Photo) -> Unit) {
        val seen = mutableSetOf<Photo>()

        // Run the callback on existing photos
        context.lifecycleScope.launch {
            for (photo in _allPhotos.toList().asReversed()) {
                if (photo in _allPhotos && seen.add(photo)) {
                    listener(photo)
                }
            }
        }

        // Run the callback on photos that will be added in the future
        context.lifecycleScope.launch {
            _photoAdded.asSharedFlow().collect { photo ->
                if (seen.add(photo)) {
                    listener(photo)
                }
            }
        }

        // Remove from seen if the photo is removed, so it can be added again if needed
        context.lifecycleScope.launch {
            _photoRemoved.asSharedFlow().collect { seen.remove(it) }
        }
    }

    /**
     * Sets a listener that will be called whenever a photo is removed.
     *
     * @param listener  The listener to set. The listener will be called with the photo being removed and its old index as parameters.
     */
    public fun setPhotoRemovedListener(context: LifecycleOwner, listener: (Photo) -> Unit) {
        context.lifecycleScope.launch {
            _photoRemoved.asSharedFlow().collect { listener(it) }
        }
    }

    /**
     * Updates the given photo in the list of all photos, i.e. adds it if it doesn't exist yet, updates the URIs if the one that exists doesn't have all URIs, and deletes it if it doesn't have any URIs.
     *
     * @param photo         The photo to update.
     * @param updateCache   True to update the cache, false to not.
     *
     * @return The updated photo, either the existing one if it already exists, or the new one if it doesn't.
     */
    public suspend fun update(photo: Photo, updateCache: Boolean = true): Photo {
        if (this._allPhotos.any
            { it != photo && !Collections.disjoint(photo.handles.entries, it.handles.entries) }
        ) {
            throw IllegalStateException("Photo with same handles already exists with different SHA1")
        }

        val result: Photo
        if (photo.handles.isEmpty()) {
            this._allPhotos.remove(photo)
            _photoRemoved.emit(photo)
            result = photo
        }
        else {
            val existingPhoto = _allPhotos.find { it == photo }
            if (existingPhoto == null) {
                _allPhotos.add(photo)
                _photoAdded.emit(photo)
                result = photo
            }
            else {
                existingPhoto.mergeHandlesWith(photo)
                result = existingPhoto
            }
        }
        if (updateCache) {
            setCachedPhotos(this._allPhotos)
        }
        return result
    }

    /**
     * Removes the given client's URIs from all photos, and deletes the photo if no other client has that photo.
     *
     * @param client    The client to remove.
     */
    public suspend fun removeClient(client: KClass<out StorageClient>) {
        for (photo in this._allPhotos.toList()) {
            photo.handles.remove(client)
            this.update(photo, updateCache = false)
        }
        setCachedPhotos(this._allPhotos)
    }

    /**
     * Gets the photo at the given index.
     *
     * @param index The index of the photo to get.
     *
     * @return The photo at the given index.
     *
     * @throws IndexOutOfBoundsException If the index is out of bounds.
     */
    public fun photoFromIndex(index: Int): Photo {
        return this._allPhotos.elementAt(this._allPhotos.size - 1 - index)
    }

    /**
     * Gets the index of the given photo.
     *
     * @param photo The photo to get the index of.
     *
     * @return The index of the given photo, or -1 if the photo is not in the list.
     */
    public fun indexFromPhoto(photo: Photo): Int {
        return this._allPhotos.size - 1 - this._allPhotos.indexOf(photo)
    }

    /**
     * Gets the number of photos.
     *
     * @return The number of photos.
     */
    public fun numberOfPhotos(): Int {
        return this._allPhotos.size
    }

    /**
     * Syncs the photos from the given client by adding new photos to the local list and deleting photos that no longer exist. Returns when the photos have been synced.
     *
     * @param context   The context to use.
     * @param client    The client to sync from.
     *
     * @throws Exception If the photos could not be synced.
     */
    public suspend fun syncPhotos(context: StorageManagerActivity, client: StorageClient) {
        // Delete photos that no longer exist
        val photos = this._allPhotos.toList()
        val allPhotoHandles = client.allPhotoHandles()
        for (photo in photos) {
            val handle = photo.handles[client::class] ?: continue
            if (!allPhotoHandles.contains(handle)) {
                photo.handles.remove(client::class)
            }
            this.update(photo, updateCache = false)
        }

        // Add new photos
        client.getAllPhotos().collect { photo ->
            this.update(photo, updateCache = false)
            if (client is LocalStorageClient) {
                context.lifecycleScope.launch {
                    try {
                        this.autoUpload(context, photo)
                    }
                    catch (e: Exception) {
                        Log.w(this.javaClass.name, e.message, e)
                    }
                }
            }
            yield()
        }

        // Update the cache
        setCachedPhotos(this._allPhotos)
    }

    /**
     * Automatically uploads the photo to all clients for which auto upload is enabled if needed.
     *
     * @param context   The context to use.
     * @param photo     The photo to upload.
     *
     * @throws Exception If it could not be determined whether the photo should be uploaded. If it should be uploaded but the upload itself failed, logs a toast instead. This is so to that it can be handled so that toasts aren't shown for every photo.
     */
    private suspend fun autoUpload(context: StorageManagerActivity, photo: Photo) {
        val clients = context.storageClients()
        for (client in clients) {
            val companion = client::class.companionObjectInstance
            if (companion !is StorageClient.Companion) {
                continue
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
                continue
            }

            // If the photo is already on the client, skip
            if (client::class in photo.handles) {
                continue
            }

            // If the photo is in the ignore list, skip
            val ignoreList = preferences.getStringSet(
                StorageClient.Companion.IGNORED_PHOTOS_FOR_AUTOMATIC_UPLOAD,
                null
            )?.toMutableSet() ?: mutableSetOf()
            if (photo.sha1 in ignoreList) {
                continue
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
}