package io.github.gustavlindberg99.photos.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.launch
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import com.github.gustavlindberg99.androidsuspendutils.withContext
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.HandleList
import io.github.gustavlindberg99.photos.utils.rotate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Class representing a photo.
 *
 * @param fileName      The filename of the photo.
 * @param width         The width of the photo in pixels.
 * @param height        The height of the photo in pixels.
 * @param location      The geographical location at which the photo was taken, or null if unknown.
 * @param sha1          The SHA1 checksum of the photo, used for checking for equality.
 * @param _dateTime     The date and time the photo was taken in `yyyy:MM:dd HH:mm:ss` format, or null if unknown.
 * @param timezone      The timezone the photo was taken in `±HH:MM` format, or null if unknown.
 * @param handles       A map with storage client types as keys, and the file handle for that storage client as values.
 */
class Photo(
    fileName: String,
    mimeType: String,
    width: Int,
    height: Int,
    rotation: Int,
    location: GeoPoint?,
    sha1: String,
    private val _dateTime: String?,
    public val timezone: String?,
    handles: HandleList
) : Media(fileName, mimeType, width, height, rotation, location, sha1, handles) {
    public override val dateTime: Date? by lazy {
        if (this._dateTime == null) {
            return@lazy null
        }
        else try {
            if (this.timezone != null) {
                return@lazy SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US)
                    .parse(this._dateTime + this.timezone)
            }
            else {
                return@lazy SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .parse(this._dateTime)
            }
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
            if (timezone != null) {
                put(TIMEZONE, timezone)
            }
        }
    }

    public override suspend fun edit(
        context: StorageManagerActivity,
        location: GeoPoint?,
        rotation: Int
    ): ByteArray {
        return this.edit(context, location, rotation, this.timezone)
    }

    /**
     * Creates an edited version of this photo. Does not modify this object itself.
     *
     * @param context   The context to use for loading the photo.
     * @param location  The new location to set.
     * @param rotation  The rotation relative to this photo's current orientation.
     * @param timezone  The new timezone to set, in the `±HH:MM` format.
     *
     * @return The bytes of the new photo.
     *
     * @throws IOException If the photo could not be fetched.
     */
    public suspend fun edit(
        context: StorageManagerActivity,
        location: GeoPoint? = this.location,
        rotation: Int = 0,
        timezone: String?
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
        if (timezone != this.timezone) {
            exifInterface.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, timezone)
        }
        exifInterface.saveAttributes()

        val result = tempFile.readBytes()
        tempFile.delete()
        return result
    }

    /**
     * Gets whether the photo has a timezone. If not, [dateTime] will default to the current timezone, which might not be correct.
     */
    public val hasTimezone: Boolean = this.timezone != null

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