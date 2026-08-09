package io.github.gustavlindberg99.photos.data_source

import android.media.MediaDataSource
import com.github.gustavlindberg99.androidsuspendutils.runBlocking
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.FileHandle

class FileHandleMediaDataSource(
    private val _context: StorageManagerActivity,
    private val _handle: FileHandle,
    private val _size: Long
) : MediaDataSource() {
    public override fun getSize(): Long = this._size
    public override fun close() {}

    public override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= this._size) return -1
        val end = (position + size - 1).coerceAtMost(this._size - 1)
        return runBlocking {
            try {
                // This leverages your existing getInputStream(context, range)
                this._handle.getInputStream(this._context, position..end).use { stream ->
                    stream.read(buffer, offset, size)
                }
            }
            catch (_: Exception) {
                -1
            }
        }
    }
}