package io.github.gustavlindberg99.photos.data_source

import android.media.MediaDataSource
import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking

/**
 * Class allowing a `VideoMetadataParser` to efficiently parse a video over HTTP. Any `VideoMetadataParser` object constructed with this can't be used on the main thread.
 *
 * @param _client   The HTTP client to use.
 * @param _uri      The URI of the video.
 * @param _size     The size of the video.
 */
class HttpMediaDataSource(
    private val _client: HttpClient,
    private val _uri: Uri,
    private val _size: Long
) : MediaDataSource() {
    public override fun getSize(): Long {
        return this._size
    }

    public override fun readAt(position: Long, buf: ByteArray, offset: Int, size: Int): Int {
        if (position >= this._size) return -1
        val end = (position + size - 1).coerceAtMost(this._size - 1)

        return runBlocking {
            try {
                val response = _client.get(_uri.toString()) {
                    header("Range", "bytes=$position-$end")
                }
                val bytes = response.body<ByteArray>()
                System.arraycopy(bytes, 0, buf, offset, bytes.size)
                bytes.size
            }
            catch (_: Exception) {
                -1
            }
        }
    }

    public override fun close() {}
}