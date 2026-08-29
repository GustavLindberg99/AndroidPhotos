package io.github.gustavlindberg99.photos.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.util.TypedValueCompat.dpToPx
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.async
import com.github.gustavlindberg99.androidsuspendutils.concurrentForEach
import com.github.gustavlindberg99.androidsuspendutils.launch
import com.github.gustavlindberg99.androidsuspendutils.setOnClickListenerAsync
import com.github.gustavlindberg99.androidsuspendutils.setOnItemSelectedAsync
import com.github.gustavlindberg99.androidsuspendutils.showAsync
import com.github.gustavlindberg99.androidsuspendutils.useWithContext
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.photo.Media
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.Video
import io.github.gustavlindberg99.photos.storage_client_utils.PhotoManager
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import io.github.gustavlindberg99.photos.storage_client_utils.UploadManager
import io.github.gustavlindberg99.photos.utils.addToStringSet
import io.github.gustavlindberg99.photos.utils.asTypeOrNull
import io.github.gustavlindberg99.photos.utils.initOsmdroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import okio.ByteString.Companion.toByteString
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.Collections
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
    private val _shareButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_shareButton) }
    private val _fileNameRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_fileNameRow) }
    private val _durationRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_durationRow) }
    private val _dateTimeRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_dateTimeRow) }
    private val _noTimezoneRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_noTimezoneRow) }
    private val _changeTimezoneButton: Spinner by lazy { this.findViewById(R.id.PropertiesActivity_changeTimezoneButton) }
    private val _locationRow: TextView by lazy { this.findViewById(R.id.PropertiesActivity_locationRow) }
    private val _map: MapView by lazy { this.findViewById(R.id.PropertiesActivity_map) }
    private val _changeLocationButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_changeLocationButton) }
    private val _deleteLocationButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_deleteLocationButton) }
    private val _cancelChangeLocationButton: ImageButton by lazy { this.findViewById(R.id.PropertiesActivity_cancelChangeLocationButton) }
    private val _storageCheckboxesLayout: LinearLayout by lazy { this.findViewById(R.id.PropertiesActivity_storageSwitchesLayout) }

    private val _storageCheckboxes = mutableMapOf<StorageClient, MaterialCheckBox>()

    private val _selectedPhotos = mutableSetOf<Media>()

    private var _getLocationJob: Job? = null

    private val _allTimezones = arrayOf(
        "-12:00", "-11:00", "-10:00", "-09:30", "-09:00", "-08:00", "-07:00", "-06:00",
        "-05:00", "-04:00", "-03:30", "-03:00", "-02:00", "-01:00", "+00:00", "+01:00",
        "+02:00", "+03:00", "+03:30", "+04:00", "+04:30", "+05:00", "+05:30", "+05:45",
        "+06:00", "+06:30", "+07:00", "+08:00", "+08:45", "+09:00", "+09:30", "+10:00",
        "+11:00", "+12:00", "+12:45", "+13:00", "+14:00"
    )

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
                    editPhotos(this@PropertiesActivity._selectedPhotos) {
                        it.edit(this@PropertiesActivity, location = point)
                    }
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
            this._selectedPhotos.remove(photo)
            this.updateUi()
        })

        this._rotateLeftButton.setOnClickListenerAsync {
            this.editPhotos(this._selectedPhotos) { it.edit(this, rotation = -90) }
        }
        this._rotateRightButton.setOnClickListenerAsync {
            this.editPhotos(this._selectedPhotos) { it.edit(this, rotation = 90) }
        }

        this._deleteButton.setOnClickListenerAsync {
            this.changeBackupState(this.storageClients(), false)
        }

        this._shareButton.setOnClickListenerAsync { this.share() }

        this._changeLocationButton.setOnClickListener { this.startEditingLocation() }
        this._deleteLocationButton.setOnClickListenerAsync {
            val proceed = AlertDialog.Builder(this)
                .setTitle(R.string.changeLocation)
                .setMessage(R.string.changeLocationConfirmation)
                .showAsync(R.string.yes, R.string.no)
            if (proceed) {
                this.editPhotos(this._selectedPhotos) { it.edit(this, location = null) }
            }
        }
        this._cancelChangeLocationButton.setOnClickListener { this.updateUi() }

        this._changeTimezoneButton.setOnItemSelectedAsync { _, _, position, _ ->
            val newTimezone =
                this._allTimezones.getOrNull(position) ?: return@setOnItemSelectedAsync
            val selectedPhotos = this._selectedPhotos.asTypeOrNull<Photo>()
            val oldTimezones = selectedPhotos?.map { it.timezone }?.toSet()
            if (oldTimezones?.size == 1) {
                val oldTimezone = oldTimezones.first()
                if (newTimezone != oldTimezone) {
                    this.editPhotos(selectedPhotos) { it.edit(this, timezone = newTimezone) }
                }
            }
        }

        // Load osmdroid configuration
        initOsmdroid(this)

        // Initialize map
        this._map.setMultiTouchControls(true)
    }

    public override fun onResume() {
        super.onResume()
        this._map.onResume()

        this.lifecycleScope.launch {
            // Update storage checkboxes only if no photo is selected, otherwise they will disappear and reappear if the user reopens the app while a photo is selected
            val storageClients = this.storageClients()
            if (this._selectedPhotos.isEmpty()) {
                this._storageCheckboxesLayout.removeAllViews()
                this._storageCheckboxes.clear()
                for (client in storageClients) {
                    val checkbox = MaterialCheckBox(this)
                    checkbox.textSize = 18f
                    checkbox.setOnClickListenerAsync {
                        this.changeBackupState(setOf(client), checkbox.isChecked)
                    }
                    this._storageCheckboxesLayout.addView(checkbox)
                    this._storageCheckboxes[client] = checkbox
                }
            }

            // Update the enabled state of the buttons
            this.updateUi()

            // Subscribe to upload state changes to update the UI
            UploadManager.setStateChangedListener(this::updateDisabledStates)
        }
    }

    public override fun onPause() {
        super.onPause()
        this._map.onPause()
        UploadManager.removeStateChangedListener(this::updateDisabledStates)
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
    public open fun togglePhotoSelected(photo: Media, updateUi: Boolean = true): Boolean {
        val result: Boolean
        if (this._selectedPhotos.contains(photo)) {
            this._selectedPhotos.remove(photo)
            result = false
        }
        else {
            this._selectedPhotos.add(photo)
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
        this._selectedPhotos.clear()
        this.updateUi()
    }

    /**
     * Gets the selected photos.
     *
     * @return The selected photos.
     */
    public fun selectedPhotos(): Set<Media> {
        return this._selectedPhotos
    }

    /**
     * Updates the UI to show the selected photos.
     */
    private fun updateUi() {
        if (this._selectedPhotos.isEmpty()) {
            this._bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            return
        }

        this._bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        // Update file name
        if (this._selectedPhotos.size == 1) {
            this._fileNameRow.text =
                this.getString(R.string.fileName, this._selectedPhotos.first().fileName)
        }
        else {
            this._fileNameRow.text = this.resources.getQuantityString(
                R.plurals.multipleSelected,
                this._selectedPhotos.size,
                this._selectedPhotos.size
            )
        }

        // Update duration
        if (this._selectedPhotos.size == 1) {
            val photo = this._selectedPhotos.first()
            if (photo is Video) {
                this._durationRow.visibility = View.VISIBLE
                this._durationRow.text = this.resources.getString(
                    R.string.duration,
                    photo.duration / 1000
                )
            }
            else {
                this._durationRow.visibility = View.GONE
            }
        }
        else {
            this._durationRow.visibility = View.GONE
        }

        // Update date
        // Reverse min and max because the Media class considers photos with a later date to be first, since that's the order they're shown in
        val minDate = this._selectedPhotos.max().dateTime
        val maxDate = this._selectedPhotos.min().dateTime
        if (minDate == maxDate) {
            this._dateTimeRow.text = this.getString(R.string.date, minDate.toString())
        }
        else {
            this._dateTimeRow.text = this.getString(R.string.date, "$minDate - $maxDate")
        }
        val hasTimezone = this._selectedPhotos.all { it !is Photo || it.hasTimezone }
        this._noTimezoneRow.isVisible = !hasTimezone

        // Update timezone
        val selectedPhotos = this._selectedPhotos.asTypeOrNull<Photo>()
        val timezones = selectedPhotos?.map { it.timezone }?.toSet()
        if (timezones?.size == 1) {
            val timezone = timezones.first()
            if (timezone == null) {
                this._changeTimezoneButton.visibility = View.VISIBLE
                this._changeTimezoneButton.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    this._allTimezones.map { "UTC$it" } + arrayOf(this.getString(R.string.chooseTimezone))
                )
                this._changeTimezoneButton.setSelection(this._allTimezones.size)
            }
            else {
                this._changeTimezoneButton.visibility = View.VISIBLE
                this._changeTimezoneButton.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    this._allTimezones.map { "UTC$it" }
                )
                this._changeTimezoneButton.setSelection(this._allTimezones.indexOf(timezone))
            }
        }
        else {
            this._changeTimezoneButton.visibility = View.GONE
        }

        // Update location text
        val photoWithLocation =
            this._selectedPhotos.firstOrNull { it.location != null }
                ?: this._selectedPhotos.firstOrNull()
        if (photoWithLocation?.location == null) {
            this._map.visibility = View.GONE
            this._locationRow.text =
                this.getString(R.string.location, this.getString(R.string.unknown))
        }
        else {
            val photosWithLocations = this._selectedPhotos.filter { it.location != null }
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

        // Update map
        this._map.overlays.clear()
        for (photo in this._selectedPhotos) {
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

        // Update change location buttons
        if (this._selectedPhotos.size == 1) {
            this._changeLocationButton.visibility = View.VISIBLE
            this._deleteLocationButton.visibility =
                if (photoWithLocation?.location != null) View.VISIBLE else View.GONE
        }
        else {
            this._changeLocationButton.visibility = View.GONE
            this._deleteLocationButton.visibility = View.GONE
        }
        this._cancelChangeLocationButton.visibility = View.GONE

        // Update which checkboxes are checked
        this.updateCheckboxStates()

        // Update the enabled state of the buttons
        this.lifecycleScope.launch {
            var globalEnable = true
            for (client in this.storageClients()) {
                val uploads =
                    UploadManager.currentUploads(client) + UploadManager.queuedUploads(client)
                val enable = Collections.disjoint(this._selectedPhotos, uploads)
                globalEnable = globalEnable && enable
                this.setCheckboxEnabled(client, enable)
            }
            this.setButtonsEnabled(globalEnable)
        }
    }

    /**
     * Updates the state of all checkboxes.
     */
    private fun updateCheckboxStates() {
        for ((client, checkbox) in this._storageCheckboxes) {
            if (this._selectedPhotos.all { it.handles.getHandle(client::class) != null }) {
                checkbox.checkedState = MaterialCheckBox.STATE_CHECKED
            }
            else if (this._selectedPhotos.all { it.handles.getHandle(client::class) == null }) {
                checkbox.checkedState = MaterialCheckBox.STATE_UNCHECKED
            }
            else {
                checkbox.checkedState = MaterialCheckBox.STATE_INDETERMINATE
            }
        }
    }

    /**
     * Updates the disabled states of all UI elements.
     *
     * @param photo     The photo to update the disabled states of.
     * @param client    The client to update the disabled states of.
     * @param state     The state to update the disabled states to.
     */
    private fun updateDisabledStates(photo: Media, client: StorageClient, state: Int) {
        if (photo in this._selectedPhotos) {
            val enable = state == UploadManager.FINISHED
            this.setButtonsEnabled(enable)
            this.setCheckboxEnabled(client, enable)
            if (state == UploadManager.FINISHED) {
                this.updateCheckboxStates()
            }
        }
    }

    /**
     * Sets the enabled state of the buttons that are common for all clients.
     *
     * @param enable    True if the buttons should be enabled, false if they should be disabled.
     */
    private fun setButtonsEnabled(enable: Boolean) {
        this._rotateLeftButton.isEnabled = enable
        this._rotateRightButton.isEnabled = enable
        this._deleteButton.isEnabled = enable
        this._shareButton.isEnabled = enable
        this._changeTimezoneButton.isEnabled = enable
        this._changeLocationButton.isEnabled = enable
        this._deleteLocationButton.isEnabled = enable
        this._cancelChangeLocationButton.isEnabled = enable
    }

    /**
     * Sets the enabled state of the checkbox for the given client.
     *
     * @param client    The client to set the enabled state of.
     * @param enable    True if the checkbox should be enabled, false if it should be disabled.
     */
    private fun setCheckboxEnabled(client: StorageClient, enable: Boolean) {
        val checkbox = this._storageCheckboxes[client]
        checkbox?.isEnabled = enable
        if (enable) {
            val uploadText =
                if (client is LocalStorageClient) this.getString(R.string.downloadLocally)
                else this.getString(R.string.uploadTo) + " " + client.name
            checkbox?.text = uploadText
        }
        else {
            checkbox?.text =
                if (client is LocalStorageClient) this.getString(R.string.downloading)
                else this.getString(R.string.uploading)
        }
    }

    /**
     * Updates the backup state of all the selected photos with the given client.
     *
     * @param clients   The clients to update the backup state with.
     * @param upload    True if the photos should be uploaded, false if they should be deleted.
     */
    private suspend fun changeBackupState(clients: Set<StorageClient>, upload: Boolean) {
        val photos = this._selectedPhotos.toSet()

        // If deleting the photo from the last storage client, show a confirmation dialog
        if (photos.any { photo -> photo.handles.isLastHandle(clients.map { it::class }) }) {
            val proceed = AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(R.string.deleteConfirmation)
                .showAsync(R.string.yes, R.string.no)
            if (!proceed) {
                this.updateUi()
                return
            }
        }

        // Update the backup state of all the photos concurrently. Run on UploadManager's coroutine so that it isn't canceled when the activity is destroyed.
        UploadManager.lifecycleScope.async {
            try {
                for (client in clients) {
                    photos.concurrentForEach(UploadManager) { photo ->
                        if (upload) {
                            if (photo.handles.getHandle(client::class) == null) {
                                UploadManager.save(client, photo)
                            }
                        }
                        else {
                            client.delete(photo)
                        }
                    }
                }
                Toast.makeText(
                    this,
                    this.resources.getQuantityString(R.plurals.updatedSuccessfully, photos.size),
                    Toast.LENGTH_LONG
                ).show()
            }
            catch (e: Exception) {
                Log.w(this.javaClass.name, e.message, e)
                Toast.makeText(
                    this,
                    this.resources.getQuantityString(
                        R.plurals.failedToUpdate,
                        photos.size,
                        e.message
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.await()

        // Update the UI
        this.updateUi()
    }

    /**
     * Opens a share dialog for the selected photos. Returns when the dialog is opened.
     */
    private suspend fun share() {
        // Copy the photos set to a local variable to avoid concurrent modification
        val photos = this._selectedPhotos.toSet()

        try {
            val sharedPhotosDir by lazy { this.sharedPhotosDir() }
            val uris: List<Uri> = photos.map { photo ->
                val localHandle = photo.handles.localStorageHandle
                if (localHandle != null) {
                    // Use existing local URI if possible
                    return@map localHandle.uri()
                }
                else {
                    // Download cloud-only photo to cache for sharing. The sha1 is needed for the names to be unique, and the fileName is needed for the extension to be correct (otherwise some apps will think it's raw binary data rather than a photo).
                    val tempFile = File(sharedPhotosDir, photo.sha1 + photo.fileName)
                    photo.getInputStream(this).useWithContext(Dispatchers.IO) { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    return@map FileProvider.getUriForFile(
                        this,
                        "${this.packageName}.fileprovider",
                        tempFile
                    )
                }
            }

            val mimeTypes = photos.map { it.mimeType }.toSet()
            val commonMimeType = when {
                mimeTypes.size == 1 -> mimeTypes.first()
                mimeTypes.all { it.startsWith("image/") } -> "image/*"
                mimeTypes.all { it.startsWith("video/") } -> "video/*"
                else -> "*/*"
            }

            val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
            else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.type = commonMimeType
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val chooser = Intent.createChooser(intent, this.getString(R.string.share))
            this.startActivity(chooser)
        }
        catch (e: Exception) {
            Log.e(this.javaClass.name, e.message, e)
            Toast.makeText(
                this,
                this.resources.getQuantityString(
                    R.plurals.shareFailed,
                    photos.size,
                    e.message
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Creates a temporary directory for sharing photos.
     *
     * @return The temporary directory.
     */
    private fun sharedPhotosDir(): File {
        val sharedPhotosDir = File(this.cacheDir, "shared_photos")
        // Clear old shared files to save space
        if (sharedPhotosDir.exists()) {
            sharedPhotosDir.deleteRecursively()
        }
        sharedPhotosDir.mkdirs()
        return sharedPhotosDir
    }

    /**
     * Edits the selected photos by applying the given callback to each photo.
     *
     * @param photos    The currently selected photos. Should be `this._selectedPhotos`, exists as a separate parameter to allow a smart cast version of it to a `Set<T>`.
     * @param callback  A callback to be called on each selected photo, returning the bytes of the new photo. The callback may return null to indicate that the photo should be skipped.
     */
    private suspend fun <T : Media> editPhotos(
        photos: Collection<T>,
        callback: suspend (T) -> ByteArray?
    ) {
        val newPhotos = mutableSetOf<Media>()

        // Save the changes. Run on UploadManager's coroutine so that it isn't canceled when the activity is destroyed.
        UploadManager.lifecycleScope.async {
            try {
                photos.concurrentForEach(UploadManager) { photo ->
                    val newBytes = callback(photo) ?: return@concurrentForEach

                    // Add the new SHA1 to the auto-upload ignore list if the old SHA1 is there
                    for (client in this.storageClients()) {
                        val preferences =
                            UploadManager.autoUploadPreferences(this, client) ?: continue
                        val ignoreList = preferences.getStringSet(
                            StorageClient.Companion.IGNORED_PHOTOS_FOR_AUTOMATIC_UPLOAD,
                            null
                        )?.toSet() ?: emptySet()
                        if (photo.sha1 in ignoreList) {
                            val newSha1 = newBytes.toByteString().sha1().hex()
                            preferences.addToStringSet(
                                StorageClient.Companion.IGNORED_PHOTOS_FOR_AUTOMATIC_UPLOAD,
                                newSha1
                            )
                        }
                    }

                    // Upload the photo
                    val clients =
                        this.storageClients().filter { photo.handles.getHandle(it::class) != null }
                    clients.concurrentForEach(UploadManager) { client ->
                        val newPhoto = UploadManager.overwrite(client, photo, newBytes)
                        this.togglePhotoSelected(newPhoto, updateUi = false)
                        newPhotos.add(newPhoto)
                    }
                }
                Toast.makeText(
                    this,
                    this.resources.getQuantityString(R.plurals.updatedSuccessfully, photos.size),
                    Toast.LENGTH_LONG
                ).show()
            }
            catch (e: Exception) {
                Log.w(this.javaClass.name, e.message, e)
                Toast.makeText(
                    this,
                    this.resources.getQuantityString(
                        R.plurals.failedToUpdate,
                        photos.size,
                        e.message
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.await()

        // Update the UI
        this.updateUi()
    }

    /**
     * Opens the map to edit the location of the photo.
     */
    private fun startEditingLocation() {
        if (this._selectedPhotos.all { it.location == null }) {
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