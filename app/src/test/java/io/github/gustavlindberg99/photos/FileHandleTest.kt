package io.github.gustavlindberg99.photos

import androidx.core.net.toUri
import io.github.gustavlindberg99.photos.file_handle.GoogleDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.OneDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.PCloudFileHandle
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.intArrayOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileHandleTest {
    @Test
    fun serializationTest() {
        val uriHandle = UriHandle("file://fake-filesystem/thumbnails/photo1.jpg".toUri())
        val googleDriveFileHandle = GoogleDriveFileHandle("id123")
        val pCloudFileHandle = PCloudFileHandle(456)
        val oneDriveFileHandle = OneDriveFileHandle("id789")

        val serializedUriHandle = uriHandle.toString()
        val serializedGoogleDriveFileHandle = googleDriveFileHandle.toString()
        val serializedPCloudFileHandle = pCloudFileHandle.toString()
        val serializedOneDriveFileHandle = oneDriveFileHandle.toString()

        assertEquals(UriHandle(serializedUriHandle.toUri()), uriHandle)
        assertEquals(GoogleDriveFileHandle(serializedGoogleDriveFileHandle), googleDriveFileHandle)
        assertEquals(PCloudFileHandle(serializedPCloudFileHandle.toLong()), pCloudFileHandle)
        assertEquals(OneDriveFileHandle(serializedOneDriveFileHandle), oneDriveFileHandle)
    }
}