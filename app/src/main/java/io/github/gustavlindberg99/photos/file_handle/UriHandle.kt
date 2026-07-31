package io.github.gustavlindberg99.photos.file_handle

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import kotlin.collections.contains

data class UriHandle(private val _uri: Uri) : FileHandle {
    public override fun toString(): String {
        return this._uri.toString()
    }

    public override suspend fun getInputStream(
        context: StorageManagerActivity
    ): InputStream = withContext(Dispatchers.IO) {
        // Run on IO thread to avoid strange crashes when changing between light and dark mode
        if (this@UriHandle._uri.scheme in setOf("http", "https")) {
            return@withContext HttpClient(Android) { expectSuccess = true }
                .get(this@UriHandle._uri.toString())
                .bodyAsChannel()
                .toInputStream()
        }
        else {
            return@withContext context.contentResolver.openInputStream(_uri)
                ?: throw IOException("No stream available")
        }
    }

    /**
     * Gets the URI of the file, requesting permissions if necessary.
     *
     * @return The URI of the file.
     */
    public fun uri(): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && this@UriHandle._uri.scheme !in setOf("http", "https")
        ) {
            return MediaStore.setRequireOriginal(this._uri)
        }
        else {
            return this._uri
        }
    }
}