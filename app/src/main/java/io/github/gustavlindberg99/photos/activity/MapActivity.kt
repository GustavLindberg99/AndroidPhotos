package io.github.gustavlindberg99.photos.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.launch
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.databinding.ActivityMapBinding
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.PhotoManager
import io.github.gustavlindberg99.photos.utils.initOsmdroid
import org.osmdroid.views.overlay.Marker

class MapActivity : AppCompatActivity() {
    private val _binding: ActivityMapBinding by lazy { ActivityMapBinding.inflate(this.layoutInflater) }
    private val _markers = mutableMapOf<Photo, Marker>()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load osmdroid configuration
        initOsmdroid(this)

        this.setContentView(this._binding.root)

        this._binding.map.setMultiTouchControls(true)

        val mapController = this._binding.map.controller
        mapController.setZoom(3.5)

        // Load photos
        PhotoManager.setPhotoAddedListener(this, this::addMarker)
        PhotoManager.setPhotoRemovedListener(this, this::removeMarker)
    }

    public override fun onResume() {
        super.onResume()
        this._binding.map.onResume()
    }

    public override fun onPause() {
        super.onPause()
        this._binding.map.onPause()
    }

    /**
     * Adds a marker for the given photo.
     *
     * @param photo The photo to add a marker for.
     */
    private fun addMarker(photo: Photo) {
        if (photo.location != null) {
            val marker = Marker(this._binding.map)
            marker.position = photo.location
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.lifecycleScope.launch {
                try {
                    val bitmap = photo.getThumbnail(this)
                    marker.image = bitmap.toDrawable(resources)
                    this._binding.map.invalidate()
                }
                catch (_: Exception) {
                    // Ignore, the marker will just not have an image
                }
            }
            marker.setOnMarkerClickListener { _, _ ->
                try {
                    val intent = Intent(this, PhotoActivity::class.java)
                    val index = PhotoManager.indexFromPhoto(photo)
                    intent.putExtra(PhotoActivity.PHOTO_INDEX, index)
                    this.startActivity(intent)
                }
                catch (e: Exception) {
                    Log.w(this.javaClass.name, e.message, e)
                    Toast.makeText(
                        this,
                        this.getString(R.string.couldNotViewPhoto),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@setOnMarkerClickListener true
            }
            this._binding.map.overlays.add(marker)
            this._markers[photo] = marker
        }
    }

    /**
     * Removes the marker for the given photo.
     *
     * @param photo The photo to remove the marker for.
     */
    private fun removeMarker(photo: Photo) {
        this._markers[photo]?.remove(this._binding.map)
        this._markers.remove(photo)
    }
}