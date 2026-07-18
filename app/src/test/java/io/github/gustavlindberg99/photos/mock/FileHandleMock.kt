package io.github.gustavlindberg99.photos.mock

import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.FileHandle
import java.io.InputStream

data class FileHandleMock(public val name: String) : FileHandle {
    // The mocked version assumes each photo's byte contents is the file name by default.
    // In reality this wouldn't be a valid photo, but it's not a problem since the tests never assume the bytes are valid.
    public var contents = name.toByteArray()

    public override suspend fun getInputStream(context: StorageManagerActivity): InputStream {
        return this.contents.inputStream()
    }
}