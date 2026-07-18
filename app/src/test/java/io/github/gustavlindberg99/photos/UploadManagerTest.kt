package io.github.gustavlindberg99.photos

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.github.gustavlindberg99.androidsuspendutils.launch
import io.github.gustavlindberg99.photos.mock.FileHandleMock
import io.github.gustavlindberg99.photos.mock.StorageClientMock
import io.github.gustavlindberg99.photos.photo.PhotoManager
import io.github.gustavlindberg99.photos.storage_client_utils.UploadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.intArrayOf
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UploadManagerTest : PhotoTestBase() {
    @Before
    fun reset() {
        PhotoManager.reset()
        UploadManager.reset()
    }

    @Test
    fun uploadTest() = runBlocking {
        val lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.currentState = Lifecycle.State.RESUMED
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = StorageClientMock(context)

        val photos = listOf(
            photo1, photo2, photo3, photo4, photo5, photo6, photo7,
            photo8, photo9, photo10, photo11, photo12, photo13
        )

        // Use strings representing file names instead of photo objects to distinguish between duplicate photos
        val uploadedPhotos = mutableSetOf<String>()
        val calledListeners = mutableMapOf<String, UploadManager.UploadState>()
        UploadManager.setStateChangedListener { photo, _, state ->
            calledListeners[photo.fileName] = state
        }

        // Queue all photos to be added
        for (photo in photos) {
            lifecycleOwner.lifecycleScope.launch {
                UploadManager.save(lifecycleOwner, client, photo)
                uploadedPhotos.add(photo.fileName)
            }
        }

        // Assert that the pending and current uploads are correct
        assertEquals(
            UploadManager.currentUploads(client),
            setOf(photo1, photo3, photo4, photo5, photo6, photo7, photo8, photo9, photo10, photo11)
        )
        assertEquals(UploadManager.queuedUploads(client), setOf(photo12))

        // Assert that the correct listeners have been called
        assertEquals(
            calledListeners, mapOf(
                photo1.fileName to UploadManager.UploadState.UPLOADING,
                // photo2 is a duplicate and should be missing because the listener should only be called once per photo
                photo3.fileName to UploadManager.UploadState.UPLOADING,
                photo4.fileName to UploadManager.UploadState.UPLOADING,
                photo5.fileName to UploadManager.UploadState.UPLOADING,
                photo6.fileName to UploadManager.UploadState.UPLOADING,
                photo7.fileName to UploadManager.UploadState.UPLOADING,
                photo8.fileName to UploadManager.UploadState.UPLOADING,
                photo9.fileName to UploadManager.UploadState.UPLOADING,
                photo10.fileName to UploadManager.UploadState.UPLOADING,
                photo11.fileName to UploadManager.UploadState.UPLOADING,
                photo12.fileName to UploadManager.UploadState.QUEUED
                // photo13 is a duplicate and should be missing because the listener should only be called once per photo
            )
        )

        // Nothing should have returned yet
        assertTrue(client.photos.isEmpty())
        assertTrue(uploadedPhotos.isEmpty())

        // Wait long enough for the first batch to finish uploading (the mocked storage client sets the "network latency" to 100ms)
        delay(150.milliseconds)
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)

        // Assert that the correct photos have been uploaded
        assertEquals(client.photos[FileHandleMock(photo1.fileName)], photo1)
        assertFalse(FileHandleMock(photo2.fileName) in client.photos)   // Duplicate of photo1
        assertEquals(client.photos[FileHandleMock(photo3.fileName)], photo3)
        assertEquals(client.photos[FileHandleMock(photo4.fileName)], photo4)
        assertEquals(client.photos[FileHandleMock(photo5.fileName)], photo5)
        assertEquals(client.photos[FileHandleMock(photo6.fileName)], photo6)
        assertEquals(client.photos[FileHandleMock(photo7.fileName)], photo7)
        assertEquals(client.photos[FileHandleMock(photo8.fileName)], photo8)
        assertEquals(client.photos[FileHandleMock(photo9.fileName)], photo9)
        assertEquals(client.photos[FileHandleMock(photo10.fileName)], photo10)
        assertEquals(client.photos[FileHandleMock(photo11.fileName)], photo11)
        assertFalse(FileHandleMock(photo12.fileName) in client.photos)  // Queued, not finished yet
        assertFalse(FileHandleMock(photo13.fileName) in client.photos)  // Duplicate of photo12

        // Assert that the correct listeners have been called
        assertEquals(
            calledListeners, mapOf(
                photo1.fileName to UploadManager.UploadState.FINISHED,
                // photo2 is a duplicate and should be missing because the listener should only be called once per photo
                photo3.fileName to UploadManager.UploadState.FINISHED,
                photo4.fileName to UploadManager.UploadState.FINISHED,
                photo5.fileName to UploadManager.UploadState.FINISHED,
                photo6.fileName to UploadManager.UploadState.FINISHED,
                photo7.fileName to UploadManager.UploadState.FINISHED,
                photo8.fileName to UploadManager.UploadState.FINISHED,
                photo9.fileName to UploadManager.UploadState.FINISHED,
                photo10.fileName to UploadManager.UploadState.FINISHED,
                photo11.fileName to UploadManager.UploadState.FINISHED,
                photo12.fileName to UploadManager.UploadState.UPLOADING
                // photo13 is a duplicate and should be missing because the listener should only be called once per photo
            )
        )

        // Assert that the correct coroutines have returned
        assertEquals(
            uploadedPhotos,
            setOf(
                photo1.fileName, photo2.fileName, photo3.fileName, photo4.fileName,
                photo5.fileName, photo6.fileName, photo7.fileName, photo8.fileName,
                photo9.fileName, photo10.fileName, photo11.fileName
            )
        )

        // Assert that the pending and current uploads are correct
        assertEquals(UploadManager.currentUploads(client), setOf(photo12))
        assertTrue(UploadManager.queuedUploads(client).isEmpty())

        // Wait long enough for the second batch to finish uploading
        delay(150.milliseconds)
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)

        // Assert that the correct photos have been uploaded
        assertEquals(client.photos[FileHandleMock(photo1.fileName)], photo1)
        assertFalse(FileHandleMock(photo2.fileName) in client.photos)   // Duplicate of photo1
        assertEquals(client.photos[FileHandleMock(photo3.fileName)], photo3)
        assertEquals(client.photos[FileHandleMock(photo4.fileName)], photo4)
        assertEquals(client.photos[FileHandleMock(photo5.fileName)], photo5)
        assertEquals(client.photos[FileHandleMock(photo6.fileName)], photo6)
        assertEquals(client.photos[FileHandleMock(photo7.fileName)], photo7)
        assertEquals(client.photos[FileHandleMock(photo8.fileName)], photo8)
        assertEquals(client.photos[FileHandleMock(photo9.fileName)], photo9)
        assertEquals(client.photos[FileHandleMock(photo10.fileName)], photo10)
        assertEquals(client.photos[FileHandleMock(photo11.fileName)], photo11)
        assertEquals(client.photos[FileHandleMock(photo12.fileName)], photo12)
        assertFalse(FileHandleMock(photo13.fileName) in client.photos)   // Duplicate of photo12

        // Assert that the correct listeners have been called
        assertEquals(
            calledListeners, mapOf(
                photo1.fileName to UploadManager.UploadState.FINISHED,
                // photo2 is a duplicate and should be missing because the listener should only be called once per photo
                photo3.fileName to UploadManager.UploadState.FINISHED,
                photo4.fileName to UploadManager.UploadState.FINISHED,
                photo5.fileName to UploadManager.UploadState.FINISHED,
                photo6.fileName to UploadManager.UploadState.FINISHED,
                photo7.fileName to UploadManager.UploadState.FINISHED,
                photo8.fileName to UploadManager.UploadState.FINISHED,
                photo9.fileName to UploadManager.UploadState.FINISHED,
                photo10.fileName to UploadManager.UploadState.FINISHED,
                photo11.fileName to UploadManager.UploadState.FINISHED,
                photo12.fileName to UploadManager.UploadState.FINISHED
                // photo13 is a duplicate and should be missing because the listener should only be called once per photo
            )
        )

        // Assert that the correct coroutines have returned
        assertEquals(
            uploadedPhotos,
            setOf(
                photo1.fileName, photo2.fileName, photo3.fileName, photo4.fileName,
                photo5.fileName, photo6.fileName, photo7.fileName, photo8.fileName,
                photo9.fileName, photo10.fileName, photo11.fileName, photo12.fileName,
                photo13.fileName
            )
        )

        // Assert that the pending and current uploads are correct
        assertTrue(UploadManager.currentUploads(client).isEmpty())
        assertTrue(UploadManager.queuedUploads(client).isEmpty())
    }

    @Test
    fun uploadFailedTest() = runBlocking {
        val lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.currentState = Lifecycle.State.RESUMED
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = StorageClientMock(context)
        client.networkDown = true

        val photos = listOf(
            photo1, photo2, photo3, photo4, photo5, photo6,
            photo7, photo8, photo9, photo10, photo11, photo12
        )
        val failedUploads = mutableSetOf<String>()

        // Queue all photos to be added
        for (photo in photos) {
            lifecycleOwner.lifecycleScope.launch {
                try {
                    UploadManager.save(lifecycleOwner, client, photo)
                }
                catch (_: StorageClientMock.TestException) {
                    failedUploads.add(photo.fileName)
                }
            }
        }

        // Nothing should have returned yet
        assertTrue(failedUploads.isEmpty())

        // Assert that the pending and current uploads are correct
        assertEquals(
            UploadManager.currentUploads(client),
            setOf(photo1, photo3, photo4, photo5, photo6, photo7, photo8, photo9, photo10, photo11)
        )
        assertEquals(UploadManager.queuedUploads(client), setOf(photo12))

        // Wait long enough for the first batch to finish uploading (the mocked storage client sets the "network latency" to 100ms)
        delay(150.milliseconds)
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)

        // All photos should have failed except photo12 which is queued
        assertEquals(
            failedUploads,
            setOf(
                photo1.fileName, photo2.fileName, photo3.fileName, photo4.fileName,
                photo5.fileName, photo6.fileName, photo7.fileName, photo8.fileName,
                photo9.fileName, photo10.fileName, photo11.fileName
            )
        )

        // Assert that the pending and current uploads are correct
        assertEquals(UploadManager.currentUploads(client), setOf(photo12))
        assertTrue(UploadManager.queuedUploads(client).isEmpty())

        // Wait long enough for the second batch to finish uploading
        delay(150.milliseconds)
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)

        // All uploads should have failed
        assertEquals(
            failedUploads,
            setOf(
                photo1.fileName, photo2.fileName, photo3.fileName, photo4.fileName,
                photo5.fileName, photo6.fileName, photo7.fileName, photo8.fileName,
                photo9.fileName, photo10.fileName, photo11.fileName, photo12.fileName
            )
        )

        // Assert that the pending and current uploads are correct
        assertTrue(UploadManager.currentUploads(client).isEmpty())
        assertTrue(UploadManager.queuedUploads(client).isEmpty())
    }
}