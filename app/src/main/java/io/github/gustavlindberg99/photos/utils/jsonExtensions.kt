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