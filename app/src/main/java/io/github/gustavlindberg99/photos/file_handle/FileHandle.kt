package io.github.gustavlindberg99.photos.file_handle

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
     *
     * @return The input stream for the file.
     *
     * @throws Exception If the input stream could not be retrieved.
     */
    public suspend fun getInputStream(context: StorageManagerActivity): InputStream
}