package io.github.gustavlindberg99.photos.mock

import android.media.MediaDataSource

/**
 * A [MediaDataSource] that reads from a byte array. This would work in the app as well, but loading a video's bytes into memory could easily lead to out of memory errors, so it should only be used for unit tests.
 */
class ByteArrayDataSource(private val _bytes: ByteArray) : MediaDataSource() {
    public override fun getSize(): Long {
        return this._bytes.size.toLong()
    }

    public override fun readAt(position: Long, buf: ByteArray, offset: Int, size: Int): Int {
        if (position >= this._bytes.size) return -1
        val remaining = this._bytes.size - position
        val toRead = if (size > remaining) remaining.toInt() else size
        System.arraycopy(this._bytes, position.toInt(), buf, offset, toRead)
        return toRead
    }

    override fun close() {}
}