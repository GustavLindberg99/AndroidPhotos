package io.github.gustavlindberg99.photos.mock

import android.content.Context
import androidx.core.net.toUri
import com.github.gustavlindberg99.androidsuspendutils.flow
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.PhotoManager
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import kotlinx.coroutines.delay
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration.Companion.milliseconds

class StorageClientMock(private val _context: Context) : StorageClient {
    public class TestException : Exception()

    public val photos = mutableMapOf<FileHandleMock, Photo>()
    public var networkDown = false

    public override val name = "Test"

    public override fun equals(other: Any?): Boolean {
        return other is StorageClientMock
    }

    public override fun hashCode(): Int {
        return StorageClientMock::class::qualifiedName.hashCode()
    }

    public override fun getAllPhotos() = flow {
        for (photo in this.photos.values) {
            it.emit(photo)
        }
    }

    public override suspend fun allPhotoHandles(): Set<FileHandleMock> {
        return this.photos.keys
    }

    public override suspend fun save(photo: Photo) {
        delay(100.milliseconds)    // Simulate network latencies

        if (this.networkDown) {
            throw TestException()
        }

        val handle = FileHandleMock(photo.fileName)
        this.photos[handle] = photo
        photo.handles[this::class] = handle
        PhotoManager.update(this._context, photo)
    }

    public override suspend fun overwrite(oldPhoto: Photo, newBytes: ByteArray): Photo {
        delay(100.milliseconds)    // Simulate network latencies

        if (this.networkDown) {
            throw TestException()
        }

        val handle = this.photos.filterValues { it == oldPhoto }.keys.first()
        handle.contents = newBytes
        val sha1 = newBytes.toByteString().sha1().hex()
        val newPhoto = Photo(
            oldPhoto.fileName,
            oldPhoto.mimeType,
            oldPhoto.width,
            oldPhoto.height,
            oldPhoto.location,
            sha1,
            null,
            null,
            "file://fake-filesystem/thumbnails/photo.jpg".toUri(),
            mutableMapOf(this::class to handle)
        )
        PhotoManager.update(this._context, oldPhoto)
        val updatedPhoto = PhotoManager.update(this._context, newPhoto)
        this.photos[handle] = updatedPhoto
        return updatedPhoto
    }

    public override suspend fun delete(photo: Photo) {
        delay(100.milliseconds)    // Simulate network latencies

        if (this.networkDown) {
            throw TestException()
        }

        this.photos[FileHandleMock(photo.fileName)] = photo
        photo.handles.remove(this::class)
        PhotoManager.update(this._context, photo)
    }
}