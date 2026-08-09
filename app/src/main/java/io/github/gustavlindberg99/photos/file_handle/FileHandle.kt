package io.github.gustavlindberg99.photos.file_handle

import android.net.Uri
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import java.io.InputStream

interface FileHandle {
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int
    public override fun toString(): String

    /**
     * Gets the input stream for the file. Must be closed after use.
     *
     * @param context   The context to use.
     * @param range     The minimum range of bytes to read (implementations can read more bytes than requested). If null, the entire file is read.
     *
     * @return The input stream for the file.
     *
     * @throws Exception If the input stream could not be retrieved.
     */
    public suspend fun getInputStream(
        context: StorageManagerActivity,
        range: LongRange? = null
    ): InputStream

    /**
     * Gets the size of the file in bytes.
     *
     * @param context   The context to use.
     *
     * @return The size of the file in bytes.
     */
    public suspend fun getSize(context: StorageManagerActivity): Long

    /**
     * Gets the URI for the file to use to play videos.
     *
     * @return The URI for the file.
     */
    public suspend fun getPlaybackUri(context: StorageManagerActivity): Uri
}