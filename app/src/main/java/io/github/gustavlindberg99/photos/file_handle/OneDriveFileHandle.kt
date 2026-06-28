package io.github.gustavlindberg99.photos.file_handle

import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.storage_client.OneDriveStorageClient
import java.io.InputStream

data class OneDriveFileHandle(public val id: String) : FileHandle {
    public override fun toString(): String {
        return this.id
    }

    public override suspend fun getInputStream(context: StorageManagerActivity): InputStream {
        val client = context.storageClients().filterIsInstance<OneDriveStorageClient>().first()
        return client.getInputStream(this.id)
    }
}