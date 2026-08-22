package io.github.gustavlindberg99.photos.storage_client_utils

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.async
import com.github.gustavlindberg99.androidsuspendutils.launch
import com.github.gustavlindberg99.androidsuspendutils.withContext
import io.github.gustavlindberg99.photos.photo.Media
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.collections.mutableSetOf
import kotlin.coroutines.resume
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance

object UploadManager : LifecycleOwner {
    // LinkedHashSet preserves both insertion order and uniqueness of elements
    private val _queuedUploads =
        mutableMapOf<KClass<out StorageClient>, LinkedHashMap<Media, MutableSet<CancellableContinuation<Unit>>>>()
    private val _currentUploads =
        mutableMapOf<KClass<out StorageClient>, MutableMap<Media, Deferred<Media>>>()
    private val _failedUploads = mutableMapOf<KClass<out StorageClient>, MutableSet<Media>>()

    private val _stateChangedListeners = mutableSetOf<(Media, StorageClient, Int) -> Unit>()

    public override val lifecycle = LifecycleRegistry(this)

    private const val MAX_SIMULTANEOUS_UPLOADS = 10

    public const val QUEUED = -4
    public const val UPLOADING = -3
    public const val FAILED = -2
    public const val FINISHED = -1

    init {
        this.lifecycle.currentState = Lifecycle.State.RESUMED
    }

    /**
     * Resets the photo manager by clearing all photos.
     */
    @VisibleForTesting
    fun reset() {
        this._queuedUploads.clear()
        this._currentUploads.clear()
        this._failedUploads.clear()
        this._stateChangedListeners.clear()
    }

    /**
     * Queues the given photo to be saved to the given client.
     *
     * @param client    The client to upload to.
     * @param photo     The photo to upload.
     *
     * @throws Exception If the upload failed.
     */
    public suspend fun save(client: StorageClient, photo: Media) {
        this.upload(client, photo, {
            client.save(photo, { progress ->
                UploadManager.lifecycleScope.launch {
                    this.notifyListeners(photo, client, progress)
                }
            })
            return@upload photo
        })
    }

    /**
     * Queues the given photo to be overwritten to the given client.
     *
     * @param client    The client to upload to.
     * @param photo     The photo to upload.
     * @param newBytes  The new bytes to upload.
     *
     * @throws Exception If the upload failed.
     */
    public suspend fun overwrite(client: StorageClient, photo: Media, newBytes: ByteArray): Media {
        return this.upload(client, photo, { client.overwrite(photo, newBytes) })
    }

    /**
     * Queues the given photo to be uploaded to the given client.
     *
     * @param client    The client to upload to.
     * @param photo     The photo to upload.
     * @param action    The action to perform on the client. Can be `save` or `overwrite`.
     *
     * @throws Exception If the upload failed.
     */
    private suspend fun upload(
        client: StorageClient,
        photo: Media,
        action: suspend () -> Media
    ): Media {
        val currentUploads = this._currentUploads[client::class] ?: mutableMapOf()
        this._currentUploads[client::class] = currentUploads
        val pendingUploads = this._queuedUploads[client::class] ?: LinkedHashMap()
        this._queuedUploads[client::class] = pendingUploads
        val failedUploads = this._failedUploads[client::class] ?: mutableSetOf()
        this._failedUploads[client::class] = failedUploads
        failedUploads.remove(photo)

        // Queue the photo if there are too many uploads already
        if (currentUploads.size >= MAX_SIMULTANEOUS_UPLOADS) {
            suspendCancellableCoroutine {
                val callListeners = photo !in pendingUploads
                val continuations = pendingUploads[photo] ?: mutableSetOf()
                pendingUploads[photo] = continuations
                continuations.add(it)
                if (callListeners) {
                    UploadManager.lifecycleScope.launch {
                        this.notifyListeners(photo, client, QUEUED)
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
        val promise = this.lifecycleScope.async { action() }
        currentUploads[photo] = promise
        this.notifyListeners(photo, client, UPLOADING)
        try {
            return promise.await()
        }
        catch (e: Exception) {
            this.notifyListeners(photo, client, FAILED)
            failedUploads.add(photo)
            throw e
        }
        finally {
            @Suppress("DeferredResultUnused")
            currentUploads.remove(photo)
            this.notifyListeners(photo, client, FINISHED)

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
    public fun queuedUploads(client: StorageClient): Set<Media> {
        return this._queuedUploads[client::class]?.keys ?: emptySet()
    }

    /**
     * Gets the photos that are currently being uploaded to the given client.
     *
     * @param client    The client to get the photos for.
     */
    public fun currentUploads(client: StorageClient): Set<Media> {
        return this._currentUploads[client::class]?.keys ?: emptySet()
    }

    /**
     * Gets the photos that failed to be uploaded to the given client.
     *
     * @param client    The client to get the photos for.
     */
    public fun failedUploads(client: StorageClient): Set<Media> {
        return this._failedUploads[client::class] ?: emptySet()
    }

    /**
     * Checks if the given photo is queued to be uploaded to any client.
     *
     * @param photo The photo to check.
     *
     * @return True if the photo is queued, false otherwise.
     */
    public fun isQueued(photo: Media): Boolean {
        return this._queuedUploads.values.any { it.containsKey(photo) }
    }

    /**
     * Checks if the given photo is currently being uploaded to any client.
     *
     * @param photo The photo to check.
     *
     * @return True if the photo is being uploaded, false otherwise.
     */
    public fun isUploading(photo: Media): Boolean {
        return this._currentUploads.values.any { it.containsKey(photo) }
    }

    /**
     * Checks if the given photo failed being uploaded to any client.
     *
     * @param photo The photo to check.
     *
     * @return True if the upload failed, false otherwise.
     */
    public fun hasFailed(photo: Media): Boolean {
        return this._failedUploads.values.any { photo in it }
    }

    /**
     * Sets a listener that is called when the state of a photo changes.
     *
     * @param listener  The listener to set. The third parameter will be called with QUEUED when the upload is queued, UPLOADING when the upload has started but no progress is available yet, the progress between 0 and 100 when the upload progresses, and FINISHED when the upload is finished.
     */
    public fun setStateChangedListener(listener: (Media, StorageClient, Int) -> Unit) {
        this._stateChangedListeners.add(listener)
    }

    /**
     * Removes a listener that is called when the state of a photo changes.
     *
     * @param listener  The listener to remove.
     */
    public fun removeStateChangedListener(listener: (Media, StorageClient, Int) -> Unit) {
        this._stateChangedListeners.remove(listener)
    }

    /**
     * Gets the auto upload preferences for the given client.
     *
     * @param context   The context to use.
     * @param client    The client to get the preferences for.
     *
     * @return The auto upload preferences for the given client, or null if it doesn't exist.
     */
    public fun autoUploadPreferences(context: Context, client: StorageClient): SharedPreferences? {
        val companion = client::class.companionObjectInstance
        if (companion !is StorageClient.Companion) {
            return null
        }
        return context.getSharedPreferences(
            companion.PREFERENCES_KEY,
            Context.MODE_PRIVATE
        )
    }

    /**
     * Notifies all listeners that the state of the given photo has changed.
     *
     * @param photo     The photo that has changed.
     * @param client    The client that the photo has changed for.
     * @param progress  The progress of the upload.
     */
    private suspend fun notifyListeners(photo: Media, client: StorageClient, progress: Int) {
        withContext(Dispatchers.Main.immediate) {
            for (listener in this._stateChangedListeners) {
                listener(photo, client, progress)
            }
        }
    }
}