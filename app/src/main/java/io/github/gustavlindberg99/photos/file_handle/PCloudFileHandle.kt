package io.github.gustavlindberg99.photos.file_handle

import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.storage_client.PCloudClient
import java.io.InputStream

data class PCloudFileHandle(public val id: Long) : FileHandle {
    public override fun toString(): String {
        return this.id.toString()
    }

    public override suspend fun getInputStream(context: StorageManagerActivity): InputStream {
        val client = context.storageClients().filterIsInstance<PCloudClient>().first()
        return client.getInputStream(this.id)
    }
}