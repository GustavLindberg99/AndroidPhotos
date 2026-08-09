package io.github.gustavlindberg99.photos.file_handle

import android.net.Uri
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.storage_client.OneDriveStorageClient
import java.io.InputStream

data class OneDriveFileHandle(public val id: String) : FileHandle {
    public override fun toString(): String {
        return this.id
    }

    public override suspend fun getInputStream(
        context: StorageManagerActivity,
        range: LongRange?
    ): InputStream {
        val client = context.storageClients().filterIsInstance<OneDriveStorageClient>().first()
        return client.getInputStream(this.id, range)
    }

    public override suspend fun getSize(context: StorageManagerActivity): Long {
        val client = context.storageClients().filterIsInstance<OneDriveStorageClient>().first()
        return client.getSize(this.id)
    }

    public override suspend fun getPlaybackUri(context: StorageManagerActivity): Uri {
        val client = context.storageClients().filterIsInstance<OneDriveStorageClient>().first()
        return client.getPlaybackUri(this.id)
    }
}