package io.github.gustavlindberg99.photos.file_handle

import android.net.Uri
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.IOException
import java.io.InputStream

data class UriHandle(public val uri: Uri) : FileHandle {
    public override fun toString(): String {
        return this.uri.toString()
    }

    public override suspend fun getInputStream(context: StorageManagerActivity): InputStream {
        if (this.uri.scheme == "http" || this.uri.scheme == "https") {
            return HttpClient(Android) { expectSuccess = true }
                .get(this.uri.toString())
                .bodyAsChannel()
                .toInputStream()
        }
        else {
            return context.contentResolver.openInputStream(uri)
                ?: throw IOException("No stream available")
        }
    }
}