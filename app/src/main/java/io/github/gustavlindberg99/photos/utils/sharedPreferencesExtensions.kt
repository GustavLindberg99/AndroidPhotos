package io.github.gustavlindberg99.photos.utils

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Adds an element to a string set in a [SharedPreferences]. If the string set at the given key doesn't exist, it will be created.
 *
 * @param key   The key of the string set.
 * @param value The value to add to the string set.
 */
public fun SharedPreferences.addToStringSet(key: String, value: String) {
    val originalSet = this.getStringSet(key, null) ?: emptySet()
    this.edit {
        putStringSet(key, originalSet + setOf(value))
    }
}