package io.github.gustavlindberg99.photos.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a [JSONArray] to a `List<JSONObject>`.
 *
 * @return A list of [JSONObject]s.
 *
 * @throws org.json.JSONException If the array contains elements that are not [JSONObject]s.
 */
public fun JSONArray.toJsonObjectList(): List<JSONObject> {
    return List(this.length(), { index -> this.getJSONObject(index) })
}

/**
 * Converts a [JSONObject] to a `Map<String, String>`.
 *
 * @return A map of strings.
 *
 * @throws org.json.JSONException If the object contains elements that are not strings.
 */
public fun JSONObject.toStringMap(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (key in this.keys()) {
        result[key] = this.getString(key)
    }
    return result
}