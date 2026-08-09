package io.github.gustavlindberg99.photos

import android.net.Uri
import io.github.gustavlindberg99.photos.metadata_parser.PhotoMetadataParser
import io.github.gustavlindberg99.photos.mock.StorageManagerActivityMock
import io.github.gustavlindberg99.photos.mock.FileHandleMock
import io.github.gustavlindberg99.photos.mock.StorageClientMock
import io.github.gustavlindberg99.photos.photo.Photo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.intArrayOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoMetadataTest {
    @Test
    fun photoMetadataParserTest() {
        val inputStream = this.javaClass.classLoader!!.getResourceAsStream("blake.jpg")
        val bytes = inputStream!!.use { it.readBytes() }
        val metadataParser = PhotoMetadataParser(bytes)
        assertEquals(metadataParser.width(), 3120)
        assertEquals(metadataParser.height(), 4160)
        assertEquals(metadataParser.rotation(), 0)
        assertEquals(metadataParser.location(), GeoPoint(48.9796501, 1.9955147))
        assertEquals(metadataParser.dateTime(), "2021:07:06 13:53:29")
        assertEquals(metadataParser.timezone(), "+02:00")
    }

    @Test
    fun rotatePhotoTest() = runBlocking {
        val inputStream = this.javaClass.classLoader!!.getResourceAsStream("blake.jpg")
        val bytes = inputStream!!.use { it.readBytes() }
        val metadataParser = PhotoMetadataParser(bytes)
        val handle = FileHandleMock("blake.jpg")
        val context = StorageManagerActivityMock()
        val photo = Photo(
            "blake.jpg",
            "image/jpeg",
            metadataParser.width()!!,
            metadataParser.height()!!,
            metadataParser.location(),
            "5d22e5a99daf5a2683b845b43959feb52bfbec14",
            metadataParser.dateTime(),
            metadataParser.timezone(),
            Uri.EMPTY,
            mutableMapOf(StorageClientMock::class to handle)
        )

        val rotation = 90
        val newBytes = photo.edit(context, rotation = rotation)
        val newMetadata = PhotoMetadataParser(newBytes)

        assertEquals(newMetadata.width(), metadataParser.height())
        assertEquals(newMetadata.height(), metadataParser.width())
        assertEquals(newMetadata.rotation(), metadataParser.rotation() + rotation)
        assertEquals(newMetadata.location(), metadataParser.location())
        assertEquals(newMetadata.dateTime(), metadataParser.dateTime())
        assertEquals(newMetadata.timezone(), metadataParser.timezone())
    }

    @Test
    fun changePhotoLocationTest() = runBlocking {
        val inputStream = this.javaClass.classLoader!!.getResourceAsStream("blake.jpg")
        val bytes = inputStream!!.use { it.readBytes() }
        val metadataParser = PhotoMetadataParser(bytes)
        val handle = FileHandleMock("blake.jpg")
        val context = StorageManagerActivityMock()
        val photo = Photo(
            "blake.jpg",
            "image/jpeg",
            metadataParser.width()!!,
            metadataParser.height()!!,
            metadataParser.location(),
            "5d22e5a99daf5a2683b845b43959feb52bfbec14",
            metadataParser.dateTime(),
            metadataParser.timezone(),
            Uri.EMPTY,
            mutableMapOf(StorageClientMock::class to handle)
        )

        val newLocation = GeoPoint(39.74, -104.99)
        val newBytes = photo.edit(context, location = newLocation)
        val editedMetadata = PhotoMetadataParser(newBytes)

        assertEquals(editedMetadata.width(), metadataParser.width())
        assertEquals(editedMetadata.height(), metadataParser.height())
        assertEquals(editedMetadata.rotation(), metadataParser.rotation())
        assertEquals(editedMetadata.location(), newLocation)
        assertEquals(editedMetadata.dateTime(), metadataParser.dateTime())
        assertEquals(editedMetadata.timezone(), metadataParser.timezone())
    }

    @Test
    fun changePhotoTimezoneTest() = runBlocking {
        val inputStream = this.javaClass.classLoader!!.getResourceAsStream("blake.jpg")
        val bytes = inputStream!!.use { it.readBytes() }
        val metadataParser = PhotoMetadataParser(bytes)
        val handle = FileHandleMock("blake.jpg")
        val context = StorageManagerActivityMock()
        val photo = Photo(
            "blake.jpg",
            "image/jpeg",
            metadataParser.width()!!,
            metadataParser.height()!!,
            metadataParser.location(),
            "5d22e5a99daf5a2683b845b43959feb52bfbec14",
            metadataParser.dateTime(),
            metadataParser.timezone(),
            Uri.EMPTY,
            mutableMapOf(StorageClientMock::class to handle)
        )

        val newTimezone = "-06:00"
        val newBytes = photo.edit(context, timezone = newTimezone)
        val editedMetadata = PhotoMetadataParser(newBytes)

        assertEquals(editedMetadata.width(), metadataParser.width())
        assertEquals(editedMetadata.height(), metadataParser.height())
        assertEquals(editedMetadata.rotation(), metadataParser.rotation())
        assertEquals(editedMetadata.location(), metadataParser.location())
        assertEquals(editedMetadata.dateTime(), metadataParser.dateTime())
        assertEquals(editedMetadata.timezone(), newTimezone)
    }
}