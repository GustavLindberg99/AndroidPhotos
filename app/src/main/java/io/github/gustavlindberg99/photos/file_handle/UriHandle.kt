package io.github.gustavlindberg99.photos.file_handle

import android.net.Uri
import com.github.gustavlindberg99.androidsuspendutils.withContext
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.io.InputStream

data class UriHandle(public val uri: Uri) : FileHandle {
    public override fun toString(): String {
        return this.uri.toString()
    }

    public override suspend fun getInputStream(
        context: StorageManagerActivity
    ): InputStream = withContext(Dispatchers.IO) {
        // Run on IO thread to avoid strange crashes when changing between light and dark mode
        if (this@UriHandle.uri.scheme == "http" || this@UriHandle.uri.scheme == "https") {
            return@withContext HttpClient(Android) { expectSuccess = true }
                .get(this@UriHandle.uri.toString())
                .bodyAsChannel()
                .toInputStream()
        }
        else {
            return@withContext context.contentResolver.openInputStream(uri)
                ?: throw IOException("No stream available")
        }
    }
}