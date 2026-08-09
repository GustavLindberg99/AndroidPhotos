package io.github.gustavlindberg99.photos.mock

import android.content.Context
import androidx.media3.datasource.DataSource
import io.github.gustavlindberg99.photos.file_handle.FileHandle
import io.github.gustavlindberg99.photos.photo.Media
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import kotlinx.coroutines.flow.Flow

class DummyStorageClient : StorageClient {
    public override val name = "Test"

    public override fun equals(other: Any?): Boolean {
        return other is DummyStorageClient
    }

    public override fun hashCode(): Int {
        return DummyStorageClient::class::qualifiedName.hashCode()
    }

    public override fun getAllPhotos(): Flow<Media> = throw NotImplementedError()
    public override suspend fun allPhotoHandles(): Set<FileHandle> = throw NotImplementedError()
    public override suspend fun save(photo: Media) = throw NotImplementedError()
    public override suspend fun overwrite(oldPhoto: Media, newBytes: ByteArray): Media =
        throw NotImplementedError()

    public override suspend fun delete(photo: Media) = throw NotImplementedError()
    public override suspend fun dataFactory(context: Context): DataSource.Factory =
        throw NotImplementedError()
}