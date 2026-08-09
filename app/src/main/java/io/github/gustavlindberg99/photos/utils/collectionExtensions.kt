package io.github.gustavlindberg99.photos.utils

/**
 * Smart casts a collection to a `List<T>`.
 *
 * @return The collection as a `List<T>` if all elements are of type `T`, `null` otherwise.
 */
public inline fun <reified T> Collection<*>.asTypeOrNull(): List<T>? {
    val result = mutableListOf<T>()
    for (element in this) {
        if (element is T) {
            result.add(element)
        }
        else {
            return null
        }
    }
    return result
}