package io.github.gustavlindberg99.photos.storage_client_utils

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.async
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.reflect.KClass

object UploadManager {
    // LinkedHashSet preserves both insertion order and uniqueness of elements
    private val _queuedUploads =
        mutableMapOf<KClass<out StorageClient>, LinkedHashMap<Photo, MutableSet<CancellableContinuation<Unit>>>>()
    private val _currentUploads =
        mutableMapOf<KClass<out StorageClient>, MutableMap<Photo, Deferred<Photo>>>()
    private val _stateChangedListeners = mutableSetOf<(Photo, StorageClient, UploadState) -> Unit>()

    private const val MAX_SIMULTANEOUS_UPLOADS = 10

    public enum class UploadState {
        QUEUED, UPLOADING, FINISHED
    }

    /**
     * Resets the photo manager by clearing all photos.
     */
    @VisibleForTesting
    fun reset() {
        this._queuedUploads.clear()
        this._currentUploads.clear()
        this._stateChangedListeners.clear()
    }

    /**
     * Queues the given photo to be saved to the given client.
     *
     * @param context   The context to use.
     * @param client    The client to upload to.
     * @param photo     The photo to upload.
     *
     * @throws Exception If the upload failed.
     */
    public suspend fun save(context: LifecycleOwner, client: StorageClient, photo: Photo) {
        this.upload(context, client, photo, {
            client.save(photo)
            return@upload photo
        })
    }

    /**
     * Queues the given photo to be overwritten to the given client.
     *
     * @param context   The context to use.
     * @param client    The client to upload to.
     * @param photo     The photo to upload.
     * @param newBytes  The new bytes to upload.
     *
     * @throws Exception If the upload failed.
     */
    public suspend fun overwrite(
        context: LifecycleOwner,
        client: StorageClient,
        photo: Photo,
        newBytes: ByteArray
    ): Photo {
        return this.upload(context, client, photo, { client.overwrite(photo, newBytes) })
    }

    /**
     * Queues the given photo to be uploaded to the given client.
     *
     * @param context   The context to use.
     * @param client    The client to upload to.
     * @param photo     The photo to upload.
     * @param action    The action to perform on the client. Can be `save` or `overwrite`.
     *
     * @throws Exception If the upload failed.
     */
    private suspend fun upload(
        context: LifecycleOwner,
        client: StorageClient,
        photo: Photo,
        action: suspend () -> Photo
    ): Photo {
        val currentUploads = this._currentUploads[client::class] ?: mutableMapOf()
        this._currentUploads[client::class] = currentUploads
        val pendingUploads = this._queuedUploads[client::class] ?: LinkedHashMap()
        this._queuedUploads[client::class] = pendingUploads

        // Queue the photo if there are too many uploads already
        if (currentUploads.size >= MAX_SIMULTANEOUS_UPLOADS) {
            suspendCancellableCoroutine {
                val callListeners = photo !in pendingUploads
                val continuations = pendingUploads[photo] ?: mutableSetOf()
                pendingUploads[photo] = continuations
                continuations.add(it)
                if (callListeners) {
                    for (listener in this._stateChangedListeners) {
                        listener(photo, client, UploadState.QUEUED)
                    }
                }
            }
        }

        // If the photo is already being uploaded, just wait for the existing upload to finish
        val existingPromise = currentUploads[photo]
        if (existingPromise != null) {
            return existingPromise.await()
        }

        // Upload the photo
        val promise = context.lifecycleScope.async { action() }
        currentUploads[photo] = promise
        for (listener in this._stateChangedListeners) {
            listener(photo, client, UploadState.UPLOADING)
        }
        try {
            return promise.await()
        }
        finally {
            @Suppress("DeferredResultUnused")
            currentUploads.remove(photo)
            for (listener in this._stateChangedListeners) {
                listener(photo, client, UploadState.FINISHED)
            }

            // Upload the next photo in the queue
            if (!pendingUploads.isEmpty()) {
                val (nextPhoto, continuations) = pendingUploads.iterator().next()
                pendingUploads.remove(nextPhoto)
                for (continuation in continuations) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    /**
     * Gets the photos that are currently queued to be uploaded to the given client.
     *
     * @param client    The client to get the photos for.
     */
    public fun queuedUploads(client: StorageClient): Set<Photo> {
        return this._queuedUploads[client::class]?.keys ?: emptySet()
    }

    /**
     * Gets the photos that are currently being uploaded to the given client.
     *
     * @param client    The client to get the photos for.
     */
    public fun currentUploads(client: StorageClient): Set<Photo> {
        return this._currentUploads[client::class]?.keys ?: emptySet()
    }

    /**
     * Sets a listener that is called when the state of a photo changes.
     *
     * @param listener  The listener to set.
     */
    public fun setStateChangedListener(listener: (Photo, StorageClient, UploadState) -> Unit) {
        this._stateChangedListeners.add(listener)
    }

    /**
     * Removes a listener that is called when the state of a photo changes.
     *
     * @param listener  The listener to remove.
     */
    public fun removeStateChangedListener(listener: (Photo, StorageClient, UploadState) -> Unit) {
        this._stateChangedListeners.remove(listener)
    }
}