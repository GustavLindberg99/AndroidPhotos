@file:Suppress("REDUNDANT_MODIFIER") // Useless warning, warns about something that makes the code more readable and can't possibly be a bug

package io.github.gustavlindberg99.photos.photo

import android.content.Context
import android.graphics.Bitmap
import android.location.Geocoder
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import com.github.gustavlindberg99.androidsuspendutils.withContext
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.HandleList
import io.github.gustavlindberg99.photos.storage_client_utils.getCachedThumbnailBySha1
import io.github.gustavlindberg99.photos.utils.readThumbnailBitmapFromInputStream
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.io.InputStream
import java.util.Date
import kotlin.text.trim

/**
 * Class representing a photo or a video.
 *
 * @param fileName      The filename of the photo.
 * @param width         The width of the photo in pixels.
 * @param height        The height of the photo in pixels.
 * @param location      The geographical location at which the photo was taken, or null if unknown.
 * @param sha1          The SHA1 checksum of the photo, used for checking for equality.
 * @param handles       A map with storage client types as keys, and the file handle for that storage client as values.
 */
abstract sealed class Media(
    public val fileName: String,
    public val mimeType: String,
    public val width: Int,
    public val height: Int,
    private val _rotation: Int,
    public val location: GeoPoint?,
    public val sha1: String,
    public val handles: HandleList
) : Comparable<Media> {
    public override fun equals(other: Any?): Boolean {
        return other is Media && this.sha1 == other.sha1
    }

    public override fun hashCode(): Int {
        return this.sha1.hashCode()
    }

    public override fun toString(): String {
        return this.fileName + " (${this.sha1})"
    }

    /**
     * Gets the date and time the photo was taken.
     *
     * @return The date and time the photo was taken, or null if this information is unavailable.
     */
    public abstract val dateTime: Date?

    /**
     * Creates an edited version of this photo. Does not modify this object itself.
     *
     * @param context   The context to use for loading the photo.
     * @param location  The new location to set.
     * @param rotation  The rotation relative to this photo's current orientation.
     *
     * @return The bytes of the new photo.
     *
     * @throws IOException If the photo could not be fetched.
     */
    public abstract suspend fun edit(
        context: StorageManagerActivity,
        location: GeoPoint? = this.location,
        rotation: Int = 0
    ): ByteArray

    /**
     * Compares this photo with another photo, so that photos that are shown first in the list (i.e. more recent) are considered smaller.
     */
    public override fun compareTo(other: Media): Int {
        val thisDateTime = this.dateTime
        val otherDateTime = other.dateTime
        val dateTimeResult =
            if (thisDateTime == null && otherDateTime == null) 0
            else if (thisDateTime == null) -1
            else if (otherDateTime == null) 1
            else thisDateTime.compareTo(otherDateTime)
        if (dateTimeResult != 0) {
            return -dateTimeResult
        }
        // Default to comparing the SHA1 checksums because comparing two different photos as equal would cause problems with sortedMap
        return -this.sha1.compareTo(other.sha1)
    }

    /**
     * Gets the bytes of the photo.
     *
     * @return The bytes of the photo.
     *
     * @throws IOException If the photo could not be fetched.
     * @throws NoSuchElementException If the photo has been deleted from all storage services.
     */
    public suspend fun getInputStream(context: StorageManagerActivity): InputStream {
        return this.handles.preferredHandle().getInputStream(context)
    }

    /**
     * Gets the thumbnail bitmap of the photo.
     *
     * @return The thumbnail bitmap of the photo.
     *
     * @throws IOException If the photo could not be fetched.
     */
    public suspend fun getThumbnail(context: StorageManagerActivity): Bitmap {
        val errorDrawable =
            AppCompatResources.getDrawable(context, R.drawable.baseline_warning_24)!!.toBitmap()
        val handle = this.handles.preferredHandle()
        val uri = getCachedThumbnailBySha1(
            context,
            this.sha1,
            handle,
            this._rotation,
            null
        ) ?: return errorDrawable
        val inputStream = try {
            context.contentResolver.openInputStream(uri)
        }
        catch (e: Exception) {
            Log.w(this.javaClass.name, e.message, e)
            return errorDrawable
        }
        return readThumbnailBitmapFromInputStream(inputStream) ?: errorDrawable
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
     * Creates a JSON object representing this photo.
     *
     * @return The JSON object.
     */
    public open fun toJson(): JSONObject {
        return JSONObject().apply {
            put(FILE_NAME, fileName)
            put(MIME_TYPE, mimeType)
            put(WIDTH, width)
            put(HEIGHT, height)
            put(ROTATION, _rotation)
            if (location != null) {
                put(LATITUDE, location.latitude)
                put(LONGITUDE, location.longitude)
            }
            put(SHA1_CHECKSUM, sha1)
            put(URIS, handles.toJson())
        }
    }

    companion object {
        private const val FILE_NAME = "fileName"
        private const val MIME_TYPE = "mimeType"
        private const val WIDTH = "width"
        private const val HEIGHT = "height"
        private const val ROTATION = "rotation"
        private const val LATITUDE = "latitude"
        private const val LONGITUDE = "longitude"
        private const val SHA1_CHECKSUM = "sha1Checksum"
        private const val URIS = "uris"
        protected const val DATE_TIME = "dateTime"
        protected const val TIMEZONE = "timezone"
        protected const val DURATION = "duration"

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
        public fun fromJson(json: JSONObject): Media {
            val fileName = json.getString(FILE_NAME)
            val mimeType = json.getString(MIME_TYPE)
            val width = json.getInt(WIDTH)
            val height = json.getInt(HEIGHT)
            val rotation = json.getInt(ROTATION)
            val location =
                if (json.has(LATITUDE) && json.has(LONGITUDE))
                    GeoPoint(json.getDouble(LATITUDE), json.getDouble(LONGITUDE))
                else null
            val sha1 = json.getString(SHA1_CHECKSUM)
            val handles = HandleList.fromJson(json.getJSONObject(URIS))
            val dateTime =
                if (json.has(DATE_TIME)) json.getString(DATE_TIME)
                else null
            val timezone =
                if (json.has(TIMEZONE)) json.getString(TIMEZONE)
                else null
            val duration =
                if (json.has(DURATION)) json.getLong(DURATION)
                else null
            if (duration != null) {
                return Video(
                    fileName,
                    mimeType,
                    width,
                    height,
                    rotation,
                    duration,
                    location,
                    sha1,
                    dateTime,
                    handles
                )
            }
            else {
                return Photo(
                    fileName,
                    mimeType,
                    width,
                    height,
                    rotation,
                    location,
                    sha1,
                    dateTime,
                    timezone,
                    handles
                )
            }
        }
    }
}