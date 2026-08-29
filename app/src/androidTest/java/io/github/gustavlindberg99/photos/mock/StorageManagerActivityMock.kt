package io.github.gustavlindberg99.photos.mock

import android.content.ContentResolver
import android.content.Context
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import java.io.File

class StorageManagerActivityMock(private val _context: Context) : StorageManagerActivity() {
    public override fun getCacheDir(): File {
        // Return a temporary directory for the test environment
        return File(System.getProperty("java.io.tmpdir")!!)
    }

    public override fun getContentResolver(): ContentResolver? {
        return this._context.contentResolver
    }

    public override suspend fun storageClients(): Set<StorageClient> {
        return emptySet()
    }
}