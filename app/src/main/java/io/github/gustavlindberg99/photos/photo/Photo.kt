package io.github.gustavlindberg99.photos.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.launch
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import com.github.gustavlindberg99.androidsuspendutils.withContext
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.FileHandle
import io.github.gustavlindberg99.photos.file_handle.GoogleDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.OneDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.file_handle.PCloudFileHandle
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.storage_client.OneDriveStorageClient
import io.github.gustavlindberg99.photos.storage_client.PCloudClient
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import io.github.gustavlindberg99.photos.utils.rotate
import io.github.gustavlindberg99.photos.utils.toStringMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KClass

/**
 * Class representing a photo.
 *
 * @param fileName      The filename of the photo.
 * @param width         The width of the photo in pixels.
 * @param height        The height of the photo in pixels.
 * @param location      The geographical location at which the photo was taken, or null if unknown.
 * @param sha1          The SHA1 checksum of the photo, used for checking for equality.
 * @param _dateTime     The date and time the photo was taken in `yyyy:MM:dd HH:mm:ss` format, or null if unknown.
 * @param _thumbnailUri The URI of the thumbnail of the photo. Must be a local URI (usually a cache file).
 * @param handles       A map with storage client types as keys, and the file handle for that storage client as values.
 */
class Photo(
    public val fileName: String,
    public val mimeType: String,
    public val width: Int,
    public val height: Int,
    public val location: GeoPoint?,
    public val sha1: String,
    private val _dateTime: String?,
    private val _timezone: String?,
    private val _thumbnailUri: Uri,
    public val handles: MutableMap<KClass<out StorageClient>, FileHandle>
) : Comparable<Photo> {
    public override fun equals(other: Any?): Boolean {
        return other is Photo && this.sha1 == other.sha1
    }

    public override fun hashCode(): Int {
        return this.sha1.hashCode()
    }

    public override fun toString(): String {
        return this.fileName + " (${this.sha1})"
    }

    /**
     * Compares this photo with another photo, so that photos that are shown first in the list (i.e. more recent) are considered smaller.
     */
    public override fun compareTo(other: Photo): Int {
        val dateTimeResult =
            if (this.dateTime == null && other.dateTime == null) 0
            else if (this.dateTime == null) -1
            else if (other.dateTime == null) 1
            else this.dateTime.compareTo(other.dateTime)
        if (dateTimeResult != 0) {
            return -dateTimeResult
        }
        // Default to comparing the SHA1 checksums because comparing two different photos as equal would cause problems with sortedMap
        return -this.sha1.compareTo(other.sha1)
    }

    /**
     * Creates a JSON object representing this photo.
     *
     * @return The JSON object.
     */
    public fun toJson(): JSONObject {
        return JSONObject().apply {
            put(FILE_NAME, fileName)
            put(MIME_TYPE, mimeType)
            put(WIDTH, width)
            put(HEIGHT, height)
            if (location != null) {
                put(LATITUDE, location.latitude)
                put(LONGITUDE, location.longitude)
            }
            put(SHA1_CHECKSUM, sha1)
            if (_dateTime != null) {
                put(DATE_TIME, _dateTime)
            }
            if (_timezone != null) {
                put(TIMEZONE, _timezone)
            }
            put(THUMBNAIL_URI, _thumbnailUri.toString())
            val handles = this@Photo.handles
                .mapKeys { it.key.qualifiedName!! }
                .mapValues { it.value.toString() }
            put(URIS, JSONObject(handles))
        }
    }

    companion object {
        private const val FILE_NAME = "fileName"
        private const val MIME_TYPE = "mimeType"
        private const val WIDTH = "width"
        private const val HEIGHT = "height"
        private const val LATITUDE = "latitude"
        private const val LONGITUDE = "longitude"
        private const val SHA1_CHECKSUM = "sha1Checksum"
        private const val DATE_TIME = "dateTime"
        private const val TIMEZONE = "timezone"
        private const val THUMBNAIL_URI = "thumbnailUri"
        private const val URIS = "uris"

        /**
         * Creates a Photo object from a JSON object.
         *
         * @param json  The JSON object.
         *
         * @return The Photo object.
         *
         * @throws org.json.JSONException If the JSON object is invalid.
         * @throws ClassNotFoundException If a key for the URIs does not correspond to a valid StorageClient subclass.
         */
        public fun fromJson(json: JSONObject): Photo {
            val fileName = json.getString(FILE_NAME)
            val mimeType = json.getString(MIME_TYPE)
            val width = json.getInt(WIDTH)
            val height = json.getInt(HEIGHT)
            val location =
                if (json.has(LATITUDE) && json.has(LONGITUDE))
                    GeoPoint(json.getDouble(LATITUDE), json.getDouble(LONGITUDE))
                else null
            val sha1 = json.getString(SHA1_CHECKSUM)
            val dateTime =
                if (json.has(DATE_TIME)) json.getString(DATE_TIME)
                else null
            val timezone =
                if (json.has(TIMEZONE)) json.getString(TIMEZONE)
                else null
            val thumbnailUri = json.getString(THUMBNAIL_URI).toUri()
            val handles = json.getJSONObject(URIS).toStringMap()
                .mapKeys { Class.forName(it.key).asSubclass(StorageClient::class.java).kotlin }
                .mapValues {
                    when (it.key) {
                        LocalStorageClient::class -> UriHandle(it.value.toUri())
                        GoogleDriveClient::class -> GoogleDriveFileHandle(it.value)
                        OneDriveStorageClient::class -> OneDriveFileHandle(it.value)
                        PCloudClient::class -> PCloudFileHandle(it.value.toLong())
                        else -> throw ClassNotFoundException(it.key.qualifiedName)
                    }
                }
            return Photo(
                fileName,
                mimeType,
                width,
                height,
                location,
                sha1,
                dateTime,
                timezone,
                thumbnailUri,
                handles.toMutableMap()
            )
        }
    }

    /**
     * Gets the date and time the photo was taken.
     *
     * @return The date and time the photo was taken, or null if this information is unavailable.
     */
    public val dateTime: Date? =
        if (this._dateTime == null) {
            null
        }
        else try {
            if (this._timezone != null) {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US)
                    .parse(this._dateTime + this._timezone)
            }
            else {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .parse(this._dateTime)
            }
        }
        catch (_: ParseException) {
            null
        }

    /**
     * Gets whether the photo has a timezone. If not, [dateTime] will default to the current timezone, which might not be correct.
     */
    public val hasTimezone: Boolean = this._timezone != null

    /**
     * Gets the bytes of the photo.
     *
     * @return The bytes of the photo.
     *
     * @throws IOException If the photo could not be fetched.
     * @throws NoSuchElementException If the photo has been deleted from all storage services.
     */
    public suspend fun getInputStream(context: StorageManagerActivity): InputStream {
        val clients = context.storageClients().filter { it::class in this.handles }
        val client = clients.firstOrNull { it is LocalStorageClient } ?: clients.first()
        return this.handles[client::class]!!.getInputStream(context)
    }

    /**
     * Gets the full bitmap of the photo. Only use when displaying the photo on its own. Otherwise, use the thumbnail for performance reasons.
     *
     * @return The full bitmap of the photo.
     *
     * @throws IOException  If the photo could not be fetched.
     */
    public suspend fun getBitmap(context: StorageManagerActivity): Bitmap {
        val bytes = this.getInputStream(context).useWithContext(Dispatchers.IO) { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val exifInterface = ExifInterface(bytes.inputStream())
        return bitmap.rotate(exifInterface.rotationDegrees)
    }

    /**
     * Gets the thumbnail bitmap of the photo.
     *
     * @return The thumbnail bitmap of the photo.
     *
     * @throws IOException If the photo could not be fetched.
     */
    public suspend fun getThumbnail(context: Context): Bitmap = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(this._thumbnailUri)
            ?.use { it.readBytes() }
            ?: throw IOException("No stream available")
        val exifInterface = ExifInterface(bytes.inputStream())
        val thumbnail =
            exifInterface.thumbnailBitmap ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return@withContext thumbnail.rotate(exifInterface.rotationDegrees)
    }

    /**
     * Gets the name of the city where the photo was taken.
     *
     * @return The name of the city, or null if this information is unavailable.
     */
    public suspend fun cityName(context: Context): String? {
        val location = this.location ?: return null

        val address = try {
            withContext(Dispatchers.IO) {
                // getFromLocation is deprecated in favor of an overload that's non-blocking and takes a callback. While that would be a better solution, it's not available until API level 33, so it can't be used here.
                @Suppress("DEPRECATION")
                Geocoder(context)
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
            }
        }
        catch (e: Exception) {
            Log.w(this.javaClass.name, e.message, e)
            null
        }
        val locality = address?.locality
        val cityName =
            if (locality == null || locality.trim().isEmpty()) address?.countryName
            else locality
        return cityName
    }

    /**
     * If the other photo has handles with storage services that this photo doesn't have, copies those handles into this Photo object.
     *
     * @param other The photo to get the handles from.
     */
    public fun mergeHandlesWith(other: Photo, delete: Boolean) {
        if (delete) {
            this.handles.keys.removeAll { it !in other.handles }
        }
        else {
            this.handles.putAll(other.handles)
        }
    }

    /**
     * Creates an edited version of this photo. Does not modify this object itself.
     *
     * @param location   The new location to set.
     * @param rotation      The rotation with respect to this photo's orientation.
     *
     * @return The bytes of the new photo.
     *
     * @throws IOException If the photo could not be fetched.
     */
    public suspend fun edit(
        context: StorageManagerActivity,
        location: GeoPoint? = this.location,
        rotation: Int = 0
    ): ByteArray {
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile(
                this.fileName,
                File(this.fileName).extension,
                context.cacheDir
            )
        }
        this.getInputStream(context).useWithContext(Dispatchers.IO) { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }

        val exifInterface = ExifInterface(tempFile.absolutePath)
        if (location != this.location) {
            if (location != null) {
                exifInterface.setLatLong(location.latitude, location.longitude)
            }
            else {
                exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE, null)
                exifInterface.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, null)
                exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, null)
                exifInterface.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, null)
            }
        }
        if (rotation != 0) {
            val currentRotation = exifInterface.rotationDegrees
            val newRotation = (currentRotation + rotation + 360) % 360
            val orientation = when (newRotation) {
                0 -> ExifInterface.ORIENTATION_NORMAL
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> throw IllegalArgumentException("Invalid rotation: $newRotation")
            }
            exifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        }
        exifInterface.saveAttributes()

        val result = tempFile.readBytes()
        tempFile.delete()
        return result
    }

    /**
     * Shows the photo on the given view, initially showing the bitmap and then fetching the full bitmap in the background.
     *
     * @param context   The context to use for loading the photo.
     * @param view      The view to show the photo on.
     */
    public fun showOnView(context: StorageManagerActivity, view: ImageView) {
        context.lifecycleScope.launch {
            view.setImageBitmap(this.getThumbnail(context))
            try {
                view.setImageBitmap(this.getBitmap(context))
            }
            catch (_: CancellationException) {
                // Do nothing, this is normal if the activity is closed before the photo is fetched
            }
            catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.couldNotFetchPhoto, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}