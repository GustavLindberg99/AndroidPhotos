package io.github.gustavlindberg99.photos

import androidx.core.net.toUri
import io.github.gustavlindberg99.photos.file_handle.GoogleDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.HandleList
import io.github.gustavlindberg99.photos.file_handle.OneDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.storage_client.OneDriveStorageClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.intArrayOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HandleListTest {
    @Test
    fun preferredHandleTest() {
        val localHandle = UriHandle("file://fake-filesystem/photo1.jpg".toUri())
        val googleDriveHandle = GoogleDriveFileHandle("id2")

        assertEquals(
            HandleList(
                localStorageHandle = localHandle,
                googleDriveHandle = googleDriveHandle
            ).preferredHandle(),
            localHandle
        )

        assertEquals(
            HandleList(googleDriveHandle = googleDriveHandle).preferredHandle(),
            googleDriveHandle
        )
    }

    @Test
    fun isDisjointTest() {
        val photo1Local = UriHandle("file://fake-filesystem/photo1.jpg".toUri())
        val photo2Local = UriHandle("file://fake-filesystem/photo2.jpg".toUri())
        val photo2OneDrive = OneDriveFileHandle("id2")
        val photo2GoogleDrive = GoogleDriveFileHandle("id2")
        val handles1 = HandleList(localStorageHandle = photo1Local)
        val handles2 = HandleList(localStorageHandle = photo2Local)
        val handles3 = HandleList(googleDriveHandle = photo2GoogleDrive)
        val handles4 = HandleList(
            localStorageHandle = photo1Local,
            googleDriveHandle = photo2GoogleDrive
        )
        val handles5 = HandleList(
            localStorageHandle = photo1Local,
            oneDriveHandle = photo2OneDrive
        )

        assertFalse(handles1.isDisjoint(handles1))
        assertTrue(handles1.isDisjoint(handles2))
        assertTrue(handles1.isDisjoint(handles3))
        assertFalse(handles1.isDisjoint(handles4))
        assertFalse(handles1.isDisjoint(handles5))

        assertTrue(handles2.isDisjoint(handles1))
        assertFalse(handles2.isDisjoint(handles2))
        assertTrue(handles2.isDisjoint(handles3))
        assertTrue(handles2.isDisjoint(handles4))
        assertTrue(handles2.isDisjoint(handles5))

        assertTrue(handles3.isDisjoint(handles1))
        assertTrue(handles3.isDisjoint(handles2))
        assertFalse(handles3.isDisjoint(handles3))
        assertFalse(handles3.isDisjoint(handles4))
        assertTrue(handles3.isDisjoint(handles5))

        assertFalse(handles4.isDisjoint(handles1))
        assertTrue(handles4.isDisjoint(handles2))
        assertFalse(handles4.isDisjoint(handles3))
        assertFalse(handles4.isDisjoint(handles4))
        assertFalse(handles4.isDisjoint(handles5))

        assertFalse(handles5.isDisjoint(handles1))
        assertTrue(handles5.isDisjoint(handles2))
        assertTrue(handles5.isDisjoint(handles3))
        assertFalse(handles5.isDisjoint(handles4))
        assertFalse(handles5.isDisjoint(handles5))
    }

    @Test
    fun makeDisjointTest() {
        val photo1Local = UriHandle("file://fake-filesystem/photo1.jpg".toUri())
        val photo2Local = UriHandle("file://fake-filesystem/photo2.jpg".toUri())
        val photo2OneDrive = OneDriveFileHandle("id2")
        val photo2GoogleDrive = GoogleDriveFileHandle("id2")
        val handles1 = HandleList(localStorageHandle = photo1Local)
        val handles2 = HandleList(localStorageHandle = photo2Local)
        val handles3 = HandleList(googleDriveHandle = photo2GoogleDrive)
        val handles4 = HandleList(
            localStorageHandle = photo1Local,
            googleDriveHandle = photo2GoogleDrive
        )
        val handles5 = HandleList(
            localStorageHandle = photo1Local,
            oneDriveHandle = photo2OneDrive
        )

        handles1.makeDisjoint(handles4)
        assertEquals(handles1, HandleList())
        handles2.makeDisjoint(handles4)
        assertEquals(handles2, HandleList(localStorageHandle = photo2Local))
        handles3.makeDisjoint(handles5)
        assertEquals(handles3, HandleList(googleDriveHandle = photo2GoogleDrive))
        handles5.makeDisjoint(handles4)
        assertEquals(handles5, HandleList(oneDriveHandle = photo2OneDrive))
    }

    @Test
    fun isBackedUpTest() {
        assertFalse(HandleList(localStorageHandle = UriHandle("file://fake-filesystem/photo1.jpg".toUri())).isBackedUp())
        assertTrue(HandleList(googleDriveHandle = GoogleDriveFileHandle("id2")).isBackedUp())
        assertTrue(
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo1.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id2")
            ).isBackedUp()
        )
    }

    @Test
    fun isLastHandleTest() {
        val photo1Local = UriHandle("file://fake-filesystem/photo1.jpg".toUri())
        val photo2GoogleDrive = GoogleDriveFileHandle("id2")
        val handles1 = HandleList(localStorageHandle = photo1Local)
        val handles2 = HandleList(
            localStorageHandle = photo1Local,
            googleDriveHandle = photo2GoogleDrive
        )

        assertTrue(handles1.isLastHandle(listOf(LocalStorageClient::class)))
        assertFalse(handles1.isLastHandle(listOf(GoogleDriveClient::class)))

        assertFalse(handles2.isLastHandle(listOf(LocalStorageClient::class)))
        assertTrue(
            handles2.isLastHandle(
                listOf(LocalStorageClient::class, GoogleDriveClient::class)
            )
        )
        assertTrue(
            handles2.isLastHandle(
                listOf(
                    LocalStorageClient::class,
                    GoogleDriveClient::class,
                    OneDriveStorageClient::class
                )
            )
        )
        assertFalse(
            handles2.isLastHandle(
                listOf(LocalStorageClient::class, OneDriveStorageClient::class)
            )
        )
    }

    @Test
    fun mergeHandlesTest() {
        val handles = HandleList(googleDriveHandle = GoogleDriveFileHandle("id2"))
        handles.mergeHandlesWith(HandleList(localStorageHandle = UriHandle("file://fake-filesystem/photo1.jpg".toUri())))
        assertEquals(
            handles, HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo1.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id2")
            )
        )
    }

    @Test
    fun removeExtraHandlesTest() {
        val handles = HandleList(
            localStorageHandle = UriHandle("file://fake-filesystem/photo12.jpg".toUri()),
            googleDriveHandle = GoogleDriveFileHandle("id12")
        )
        handles.removeExtraHandles(HandleList(localStorageHandle = UriHandle("file://fake-filesystem/photo13.jpg".toUri())))
        assertEquals(
            handles, HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo12.jpg".toUri())
            )
        )
    }

    @Test
    fun jsonTest() {
        val handles = HandleList(
            localStorageHandle = UriHandle("file://fake-filesystem/photo12.jpg".toUri()),
            googleDriveHandle = GoogleDriveFileHandle("id12")
        )
        val json = handles.toJson()
        val parsedHandles = HandleList.fromJson(json)
        assertEquals(handles, parsedHandles)
    }
}