package io.github.gustavlindberg99.photos

import androidx.core.net.toUri
import io.github.gustavlindberg99.photos.file_handle.GoogleDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric is needed to be able to use Android classes in unit tests.
 * Unit tests run locally on the computer and don't have access to the Android SDK by default, Robolectric gives them that access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoTest : PhotoTestBase() {
    @Test
    fun photoEquality() {
        assertEquals(photo1, photo2)
        assertNotEquals(photo1, photo3)
        assertNotEquals(photo1, photo4)
        assertNotEquals(photo1, photo5)
        assertNotEquals(photo2, photo3)
        assertNotEquals(photo2, photo4)
        assertNotEquals(photo2, photo5)
        assertNotEquals(photo3, photo4)
        assertNotEquals(photo3, photo5)
        assertNotEquals(photo4, photo5)
    }

    @Test
    fun photoCompare() {
        assertTrue(photo1 < photo4) // Because of time zones
        assertTrue(photo1 > photo5)
        assertTrue(photo4 > photo5)
    }

    @Test
    fun mergePhotos() {
        photo2.mergeHandlesWith(photo1)
        assertEquals(
            photo2.handles, mapOf(
                LocalStorageClient::class to UriHandle("file://fake-filesystem/photo1.jpg".toUri()),
                GoogleDriveClient::class to GoogleDriveFileHandle("id2")
            )
        )
    }
}