package io.github.gustavlindberg99.photos.file_handle

import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import java.io.InputStream

data class GoogleDriveFileHandle(public val id: String) : FileHandle {
    public override fun toString(): String {
        return this.id
    }

    public override suspend fun getInputStream(context: StorageManagerActivity): InputStream {
        val client = context.storageClients().filterIsInstance<GoogleDriveClient>().first()
        return client.getInputStream(this.id)
    }
}