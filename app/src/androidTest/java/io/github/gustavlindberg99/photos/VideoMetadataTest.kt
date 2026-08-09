package io.github.gustavlindberg99.photos

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gustavlindberg99.photos.mock.ByteArrayDataSource
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.metadata_parser.VideoMetadataParser
import io.github.gustavlindberg99.photos.mock.DummyStorageClient
import io.github.gustavlindberg99.photos.mock.StorageManagerActivityMock
import io.github.gustavlindberg99.photos.photo.Video
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Must be an instrumented test because robolectric's implementation of MediaMetadataRetriever is just an empty placeholder.
 */
class VideoMetadataTest {
    @Test
    fun videoMetadataParserTest() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val resId = context.resources.getIdentifier("blake", "raw", context.packageName)
        val fd = context.resources.openRawResourceFd(resId)
        val metadataParser = VideoMetadataParser(fd.fileDescriptor, fd.startOffset, fd.length)
        assertEquals(metadataParser.width(), 1920)
        assertEquals(metadataParser.height(), 1080)
        assertEquals(metadataParser.rotation(), 0)
        assertEquals(metadataParser.location(), GeoPoint(48.9798, 1.9955))
        assertEquals(metadataParser.dateTime(), "20251222T084954.000Z")
        assertEquals(metadataParser.duration(), 36873L)
    }

    @Test
    fun rotateVideoTest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val resId = context.resources.getIdentifier("blake", "raw", context.packageName)
        val fd = context.resources.openRawResourceFd(resId)
        val metadataParser = VideoMetadataParser(fd.fileDescriptor, fd.startOffset, fd.length)
        val handle =
            UriHandle(Uri.parse("android.resource://${context.packageName}/raw/blake"))
        lateinit var activity: StorageManagerActivityMock
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = StorageManagerActivityMock(context)
        }
        val video = Video(
            "blake.mp4",
            "video/mp4",
            metadataParser.width()!!,
            metadataParser.height()!!,
            metadataParser.duration()!!,
            metadataParser.location(),
            "e54dc1462fa8324704a47616b29ec4520b722b43",
            metadataParser.dateTime(),
            Uri.EMPTY,
            mutableMapOf(DummyStorageClient::class to handle)
        )

        val rotation = 90
        val newBytes = video.edit(activity, rotation = rotation)
        val newMetadata = VideoMetadataParser(ByteArrayDataSource(newBytes))

        assertEquals(newMetadata.width(), metadataParser.height())
        assertEquals(newMetadata.height(), metadataParser.width())
        // Should always return 0, since the library takes care of rotation internally for videos
        assertEquals(newMetadata.rotation(), 0)
        assertEquals(newMetadata.location(), metadataParser.location())
        assertEquals(newMetadata.dateTime(), metadataParser.dateTime())
        assertEquals(newMetadata.duration(), metadataParser.duration())
    }

    @Test
    fun changeVideoLocationTest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val resId = context.resources.getIdentifier("blake", "raw", context.packageName)
        val fd = context.resources.openRawResourceFd(resId)
        val metadataParser = VideoMetadataParser(fd.fileDescriptor, fd.startOffset, fd.length)
        val handle =
            UriHandle(Uri.parse("android.resource://${context.packageName}/raw/blake"))
        lateinit var activity: StorageManagerActivityMock
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = StorageManagerActivityMock(context)
        }
        val video = Video(
            "blake.mp4",
            "video/mp4",
            metadataParser.width()!!,
            metadataParser.height()!!,
            metadataParser.duration()!!,
            metadataParser.location(),
            "e54dc1462fa8324704a47616b29ec4520b722b43",
            metadataParser.dateTime(),
            Uri.EMPTY,
            mutableMapOf(DummyStorageClient::class to handle)
        )

        val newLocation = GeoPoint(39.74, -104.99)
        val newBytes = video.edit(activity, location = newLocation)
        val newMetadata = VideoMetadataParser(ByteArrayDataSource(newBytes))

        assertEquals(newMetadata.width(), metadataParser.width())
        assertEquals(newMetadata.height(), metadataParser.height())
        assertEquals(newMetadata.rotation(), metadataParser.rotation())
        assertEquals(newMetadata.location(), newLocation)
        assertEquals(newMetadata.dateTime(), metadataParser.dateTime())
        assertEquals(newMetadata.duration(), metadataParser.duration())
    }
}