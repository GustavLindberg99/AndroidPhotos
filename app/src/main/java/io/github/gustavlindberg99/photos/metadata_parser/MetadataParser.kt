package io.github.gustavlindberg99.photos.metadata_parser

import android.graphics.Bitmap
import org.osmdroid.util.GeoPoint

sealed interface MetadataParser {
    /**
     * Gets the width of the photo.
     *
     * @return The width of the photo, or null if the photo is invalid.
     */
    public fun width(): Int?

    /**
     * Gets the height of the photo.
     *
     * @return The height of the photo, or null if the photo is invalid.
     */
    public fun height(): Int?

    /**
     * Gets the rotation of the photo.
     *
     * @return The rotation of the photo in degrees.
     */
    public fun rotation(): Int

    /**
     * Gets the location of the photo.
     *
     * @return The location of the photo, or null if it's unknown.
     */
    public fun location(): GeoPoint?

    /**
     * Gets the date and time the photo was taken.
     *
     * @return The date and time the photo was taken, or null if it's unknown.
     */
    public fun dateTime(): String?

    /**
     * Gets the thumbnail of the photo. May be unavailable for photos (in which case the original photo should be used as fallback), but is guaranteed to be available for any valid video.
     *
     * @return The thumbnail of the photo, or null if the thumbnail is unavailable.
     */
    public fun thumbnail(): Bitmap?
}