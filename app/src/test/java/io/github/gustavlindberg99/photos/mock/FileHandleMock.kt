package io.github.gustavlindberg99.photos.mock

import android.net.Uri
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.FileHandle
import java.io.InputStream

data class FileHandleMock(public val name: String) : FileHandle {
    public override suspend fun getInputStream(
        context: StorageManagerActivity,
        range: LongRange?
    ): InputStream {
        return this.javaClass.classLoader!!.getResourceAsStream(this.name)
    }

    public override suspend fun getSize(context: StorageManagerActivity): Long {
        return this.getInputStream(context).use { it.readBytes() }.size.toLong()
    }

    public override suspend fun getPlaybackUri(context: StorageManagerActivity): Uri {
        return Uri.EMPTY
    }
}