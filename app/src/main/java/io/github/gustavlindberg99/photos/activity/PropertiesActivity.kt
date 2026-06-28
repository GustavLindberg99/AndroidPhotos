package io.github.gustavlindberg99.photos.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.util.TypedValueCompat.dpToPx
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.concurrentForEach
import com.github.gustavlindberg99.androidsuspendutils.launch
import com.github.gustavlindberg99.androidsuspendutils.setOnClickListenerAsync
import com.github.gustavlindberg99.androidsuspendutils.showAsync
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.PhotoManager
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import io.github.gustavlindberg99.photos.utils.initOsmdroid
import kotlinx.coroutines.Job
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import kotlin.math.max

/**
 * Base class for activities that can show properties of photos.
 */
abstract class PropertiesActivity : StorageManagerActivity() {
    private val _mainLayout: LinearLayout by lazy { this.findViewById(R.id.PropertiesActivity_main) }
    private val _bottomSheet: LinearLayout by lazy { this.findViewById(R.id.PropertiesActivity_bottomSheet) }
    private val _bottomSheetBehavior by lazy { BottomSheetBehavior.from(this._bottomSheet) }
    private val _rotateLeftButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_rotateLeftButton) }
    private val _rotateRightButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_rotateRightButton) }
    private val _deleteButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_deleteButton) }
    private val _fileNameRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_fileNameRow) }
    private val _dateTimeRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_dateTimeRow) }
    private val _noTimezoneRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_noTimezoneRow) }
    private val _locationRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_locationRow) }
    private val _map: MapView by lazy { this.findViewById(R.id.PropertiesActivity_map) }
    private val _changeLocationButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_changeLocationButton) }
    private val _deleteLocationButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_deleteLocationButton) }
    private val _cancelChangeLocationButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_cancelChangeLocationButton) }
    private val _storageSwitchesLayout: LinearLayout by lazy { this.findViewById(R.id.PropertiesActivity_storageSwitchesLayout) }

    private val _checkboxes = mutableMapOf<StorageClient, MaterialCheckBox>()

    private val _photos = mutableSetOf<Photo>()
    private val _photosBeingEdited = mutableSetOf<Photo>()

    private var _getLocationJob: Job? = null

    private val _mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(point: GeoPoint?): Boolean {
            if (point != null) {
                _map.overlays.clear()
                val marker = Marker(_map)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.position = point
                _map.overlays.add(marker)
                _map.invalidate()
                lifecycleScope.launch {
                    editPhotos { it.edit(this@PropertiesActivity, location = point) }
                }
                return true
            }
            return false
        }

        override fun longPressHelper(point: GeoPoint?): Boolean {
            return false
        }
    })

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()
        super.setContentView(R.layout.activity_properties)

        ViewCompat.setOnApplyWindowInsetsListener(this.findViewById(R.id.PropertiesActivity_bottomSheet)) { v, insets ->
            val padding = dpToPx(8f, this.resources.displayMetrics).toInt()
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                max(padding, systemBars.left),
                max(padding, systemBars.top),
                max(padding, systemBars.right),
                max(padding, systemBars.bottom)
            )
            return@setOnApplyWindowInsetsListener insets
        }

        this._bottomSheetBehavior.isHideable = true
        this._bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        PhotoManager.setPhotoRemovedListener(this, { photo ->
            this._photos.remove(photo)
            this._photosBeingEdited.remove(photo)
            this.updateUi()
        })

        this._rotateLeftButton.setOnClickListenerAsync {
            this.editPhotos { it.edit(this, rotation = -90) }
        }
        this._rotateRightButton.setOnClickListenerAsync {
            this.editPhotos { it.edit(this, rotation = 90) }
        }

        this._deleteButton.setOnClickListenerAsync {
            this.changeBackupState(this.storageClients(), false)
        }

        this._changeLocationButton.setOnClickListener { this.startEditingLocation() }
        this._deleteLocationButton.setOnClickListenerAsync {
            val proceed = AlertDialog.Builder(this)
                .setTitle(R.string.changeLocation)
                .setMessage(R.string.changeLocationConfirmation)
                .showAsync(R.string.yes, R.string.no)
            if (proceed) {
                this.editPhotos { it.edit(this, location = null) }
            }
        }
        this._cancelChangeLocationButton.setOnClickListener { this.updateUi() }

        // Load osmdroid configuration
        initOsmdroid(this)

        // Initialize map
        this._map.setMultiTouchControls(true)
    }

    public override fun onResume() {
        super.onResume()
        this._map.onResume()

        if (this._photos.isEmpty()) {
            this._storageSwitchesLayout.removeAllViews()
            this._checkboxes.clear()
            this.lifecycleScope.launch {
                for (client in storageClients()) {
                    val checkbox = MaterialCheckBox(this)
                    checkbox.textSize = 18f
                    checkbox.setOnClickListenerAsync {
                        checkbox.text =
                            if (client is LocalStorageClient) this.getString(R.string.downloading)
                            else this.getString(R.string.uploading)
                        changeBackupState(setOf(client), checkbox.isChecked)
                    }
                    this._storageSwitchesLayout.addView(checkbox)
                    this._checkboxes[client] = checkbox
                }
            }
        }
    }

    public override fun onPause() {
        super.onPause()
        this._map.onPause()
    }

    public override fun setContentView(layoutResId: Int) {
        val inflater = LayoutInflater.from(this)
        inflater.inflate(layoutResId, this._mainLayout, true)
    }

    /**
     * Toggles whether the given photo is selected.
     *
     * @param photo     The photo to toggle.
     * @param updateUi  True if the UI should be updated.
     *
     * @return True of the photo became selected, false if it became unselected.
     */
    public open fun togglePhotoSelected(photo: Photo, updateUi: Boolean = true): Boolean {
        val result: Boolean
        if (this._photos.contains(photo)) {
            this._photos.remove(photo)
            result = false
        }
        else {
            this._photos.add(photo)
            result = true
        }
        if (updateUi) {
            this.updateUi()
        }

        return result
    }

    /**
     * Deselects all photos.
     */
    public open fun deselectAllPhotos() {
        this._photos.clear()
        this.updateUi()
    }

    /**
     * Gets the selected photos.
     *
     * @return The selected photos.
     */
    public fun selectedPhotos(): Set<Photo> {
        return this._photos
    }

    /**
     * Updates the UI to show the selected photos.
     */
    private fun updateUi() {
        if (this._photos.isEmpty()) {
            this._bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            return
        }

        this._bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        if (this._photos.size == 1) {
            this._fileNameRow.text =
                this.getString(R.string.fileName, this._photos.first().fileName)
        }
        else {
            this._fileNameRow.text = this.resources.getQuantityString(
                R.plurals.multipleSelected,
                this._photos.size,
                this._photos.size
            )
        }

        val minDate = this._photos.min().dateTime
        val maxDate = this._photos.max().dateTime
        if (minDate == maxDate) {
            this._dateTimeRow.text = this.getString(R.string.date, minDate.toString())
        }
        else {
            this._dateTimeRow.text = this.getString(R.string.date, "$minDate - $maxDate")
        }
        val hasTimezone = this._photos.all { it.hasTimezone }
        this._noTimezoneRow.isVisible = !hasTimezone

        val photoWithLocation =
            this._photos.firstOrNull { it.location != null } ?: this._photos.firstOrNull()
        if (photoWithLocation?.location == null) {
            this._map.visibility = View.GONE
            this._locationRow.text =
                this.getString(R.string.location, this.getString(R.string.unknown))
        }
        else {
            val photosWithLocations = this._photos.filter { it.location != null }
            this._map.visibility = View.VISIBLE
            this._getLocationJob?.cancel()
            this._getLocationJob = null
            this._locationRow.text = this.resources.getQuantityString(
                R.plurals.multipleLocations,
                photosWithLocations.size,
                photosWithLocations.size
            )
            if (photosWithLocations.size == 1) {
                this._getLocationJob = this.lifecycleScope.launch {
                    val cityName = photoWithLocation.cityName(this)
                        ?: "${photoWithLocation.location.latitude}, ${photoWithLocation.location.longitude}"
                    _locationRow.text = this.getString(R.string.location, cityName)
                }
            }
        }

        this._map.overlays.clear()
        for (photo in this._photos) {
            if (photo.location != null) {
                val marker = Marker(this._map)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.position = photo.location
                this._map.overlays.add(marker)
            }
        }
        if (photoWithLocation?.location != null) {
            this._map.controller.setZoom(10.0)
            this._map.controller.setCenter(photoWithLocation.location)
        }
        this._map.invalidate()  // Redraw the map to update the markers

        if (this._photos.size == 1) {
            this._changeLocationButton.visibility = View.VISIBLE
            this._deleteLocationButton.visibility =
                if (photoWithLocation?.location != null) View.VISIBLE else View.GONE
        }
        else {
            this._changeLocationButton.visibility = View.GONE
            this._deleteLocationButton.visibility = View.GONE
        }
        this._cancelChangeLocationButton.visibility = View.GONE

        this.updateCheckboxStates()
        this.updateDisabledStates()
    }

    /**
     * Updates the state of all checkboxes.
     */
    private fun updateCheckboxStates() {
        for ((client, checkbox) in this._checkboxes) {
            if (this._photos.all { client::class in it.handles }) {
                checkbox.checkedState = MaterialCheckBox.STATE_CHECKED
            }
            else if (this._photos.all { client::class !in it.handles }) {
                checkbox.checkedState = MaterialCheckBox.STATE_UNCHECKED
            }
            else {
                checkbox.checkedState = MaterialCheckBox.STATE_INDETERMINATE
            }
            val uploadText =
                if (client is LocalStorageClient) this.getString(R.string.downloadLocally)
                else this.getString(R.string.uploadTo) + " " + client.name
            checkbox.text = uploadText
        }
    }

    /**
     * Updates the disabled states of all UI elements.
     */
    private fun updateDisabledStates() {
        val enable = !this._photos.any { it in this._photosBeingEdited }
        this._rotateLeftButton.isEnabled = enable
        this._rotateRightButton.isEnabled = enable
        this._deleteButton.isEnabled = enable
        this._changeLocationButton.isEnabled = enable
        this._deleteLocationButton.isEnabled = enable
        this._cancelChangeLocationButton.isEnabled = enable
        for (checkbox in this._checkboxes.values) {
            checkbox.isEnabled = enable
        }
    }

    /**
     * Updates the backup state of all the selected photos with the given client.
     *
     * @param clients   The clients to update the backup state with.
     * @param upload    True if the photos should be uploaded, false if they should be deleted.
     */
    private suspend fun changeBackupState(clients: Set<StorageClient>, upload: Boolean) {
        val photos = this._photos.toSet()
        this._photosBeingEdited.addAll(photos)
        this.updateDisabledStates()

        // If deleting the photo from the last storage client, show a confirmation dialog
        val clientClasses = clients.map { it::class }
        if (photos.any { clientClasses.containsAll(it.handles.keys) }) {
            val proceed = AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(R.string.deleteConfirmation)
                .showAsync(R.string.yes, R.string.no)
            if (!proceed) {
                this._photosBeingEdited.removeAll(photos)
                this.updateUi()
                return
            }
        }

        // Update the backup state of all the photos concurrently
        try {
            for (client in clients) {
                photos.concurrentForEach(this, 10) { photo ->
                    if (upload) {
                        if (client::class !in photo.handles) {
                            client.save(photo)
                        }
                    }
                    else {
                        if (client::class in photo.handles) {
                            client.delete(photo)
                        }
                    }
                }
            }
            Toast.makeText(
                this,
                R.string.updatedSuccessfully,
                Toast.LENGTH_LONG
            ).show()
        }
        catch (e: Exception) {
            Log.w(this.javaClass.name, e.message, e)
            Toast.makeText(
                this,
                this.getString(R.string.failedToUpdate, e.message),
                Toast.LENGTH_LONG
            ).show()
        }

        // Update the UI
        this._photosBeingEdited.removeAll(photos)
        this.updateUi()
    }

    /**
     * Edits the selected photos by applying the given callback to each photo.
     *
     * @param callback  A callback to be called on each selected photo, returning the bytes of the new photo.
     */
    private suspend fun editPhotos(callback: suspend (Photo) -> ByteArray) {
        val photos = this._photos.toSet()
        val newPhotos = mutableSetOf<Photo>()
        this._photosBeingEdited.addAll(photos)
        this.updateDisabledStates()

        // Save the changes
        try {
            photos.concurrentForEach(this, 10) { photo ->
                val newBytes = callback(photo)
                val clients = this.storageClients().filter { it::class in photo.handles }
                for (client in clients) {
                    val newPhoto = client.overwrite(photo, newBytes)
                    this.togglePhotoSelected(newPhoto, updateUi = false)
                    this._photosBeingEdited.add(newPhoto)
                    newPhotos.add(newPhoto)
                }
            }
            Toast.makeText(
                this,
                R.string.updatedSuccessfully,
                Toast.LENGTH_LONG
            ).show()
        }
        catch (e: Exception) {
            Log.w(this.javaClass.name, e.message, e)
            Toast.makeText(
                this,
                this.getString(R.string.failedToUpdate, e.message),
                Toast.LENGTH_LONG
            ).show()
        }

        // Update the UI
        this._photosBeingEdited.removeAll(photos)
        this._photosBeingEdited.removeAll(newPhotos)
        this.updateUi()
    }

    /**
     * Opens the map to edit the location of the photo.
     */
    private fun startEditingLocation() {
        if (this._photos.all { it.location == null }) {
            this._map.controller.setZoom(2.0)
            this._map.controller.setCenter(GeoPoint(0.0, 0.0))
        }
        this._map.overlays.add(this._mapEventsOverlay)
        this._changeLocationButton.visibility = View.GONE
        this._deleteLocationButton.visibility = View.GONE
        this._cancelChangeLocationButton.visibility = View.VISIBLE

        @SuppressLint("ClickableViewAccessibility")
        this._map.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.parent.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        this._map.visibility = View.VISIBLE

        this._locationRow.text = this.getString(R.string.chooseLocation)
    }
}