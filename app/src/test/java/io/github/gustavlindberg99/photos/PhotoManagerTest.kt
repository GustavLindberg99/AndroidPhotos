package io.github.gustavlindberg99.photos

import android.content.Context
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.PhotoManager
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoManagerTest : PhotoTestBase() {
    @Before
    fun resetPhotoManager() {
        PhotoManager.reset()
    }

    @Test
    fun photoAddedListenerTest() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycleOwner = TestLifecycleOwner()
        val addedPhotos = mutableSetOf<Photo>()
        PhotoManager.setPhotoAddedListener(context, lifecycleOwner, addedPhotos::add)
        PhotoManager.update(context, photo1)
        PhotoManager.update(context, photo2)
        PhotoManager.update(context, photo3)
        lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        assertEquals(addedPhotos, setOf(photo1, photo2, photo3))
    }

    @Test
    fun photoRemovedListenerTest() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycleOwner = TestLifecycleOwner()
        val removedPhotos = mutableSetOf<Photo>()
        PhotoManager.setPhotoRemovedListener(lifecycleOwner, removedPhotos::add)

        PhotoManager.update(context, photo1)
        lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        assertEquals(removedPhotos, emptySet<Photo>())

        photo1.handles.clear()
        PhotoManager.update(context, photo1)
        lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        assertEquals(removedPhotos, setOf(photo1))
    }

    @Test
    fun removeClientTest() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycleOwner = TestLifecycleOwner()
        val removedPhotos = mutableSetOf<Photo>()
        PhotoManager.setPhotoRemovedListener(lifecycleOwner, removedPhotos::add)
        PhotoManager.update(context, photo1)
        PhotoManager.update(context, photo2)
        PhotoManager.update(context, photo3)
        PhotoManager.update(context, photo4)
        PhotoManager.update(context, photo5)

        PhotoManager.removeClient(context, GoogleDriveClient::class)
        lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        // photo3 is the only photo that only had a Google Drive client (photo2 is merged into photo1 since they have the same SHA1)
        assertEquals(removedPhotos, setOf(photo3))

        // The Google Drive client should have been removed from photo4 and photo5
        assertEquals(photo1.handles.keys, setOf(LocalStorageClient::class))
        assertEquals(photo4.handles.keys, setOf(LocalStorageClient::class))
        assertEquals(photo5.handles.keys, setOf(LocalStorageClient::class))
    }
}