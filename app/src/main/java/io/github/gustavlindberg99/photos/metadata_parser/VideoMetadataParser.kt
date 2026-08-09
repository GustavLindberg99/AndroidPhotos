package io.github.gustavlindberg99.photos.metadata_parser

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import io.github.gustavlindberg99.photos.utils.makeGeoPoint
import org.osmdroid.util.GeoPoint
import java.io.FileDescriptor
import java.util.regex.Pattern

class VideoMetadataParser private constructor(retriever: MediaMetadataRetriever) :
    MetadataParser {
    private val _width: Int
    private val _height: Int
    private val _locationString: String?
    private val _dateTime: String?
    private val _thumbnail: Bitmap?
    private val _duration: Long?

    init {
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toInt() ?: 0
        val rotated = rotation == 90 || rotation == 270
        val widthKey =
            if (rotated) MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            else MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        val heightKey =
            if (rotated) MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            else MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT

        this._width = retriever.extractMetadata(widthKey)?.toInt() ?: 0
        this._height = retriever.extractMetadata(heightKey)?.toInt() ?: 0
        this._locationString =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
        this._dateTime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        this._thumbnail = retriever.getFrameAtTime(0)
        this._duration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLong()
    }

    /**
     * Constructs a VideoMetadataParser from a file descriptor. The caller is responsible for closing the file descriptor afterward.
     *
     * @param fd    The file descriptor of the video.
     */
    public constructor(fd: ParcelFileDescriptor) : this(MediaMetadataRetriever().apply {
        setDataSource(fd.fileDescriptor)
    })

    /**
     * Constructs a VideoMetadataParser from a data source.
     *
     * @param dataSource    The data source of the video.
     */
    public constructor(dataSource: MediaDataSource) : this(MediaMetadataRetriever().apply {
        setDataSource(dataSource)
    })

    /**
     * Constructs a VideoMetadataParser from a file descriptor with offset and length. The caller is responsible for closing the file descriptor afterward.
     *
     * @param fd     The file descriptor of the video.
     * @param offset The offset into the file where the data begins.
     * @param length The length in bytes of the data.
     */
    @VisibleForTesting
    constructor(fd: FileDescriptor, offset: Long, length: Long) : this(MediaMetadataRetriever().apply {
        setDataSource(fd, offset, length)
    })

    public override fun width(): Int? {
        if (this._width <= 0) {
            return null
        }
        return this._width
    }

    public override fun height(): Int? {
        if (this._height <= 0) {
            return null
        }
        return this._height
    }

    public override fun rotation(): Int {
        // Return 0 rotation because the library already applies the rotation when necessary
        return 0
    }

    public override fun location(): GeoPoint? {
        if (this._locationString == null) {
            return null
        }
        val matcher = Pattern.compile("([+-][0-9.]+)([+-][0-9.]+)").matcher(this._locationString)
        if (!matcher.find()) {
            return null
        }
        return makeGeoPoint(matcher.group(1)?.toDouble(), matcher.group(2)?.toDouble())
    }

    public override fun dateTime(): String? {
        return this._dateTime
    }

    public override fun thumbnail(): Bitmap? {
        return this._thumbnail
    }

    /**
     * Gets the duration of the video in milliseconds.
     *
     * @return The duration of the video, or null if it's not a valid video.
     */
    public fun duration(): Long? {
        return this._duration
    }
}