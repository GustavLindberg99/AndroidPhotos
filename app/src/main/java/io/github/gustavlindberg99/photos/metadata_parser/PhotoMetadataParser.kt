package io.github.gustavlindberg99.photos.metadata_parser

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import io.github.gustavlindberg99.photos.utils.makeGeoPoint
import org.osmdroid.util.GeoPoint

class PhotoMetadataParser private constructor(exifInterface: ExifInterface) : MetadataParser {
    // Everything must be initialized in the constructor in case it's constructed with a file descriptor, because the ExifInterface object can only be used while the file descriptor is open
    private val _rotation: Int = exifInterface.rotationDegrees
    private val _width: Int = exifInterface.getAttributeInt(
        if (this._rotation == 90 || this._rotation == 270) ExifInterface.TAG_IMAGE_LENGTH
        else ExifInterface.TAG_IMAGE_WIDTH,
        0
    )
    private val _height: Int = exifInterface.getAttributeInt(
        if (this._rotation == 90 || this._rotation == 270) ExifInterface.TAG_IMAGE_WIDTH
        else ExifInterface.TAG_IMAGE_LENGTH,
        0
    )
    private val _location: GeoPoint? = makeGeoPoint(
        exifInterface.latLong?.get(0),
        exifInterface.latLong?.get(1)
    )
    private val _dateTime: String? = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        ?: exifInterface.getAttribute(ExifInterface.TAG_DATETIME)
    private val _thumbnail: Bitmap? = exifInterface.thumbnailBitmap
    private val _timezone: String? =
        exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?: exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME)

    /**
     * Constructs a PhotoMetadataParser from a file descriptor. The caller is responsible for closing the file descriptor afterward.
     *
     * @param fd    The file descriptor of the photo.
     */
    public constructor(fd: ParcelFileDescriptor) : this(ExifInterface(fd.fileDescriptor))

    /**
     * Constructs a PhotoMetadataParser from a byte array.
     *
     * @param bytes The bytes of the photo. May include only a range at the beginning.
     */
    public constructor(bytes: ByteArray) : this(bytes.inputStream().use { ExifInterface(it) })

    public override fun width(): Int? {
        val result = this._width
        if (result <= 0) {
            return null
        }
        return result
    }

    public override fun height(): Int? {
        val result = this._height
        if (result <= 0) {
            return null
        }
        return result
    }

    public override fun rotation(): Int {
        return this._rotation
    }

    public override fun location(): GeoPoint? {
        return this._location
    }

    public override fun dateTime(): String? {
        return this._dateTime
    }

    public override fun thumbnail(): Bitmap? {
        return this._thumbnail
    }

    /**
     * Gets the timezone the photo was taken in, in the `±HH:MM` format.
     *
     * @return The timezone the photo was taken in, or null if it's unknown.
     */
    public fun timezone(): String? {
        return this._timezone
    }
}