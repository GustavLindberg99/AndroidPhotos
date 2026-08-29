package io.github.gustavlindberg99.photos.file_handle

import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.storage_client.OneDriveStorageClient
import io.github.gustavlindberg99.photos.storage_client.PCloudClient
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import io.github.gustavlindberg99.photos.utils.mapOfNotNull
import org.json.JSONObject
import java.util.Collections
import kotlin.reflect.KClass

class HandleList(
    localStorageHandle: UriHandle? = null,
    googleDriveHandle: GoogleDriveFileHandle? = null,
    oneDriveHandle: OneDriveFileHandle? = null,
    pCloudHandle: PCloudFileHandle? = null
) {
    private val _handles = mapOfNotNull(
        LocalStorageClient::class to localStorageHandle,
        GoogleDriveClient::class to googleDriveHandle,
        OneDriveStorageClient::class to oneDriveHandle,
        PCloudClient::class to pCloudHandle
    ).toMutableMap()

    public var localStorageHandle: UriHandle?
        get() = this._handles[LocalStorageClient::class] as UriHandle?
        set(value) = this.setHandle(LocalStorageClient::class, value)

    public var googleDriveHandle: GoogleDriveFileHandle?
        get() = this._handles[GoogleDriveClient::class] as GoogleDriveFileHandle?
        set(value) = this.setHandle(GoogleDriveClient::class, value)

    public var oneDriveHandle: OneDriveFileHandle?
        get() = this._handles[OneDriveStorageClient::class] as OneDriveFileHandle?
        set(value) = this.setHandle(OneDriveStorageClient::class, value)

    public var pCloudHandle: PCloudFileHandle?
        get() = this._handles[PCloudClient::class] as PCloudFileHandle?
        set(value) = this.setHandle(PCloudClient::class, value)

    public override fun equals(other: Any?): Boolean {
        return other is HandleList && this._handles == other._handles
    }

    public override fun hashCode(): Int {
        return this._handles.hashCode()
    }

    public override fun toString(): String {
        return this._handles.toString()
    }

    /**
     * Gets the handle for the given storage client.
     *
     * @param client    The storage client to get the handle for.
     *
     * @return The handle for the given storage client, or null if it doesn't exist.
     */
    public fun getHandle(client: KClass<out StorageClient>): FileHandle? {
        return this._handles[client]
    }

    /**
     * Sets the handle for the given storage client. Private, but needs to be visible for testing so that the extensions in `StorageClientMock` can access it.
     *
     * @param client    The storage client to set the handle for.
     * @param handle    The handle to set for the given storage client.
     *
     * @return The handle for the given storage client, or null if it doesn't exist.
     */
    @VisibleForTesting
    fun setHandle(client: KClass<out StorageClient>, handle: FileHandle?) {
        if (handle == null) {
            this._handles.remove(client)
        }
        else {
            this._handles[client] = handle
        }
    }

    /**
     * Gets the local storage handle if possible, otherwise one of the remote handles.
     *
     * @return The preferred handle to use.
     *
     * @throws NoSuchElementException If no handle is available.
     */
    public fun preferredHandle(): FileHandle {
        return this.localStorageHandle ?: this._handles.values.first()
    }

    /**
     * Checks if the handle list is disjoint with the other handle list, the two handle lists don't share any handles.
     *
     * @param other The other handle list to check against.
     *
     * @return True if the handle lists are disjoint, false otherwise.
     */
    public fun isDisjoint(other: HandleList): Boolean {
        return Collections.disjoint(this._handles.entries, other._handles.entries)
    }

    /**
     * Makes `this` disjoint from [other] by removing any handles from `this` that are in both.
     *
     * @param other The other handle list to check against.
     */
    public fun makeDisjoint(other: HandleList) {
        for (entry in this._handles.entries.toSet()) {
            if (entry in other._handles.entries) {
                this._handles.remove(entry.key)
            }
        }
    }

    /**
     * Checks if the handle list has at least one handle.
     *
     * @return True if the handle list has at least one handle, false otherwise.
     */
    public fun isEmpty(): Boolean {
        return this._handles.isEmpty()
    }

    /**
     * Clears the handle list.
     */
    public fun clear() {
        this._handles.clear()
    }

    /**
     * Checks if photo for this handle list is backed up to at least one non-local storage client.
     *
     * @return True if the photo is backed up, false otherwise.
     */
    public fun isBackedUp(): Boolean {
        return this._handles.keys.any { it != LocalStorageClient::class }
    }

    /**
     * Gets the clients that have handles for this handle list.
     *
     * @return The clients that have handles for this handle list.
     */
    public fun clients(): Set<KClass<out StorageClient>> {
        return this._handles.keys
    }

    /**
     * Checks if this client list would be empty if all handles corresponding to the given clients are removed.
     *
     * @param clients   The clients to check for.
     *
     * @return True if this client list would be empty if all handles corresponding to the given clients are removed, false otherwise.
     */
    public fun isLastHandle(clients: Collection<KClass<out StorageClient>>): Boolean {
        return clients.containsAll(this._handles.keys)
    }

    /**
     * Removes the handle for the given storage client.
     *
     * @param client    The storage client to remove the handle for.
     */
    public fun removeHandle(client: KClass<out StorageClient>) {
        this._handles.remove(client)
    }

    /**
     * If this handle list has handles with storage services that the other one doesn't have, removes those handles from this object.
     *
     * @param other The handle list to get the handles from.
     */
    public fun removeExtraHandles(other: HandleList) {
        this._handles.keys.removeAll { it !in other._handles }
    }

    /**
     * If the other handle list has handles with storage services that this one doesn't have, copies those handles into this object.
     *
     * @param other The handle list to get the handles from.
     */
    public fun mergeHandlesWith(other: HandleList) {
        this._handles.putAll(other._handles)
    }

    /**
     * Creates a JSON object representing this handle list.
     *
     * @return The JSON object.
     */
    public fun toJson(): JSONObject {
        val stringMap = this._handles
            .mapKeys { it.key.qualifiedName!! }
            .mapValues { it.value.toString() }
        return JSONObject(stringMap)
    }

    companion object {
        public fun fromJson(json: JSONObject): HandleList {
            val localStorageHandle =
                if (json.has(LocalStorageClient::class.qualifiedName))
                    UriHandle(json.getString(LocalStorageClient::class.qualifiedName!!).toUri())
                else null
            val googleDriveHandle =
                if (json.has(GoogleDriveClient::class.qualifiedName))
                    GoogleDriveFileHandle(json.getString(GoogleDriveClient::class.qualifiedName!!))
                else null
            val oneDriveHandle =
                if (json.has(OneDriveStorageClient::class.qualifiedName))
                    OneDriveFileHandle(json.getString(OneDriveStorageClient::class.qualifiedName!!))
                else null
            val pCloudHandle =
                if (json.has(PCloudClient::class.qualifiedName))
                    PCloudFileHandle(json.getString(PCloudClient::class.qualifiedName!!).toLong())
                else null
            return HandleList(localStorageHandle, googleDriveHandle, oneDriveHandle, pCloudHandle)
        }
    }
}