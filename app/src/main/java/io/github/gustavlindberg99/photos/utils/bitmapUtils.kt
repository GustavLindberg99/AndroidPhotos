package io.github.gustavlindberg99.photos.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import kotlinx.coroutines.Dispatchers
import java.io.InputStream

/**
 * Rotates the bitmap by the given number of degrees.
 *
 * @param degrees The number of degrees to rotate the bitmap by.
 *
 * @return The rotated bitmap.
 */
public fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (rotated != this) {
        recycle()
    }
    return rotated
}

/**
 * Calculates the in sample size for a bitmap.
 *
 * @param options The options for the bitmap.
 * @param reqWidth The required width of the bitmap.
 * @param reqHeight The required height of the bitmap.
 *
 * @return The in sample size.
 */
public fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/**
 * Reads a thumbnail bitmap from an input stream.
 *
 * @param inputStream The input stream to read from.
 *
 * @return The thumbnail bitmap, or null if the input stream is null or the thumbnail could not be read.
 */
public suspend fun readThumbnailBitmapFromInputStream(inputStream: InputStream?): Bitmap? {
    return inputStream?.useWithContext(Dispatchers.IO) { inputStream ->
        val bufferedInputStream = inputStream.buffered()
        bufferedInputStream.mark(1024 * 1024) // 1MB buffer for header

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(bufferedInputStream, null, options)
        bufferedInputStream.reset()

        options.inSampleSize = calculateInSampleSize(options, 300, 300)
        options.inJustDecodeBounds = false
        BitmapFactory.decodeStream(bufferedInputStream, null, options)
    }
}