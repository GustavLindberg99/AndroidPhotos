package io.github.gustavlindberg99.photos.data_source

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.github.gustavlindberg99.androidsuspendutils.runBlocking
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.InputStream

@UnstableApi
class HttpDataSource(private val _client: HttpClient) : DataSource {
    private var _dataSpec: DataSpec? = null
    private var _inputStream: InputStream? = null
    private var _bytesRemaining = 0L

    public override fun addTransferListener(transferListener: TransferListener) {}

    public override fun open(dataSpec: DataSpec): Long = runBlocking {
        this._dataSpec = dataSpec
        val response = this._client.get(dataSpec.uri.toString()) {
            val end =
                if (dataSpec.length != -1L) dataSpec.position + dataSpec.length - 1 else ""
            header("Range", "bytes=${dataSpec.position}-$end")
        }
        val contentLength = response.contentLength()
        this._bytesRemaining =
            if (dataSpec.length != -1L) dataSpec.length
            else contentLength ?: -1L
        this._inputStream = response.bodyAsChannel().toInputStream()
        return@runBlocking this._bytesRemaining
    }

    public override fun getUri(): Uri? {
        return this._dataSpec?.uri
    }

    public override fun close() {
        try {
            this._inputStream?.close()
        }
        catch (_: Exception) {
            this._inputStream = null
        }
    }

    public override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = this._inputStream ?: return -1
        val bytesRead = stream.read(buffer, offset, length)
        if (bytesRead == -1) {
            return -1
        }
        if (this._bytesRemaining != -1L) {
            this._bytesRemaining -= bytesRead
        }
        return bytesRead
    }
}