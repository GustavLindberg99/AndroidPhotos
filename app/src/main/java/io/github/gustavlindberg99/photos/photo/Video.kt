package io.github.gustavlindberg99.photos.photo

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.github.gustavlindberg99.androidsuspendutils.launch
import com.github.gustavlindberg99.androidsuspendutils.withContext
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.HandleList
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.utils.patchMp4Dates
import kotlinx.coroutines.Dispatchers
import okio.FileNotFoundException
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.nio.ByteBuffer
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Class representing a video.
 *
 * @param fileName      The filename of the photo.
 * @param width         The width of the photo in pixels.
 * @param height        The height of the photo in pixels.
 * @param duration      The duration of the video in milliseconds.
 * @param location      The geographical location at which the photo was taken, or null if unknown.
 * @param sha1          The SHA1 checksum of the photo, used for checking for equality.
 * @param _dateTime     The date and time the photo was taken in `yyyy:MM:dd HH:mm:ss` format, or null if unknown.
 * @param handles       A map with storage client types as keys, and the file handle for that storage client as values.
 */
class Video(
    fileName: String,
    mimeType: String,
    width: Int,
    height: Int,
    rotation: Int,
    public val duration: Long,
    location: GeoPoint?,
    sha1: String,
    private val _dateTime: String?,
    handles: HandleList
) : Media(fileName, mimeType, width, height, rotation, location, sha1, handles) {
    public override val dateTime: Date? by lazy {
        try {
            return@lazy SimpleDateFormat("yyyyMMdd'T'HHmmss.SSSX", Locale.US)
                .parse(this._dateTime ?: return@lazy null)
        }
        catch (_: ParseException) {
            return@lazy null
        }
    }

    public override fun toJson(): JSONObject {
        return super.toJson().apply {
            if (_dateTime != null) {
                put(DATE_TIME, _dateTime)
            }
            put(DURATION, duration)
        }
    }

    public override suspend fun edit(
        context: StorageManagerActivity,
        location: GeoPoint?,
        rotation: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile(
            this.fileName,
            File(this.fileName).extension,
            context.cacheDir
        )
        val sourceFile = File.createTempFile(
            "source",
            "." + File(this.fileName).extension,
            context.cacheDir
        )
        this.getInputStream(context).use { input ->
            sourceFile.outputStream().use { output -> input.copyTo(output) }
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(sourceFile.absolutePath)

        val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Update location
        val newLocation = location ?: this@Video.location
        if (newLocation != null) {
            muxer.setLocation(newLocation.latitude.toFloat(), newLocation.longitude.toFloat())
        }

        // Update rotation
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(sourceFile.absolutePath)
        val currentRotation =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt()
                ?: 0
        retriever.release()

        val newRotation = (currentRotation + rotation + 360) % 360
        muxer.setOrientationHint(newRotation)

        val trackCount = extractor.trackCount
        val trackIndices = IntArray(trackCount)
        for (i in 0 until trackCount) {
            val format = extractor.getTrackFormat(i)
            trackIndices[i] = muxer.addTrack(format)
            extractor.selectTrack(i)
        }

        // Write the video
        muxer.start()

        val bufferSize = 1024 * 1024
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            if (bufferInfo.size < 0) {
                break
            }
            bufferInfo.presentationTimeUs = extractor.sampleTime
            @SuppressLint("WrongConstant")
            bufferInfo.flags = extractor.sampleFlags
            val trackIndex = extractor.sampleTrackIndex
            muxer.writeSampleData(trackIndices[trackIndex], buffer, bufferInfo)
            extractor.advance()
        }

        muxer.stop()
        muxer.release()
        extractor.release()

        // Changing the metadata will change the creation date to the current time, so change it back
        patchMp4Dates(tempFile, this.dateTime)

        // Extract the bytes and delete the temporary files
        val result = tempFile.readBytes()
        tempFile.delete()
        sourceFile.delete()
        return@withContext result
    }

    /**
     * Sets up the given player to play this video.
     *
     * @param context       The context of the application.
     * @param playerView    The player view to set up.
     */
    @OptIn(UnstableApi::class)
    public fun setupPlayer(
        context: StorageManagerActivity,
        playerView: PlayerView
    ) = context.lifecycleScope.launch {
        val clients = context.storageClients().filter { this.handles.getHandle(it::class) != null }
        val client = clients.firstOrNull { it is LocalStorageClient } ?: clients.first()

        val handle = this.handles.getHandle(client::class)
            ?: throw FileNotFoundException("Video has no handles")
        val datasourceFactory = client.dataFactory(context)

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(datasourceFactory))
            .build()
        player.playWhenReady = true
        playerView.player = player

        player.addListener(object : Player.Listener {
            public override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    context,
                    context.getString(R.string.failedToPlayVideo, error.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        })

        val uri = handle.getPlaybackUri(context)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(this.mimeType)
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
    }
}