package io.github.gustavlindberg99.photos.utils

import android.content.Context
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint

/**
 * Initializes the osmdroid configuration.
 *
 * @param context The context to use for loading the configuration.
 */
fun initOsmdroid(context: Context) {
    val ctx = context.applicationContext
    Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
    Configuration.getInstance().userAgentValue = ctx.packageName

    // Limit the memory cache to avoid OutOfMemoryErrors
    Configuration.getInstance().cacheMapTileCount = 64
    Configuration.getInstance().cacheMapTileOvershoot = 8
}

/**
 * Convenience function to create a nullable GeoPoint from two nullable doubles.
 *
 * @param latitude  The latitude of the point.
 * @param longitude The longitude of the point.
 *
 * @return The GeoPoint, or null if either latitude or longitude is null.
 */
public fun makeGeoPoint(latitude: Double?, longitude: Double?): GeoPoint? {
    if (latitude == null || longitude == null) {
        return null
    }
    return GeoPoint(latitude, longitude)
}