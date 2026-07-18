package io.github.gustavlindberg99.photos.storage_client_utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import com.pcloud.sdk.ApiClient
import com.pcloud.sdk.RemoteFile
import io.github.gustavlindberg99.photos.BuildConfig
import io.github.gustavlindberg99.photos.activity.StorageManagerActivity
import io.github.gustavlindberg99.photos.file_handle.FileHandle
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.storage_client.PCloudClient
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import io.github.gustavlindberg99.photos.utils.makeGeoPoint
import io.github.gustavlindberg99.photos.utils.rotate
import io.github.gustavlindberg99.photos.utils.toJsonObjectList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.IOException
import java.util.SortedSet
import kotlin.apply
import kotlin.reflect.KClass

private const val VERSION = "version"
private const val DATA = "data"
private const val WIDTH = "width"
private const val HEIGHT = "height"
private const val LATITUDE = "latitude"
private const val LONGITUDE = "longitude"
private const val DATE_TIME = "dateTime"
private const val TIMEZONE = "timezone"

private const val THUMBNAILS_DIR = "thumbnails"
private const val METADATA_DIR = "metadata"
private const val PHOTOS_FILE = "photos.json"

/**
 * Gets the URI of the cached thumbnail using its SHA1. If the thumbnail isn't cached yet, downloads the entire photo from the given URI (or uses the provided bytes) and caches the thumbnail.
 *
 * Since the SHA1 is (in practice) unique for each possible photo with each possible metadata, this cache does not have to be invalidated.
 *
 * @param context   The context of the application.
 * @param sha1      The SHA1 checksum of the photo, used as a key for the cache.
 * @param handle    The handle of the photo.
 * @param bytes     The bytes of the photo that can be used to create the cache. If null, downloads the entire photo from the given URI.
 *
 * @return The URI of the cached thumbnail, or null if the given URIs point to an invalid photo.
 */
public suspend fun getCachedThumbnailBySha1(
    context: StorageManagerActivity,
    sha1: String,
    handle: FileHandle,
    bytes: ByteArray? = null
): Uri? {
    val extension = File(handle.toString()).extension
    val thumbnailFile = context.cacheDir.resolve("$THUMBNAILS_DIR/$sha1.$extension")

    if (!thumbnailFile.exists()) {
        val fetchedBytes = bytes ?: handle.getInputStream(context)
            .useWithContext(Dispatchers.IO) { it.readBytes() }

        // Extract EXIF data
        val exifInterface = try {
            fetchedBytes.inputStream().use { ExifInterface(it) }
        }
        catch (_: IOException) {
            return null
        }

        val unrotatedThumbnail = exifInterface.thumbnailBitmap
            ?: BitmapFactory.decodeByteArray(fetchedBytes, 0, fetchedBytes.size)
            ?: return null
        val thumbnail = unrotatedThumbnail.rotate(exifInterface.rotationDegrees)

        // Cache thumbnail
        thumbnailFile.parentFile?.mkdirs()
        thumbnailFile.outputStream().use { outputStream ->
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        }
    }
    return thumbnailFile.toUri()
}

/**
 * Creates a Photo object using information cached using its SHA1. If the photo isn't cached yet, downloads the entire photo from the given URI and caches it.
 *
 * Since the SHA1 is (in practice) unique for each possible photo with each possible metadata, this cache does not have to be invalidated.
 *
 * @param context   The context of the application.
 * @param fileName  The filename of the photo. Only used for constructing the Photo object, does not participate in caching.
 * @param mimeType  The MIME type of the photo. Only used for constructing the Photo object, does not participate in caching.
 * @param sha1      The SHA1 checksum of the photo, used as a key for the cache.
 * @param handles   A map with storage client types as keys, and the handle for that storage client as values.
 *
 * @return The photo, or null if the given URIs point to an invalid photo.
 */
public suspend fun getCachedPhotoBySha1(
    context: StorageManagerActivity,
    fileName: String,
    mimeType: String,
    sha1: String,
    handles: MutableMap<KClass<out StorageClient>, FileHandle>
): Photo? {
    val clients = context.storageClients().filter { it::class in handles }
    val mainClient = clients.firstOrNull { it is LocalStorageClient } ?: clients.first()
    val mainHandle = handles[mainClient::class]!!

    val metadataFile = context.cacheDir.resolve("$METADATA_DIR/$sha1.json")

    val width: Int
    val height: Int
    val location: GeoPoint?
    val dateTime: String?
    val timezone: String?
    val thumbnailUri: Uri

    if (!metadataFile.exists()) {
        val bytes =
            mainHandle.getInputStream(context).useWithContext(Dispatchers.IO) { it.readBytes() }
        thumbnailUri =
            getCachedThumbnailBySha1(context, sha1, mainHandle, bytes) ?: return null

        // Extract EXIF data
        val exifInterface = try {
            bytes.inputStream().use { ExifInterface(it) }
        }
        catch (_: IOException) {
            return null
        }

        width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        if (width <= 0 || height <= 0) {
            // Photo is invalid, skip it
            return null
        }

        location = makeGeoPoint(exifInterface.latLong?.get(0), exifInterface.latLong?.get(1))
        dateTime = (exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exifInterface.getAttribute(ExifInterface.TAG_DATETIME))
        timezone = exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?: exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME)

        // Cache metadata
        val json = JSONObject().apply {
            put(WIDTH, width)
            put(HEIGHT, height)
            if (location != null) {
                put(LATITUDE, location.latitude)
                put(LONGITUDE, location.longitude)
            }
            if (dateTime != null) {
                put(DATE_TIME, dateTime)
            }
            if (timezone != null) {
                put(TIMEZONE, timezone)
            }
        }
        metadataFile.parentFile?.mkdirs()
        metadataFile.outputStream().use { outputStream ->
            outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
        }
    }
    else {
        thumbnailUri = getCachedThumbnailBySha1(context, sha1, mainHandle) ?: return null

        val json = JSONObject(metadataFile.readText())
        width = json.getInt(WIDTH)
        height = json.getInt(HEIGHT)
        location = try {
            GeoPoint(json.getDouble(LATITUDE), json.getDouble(LONGITUDE))
        }
        catch (_: JSONException) {
            null
        }
        dateTime = try {
            json.getString(DATE_TIME)
        }
        catch (_: JSONException) {
            null
        }
        timezone = try {
            json.getString(TIMEZONE)
        }
        catch (_: JSONException) {
            null
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
        handles
    )
}

/**
 * Gets the SHA1 of the given photo, and caches it if it isn't already cached. The cache is needed because otherwise an HTTP request would be needed to get the SHA1 of the photo. Usually a cheap HTTP request if the server can produce the SHA1 directly, but that can still be expensive if we need the SHA1s of many photos at once.
 *
 * @param context       The context of the application.
 * @param pCloudClient  The PCloud client to use.
 * @param file          The file to get the SHA1 of.
 * @param apiClient     The PCloud API client to use.
 *
 * @return The SHA1 of the given photo.
 */
public suspend fun getCachedPCloudSha1(
    context: Context,
    pCloudClient: PCloudClient,
    file: RemoteFile,
    apiClient: ApiClient
): String {
    val pCloudHash = file.hash()
    val cacheFile = context.cacheDir.resolve("$METADATA_DIR/$pCloudHash.json")
    if (cacheFile.exists()) {
        // If the SHA1 is already cached, return it
        return cacheFile.readText(Charsets.UTF_8)
    }
    else {
        val fetchedSha1 = withContext(Dispatchers.IO) {
            apiClient.getChecksums(file.fileId()).execute().sha1?.hex()
        }
        val sha1: String

        if (fetchedSha1 != null) {
            // If the SHA1 can be calculated server side, use that instead of downloading the entire photo
            return fetchedSha1
        }
        else {
            // If the SHA1 can't be calculated server side, download the entire photo and calculate the SHA1 ourselves
            val sha1Sink = HashingSink.sha1(blackholeSink())
            pCloudClient
                .getInputStream(file.fileId())
                .useWithContext(Dispatchers.IO) { it.source().buffer().readAll(sha1Sink) }
            sha1 = sha1Sink.hash.hex()
        }

        // Cache the SHA1 and return it
        cacheFile.writeText(sha1, Charsets.UTF_8)
        return sha1
    }
}

/**
 * Caches the given set of photos.
 *
 * @param context   The context of the application.
 * @param allPhotos The photos to cache.
 */
public fun setCachedPhotos(context: Context, allPhotos: Set<Photo>) {
    val file = context.cacheDir.resolve(PHOTOS_FILE)
    val jsonData = JSONArray(allPhotos.map { it.toJson() })
    val json = JSONObject().apply {
        put(VERSION, BuildConfig.VERSION_CODE)
        put(DATA, jsonData)
    }
    file.outputStream().use { outputStream ->
        outputStream.write(json.toString().toByteArray(Charsets.UTF_8))
    }
}

/**
 * Gets the photos cached by setCachedPhotos.
 *
 * @param context   The context of the application.
 *
 * @return The photos.
 */
public suspend fun getCachedPhotos(
    context: Context
): SortedSet<Photo> = withContext(Dispatchers.IO) {
    try {
        val file = context.cacheDir.resolve(PHOTOS_FILE)
        val json = JSONObject(file.readText())
        val jsonData = json.getJSONArray(DATA)
        // Make sure they're sorted so that they show up in the correct order in time when opening the app.
        // While the main activity takes care of sorting the thumbnails in space, it looks weird if photos last in the list appear before photos first in the list.
        return@withContext jsonData.toJsonObjectList().map { Photo.fromJson(it) }.toSortedSet()
    }
    catch (_: IOException) {
        // This is normal if the cache is empty
        return@withContext sortedSetOf()
    }
    catch (e: JSONException) {
        Log.e("cache", e.message, e)
        return@withContext sortedSetOf()
    }
    catch (e: ClassNotFoundException) {
        Log.e("cache", e.message, e)
        return@withContext sortedSetOf()
    }
}