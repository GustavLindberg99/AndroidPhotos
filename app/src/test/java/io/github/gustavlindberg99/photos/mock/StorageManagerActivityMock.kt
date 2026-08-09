package io.github.gustavlindberg99.photos.mock

import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import java.io.File

class StorageManagerActivityMock : StorageManagerActivity() {
    private val _client = StorageClientMock(this)

    public override fun getCacheDir(): File {
        return File(System.getProperty("java.io.tmpdir")!!)
    }

    public override suspend fun storageClients(): Set<StorageClientMock> {
        return setOf(this._client)
    }
}