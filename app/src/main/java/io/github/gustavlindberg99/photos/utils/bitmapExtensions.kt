package io.github.gustavlindberg99.photos.utils

import android.graphics.Bitmap
import android.graphics.Matrix

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