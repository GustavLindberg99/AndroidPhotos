package io.github.gustavlindberg99.photos.photo

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.launch
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

object PhotoManager {
    private var _allPhotos: MutableSet<Photo>? = null

    private val _photoAdded = MutableSharedFlow<Photo>()
    private val _photoRemoved = MutableSharedFlow<Photo>()

    private val _autosaveManager = AutosaveManager()

    /**
     * Resets the photo manager by clearing all photos.
     */
    @VisibleForTesting
    fun reset() {
        this._allPhotos?.clear()
    }

    /**
     * Gets all photos.
     */
    public suspend fun allPhotos(context: Context): Set<Photo> {
        val allPhotos = this._allPhotos ?: getCachedPhotos(context)
        this._allPhotos = allPhotos
        return allPhotos
    }

    /**
     * Sets a listener that will be called whenever a photo is added. If photos are already present when this function is called, the callback will be called immediately on those photos.
     *
     * @param context   The context to use.
     * @param listener  The listener to set. The listener will be called with the photo being added and its new index as parameters.
     */
    public fun setPhotoAddedListener(context: ComponentActivity, listener: (Photo) -> Unit) {
        this.setPhotoAddedListener(context, context, listener)
    }

    /**
     * Sets a listener that will be called whenever a photo is added. If photos are already present when this function is called, the callback will be called immediately on those photos.
     *
     * @param context           The context to use.
     * @param lifecycleOwner    The lifecycle owner to use.
     * @param listener          The listener to set. The listener will be called with the photo being added and its new index as parameters.
     */
    public fun setPhotoAddedListener(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        listener: (Photo) -> Unit
    ) {
        val seen = mutableSetOf<Photo>()

        // Run the callback on existing photos
        lifecycleOwner.lifecycleScope.launch {
            for (photo in this.allPhotos(context)) {
                if (photo in this.allPhotos(context) && seen.add(photo)) {
                    listener(photo)
                }
            }
        }

        // Run the callback on photos that will be added in the future
        lifecycleOwner.lifecycleScope.launch {
            this._photoAdded.asSharedFlow().collect { photo ->
                if (seen.add(photo)) {
                    listener(photo)
                }
            }
        }

        // Remove from seen if the photo is removed, so it can be added again if needed
        lifecycleOwner.lifecycleScope.launch {
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
    public suspend fun update(context: Context, photo: Photo, updateCache: Boolean = true): Photo {
        val collidingSha1s = this.allPhotos(context).any {
            it != photo && !Collections.disjoint(photo.handles.entries, it.handles.entries)
        }
        if (collidingSha1s) {
            throw IllegalStateException("Photo with same handles already exists with different SHA1")
        }

        val allPhotos = this._allPhotos ?: getCachedPhotos(context)
        this._allPhotos = allPhotos

        val result: Photo
        if (photo.handles.isEmpty()) {
            allPhotos.remove(photo)
            _photoRemoved.emit(photo)
            result = photo
        }
        else {
            val existingPhoto = this.allPhotos(context).find { it == photo }
            if (existingPhoto == null) {
                allPhotos.add(photo)
                this._photoAdded.emit(photo)
                result = photo
            }
            else {
                existingPhoto.mergeHandlesWith(photo)
                result = existingPhoto
            }
        }
        if (updateCache) {
            setCachedPhotos(context, this.allPhotos(context))
        }
        return result
    }

    /**
     * Removes the given client's URIs from all photos, and deletes the photo if no other client has that photo.
     *
     * @param context   The context to use.
     * @param client    The client to remove.
     */
    public suspend fun removeClient(context: Context, client: KClass<out StorageClient>) {
        for (photo in this.allPhotos(context).toList()) {
            photo.handles.remove(client)
            this.update(context, photo, updateCache = false)
        }
        setCachedPhotos(context, this.allPhotos(context))
    }

    /**
     * Gets the photo at the given index.
     *
     * @param index     The index of the photo to get.
     *
     * @return The photo at the given index.
     *
     * @throws IndexOutOfBoundsException If the index is out of bounds.
     */
    public fun photoFromIndex(index: Int): Photo {
        return this._allPhotos?.elementAt(index)
            ?: throw IndexOutOfBoundsException("Getting photo from index before photos have been loaded")
    }

    /**
     * Gets the index of the given photo.
     *
     * @param photo     The photo to get the index of.
     *
     * @return The index of the given photo, or -1 if the photo is not in the list.
     *
     * @throws NoSuchElementException If the photo is not in the list.
     */
    public fun indexFromPhoto(photo: Photo): Int {
        val allPhotos = this._allPhotos
        val index = allPhotos?.indexOf(photo) ?: -1
        if (index == -1) {
            throw NoSuchElementException("Photo is not in the list")
        }
        return index
    }

    /**
     * Gets the number of photos.
     *
     * @return The number of photos.
     */
    public fun numberOfPhotos(): Int {
        return this._allPhotos?.size ?: 0
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
        val photos = this.allPhotos(context).toList()
        val allPhotoHandles = client.allPhotoHandles()
        for (photo in photos) {
            val handle = photo.handles[client::class] ?: continue
            if (!allPhotoHandles.contains(handle)) {
                photo.handles.remove(client::class)
            }
            this.update(context, photo, updateCache = false)
        }

        // Add new photos
        client.getAllPhotos().collect { photo ->
            this.update(context, photo, updateCache = false)
            if (client is LocalStorageClient) {
                context.lifecycleScope.launch {
                    try {
                        this._autosaveManager.autoUpload(context, photo)
                    }
                    catch (e: Exception) {
                        Log.w(this.javaClass.name, e.message, e)
                    }
                }
            }
            yield()
        }

        // Update the cache
        setCachedPhotos(context, this.allPhotos(context))
    }
}