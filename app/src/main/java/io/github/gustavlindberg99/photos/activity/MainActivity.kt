package io.github.gustavlindberg99.photos.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.gustavlindberg99.androidsuspendutils.SuspendableLauncher
import com.github.gustavlindberg99.androidsuspendutils.concurrentForEach
import com.github.gustavlindberg99.androidsuspendutils.launch
import com.github.gustavlindberg99.androidsuspendutils.setOnClickListenerAsync
import com.github.gustavlindberg99.androidsuspendutils.setOnLongClickListenerAsync
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.PhotoManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : PropertiesActivity() {
    // RecyclerView will only keep currently visible thumbnails in memory, which significantly improves performance
    private val _recyclerView: RecyclerView by lazy { this.findViewById(R.id.MainActivity_recyclerView) }
    private val _mapButton: ImageButton by lazy { this.findViewById(R.id.MainActivity_mapButton) }
    private val _uploadsButton: ImageButton by lazy { this.findViewById(R.id.MainActivity_uploadsButton) }
    private val _settingsButton: ImageButton by lazy { this.findViewById(R.id.MainActivity_settingsButton) }
    private val _photoAdapter: PhotoAdapter by lazy { PhotoAdapter() }
    private val _photosInLayout = sortedSetOf<Photo>()
    private var _syncJob: Job? = null
    private var _updateJob: Job? = null

    private val _activityLauncher = SuspendableLauncher(
        this,
        ActivityResultContracts.StartActivityForResult()
    )

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()
        this.setContentView(R.layout.activity_main)

        val layoutManager = object : FlexboxLayoutManager(this) {
            override fun onLayoutChildren(
                recycler: RecyclerView.Recycler,
                state: RecyclerView.State
            ) {
                try {
                    super.onLayoutChildren(recycler, state)
                }
                // These exceptions can be thrown due to a bug in the SDK, so ignore them
                catch (_: IndexOutOfBoundsException) {
                }
                catch (_: IllegalArgumentException) {
                }
            }
        }
        layoutManager.flexDirection = FlexDirection.ROW
        layoutManager.flexWrap = FlexWrap.WRAP
        layoutManager.justifyContent = JustifyContent.FLEX_START
        this._recyclerView.layoutManager = layoutManager
        this._recyclerView.itemAnimator = null
        this._recyclerView.adapter = this._photoAdapter

        ViewCompat.setOnApplyWindowInsetsListener(this.findViewById(R.id.MainActivity_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            return@setOnApplyWindowInsetsListener insets
        }

        PhotoManager.setPhotoAddedListener(this, this::addPhotoToLayout)
        PhotoManager.setPhotoRemovedListener(this, this::removePhotoFromLayout)

        this._settingsButton.setOnClickListenerAsync {
            val intent = Intent(this, SettingsActivity::class.java)
            val oldClients = this.storageClients()
            intent.putExtra(
                SettingsActivity.CLIENTS,
                oldClients.map { it::class.qualifiedName!! }.toTypedArray()
            )
            this._activityLauncher.launch(intent)

            this.deselectAllPhotos()
            this.resetStorageClients()
            val newClients = this.storageClients()

            val removedClients = oldClients - newClients
            for (client in removedClients) {
                PhotoManager.removeClient(this, client::class)
            }
        }

        this._mapButton.setOnClickListenerAsync {
            val intent = Intent(this, MapActivity::class.java)
            this.startActivity(intent)
        }

        this._uploadsButton.setOnClickListenerAsync {
            val intent = Intent(this, UploadsActivity::class.java)
            this.startActivity(intent)
        }

        this.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedPhotos().isEmpty()) {
                    finish()
                }
                else {
                    deselectAllPhotos()
                }
            }
        })
    }

    public override fun onResume() {
        super.onResume()

        this._syncJob?.cancel()
        this._syncJob = this.lifecycleScope.launch {
            this.storageClients().concurrentForEach(this) { client ->
                try {
                    PhotoManager.syncPhotos(this, client)
                }
                catch (_: CancellationException) {
                    // The job was canceled, so we can ignore this exception
                }
                catch (e: Exception) {
                    Log.w(this.javaClass.name, e.message, e)
                    Toast.makeText(
                        this,
                        this.getString(R.string.failedToFetch, client.name, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    public override fun togglePhotoSelected(photo: Photo, updateUi: Boolean): Boolean {
        val selected = super.togglePhotoSelected(photo, updateUi)
        val index = this._photosInLayout.indexOf(photo)
        if (index != -1) {
            this._photoAdapter.notifyItemChanged(index)
        }
        return selected
    }

    public override fun deselectAllPhotos() {
        super.deselectAllPhotos()
        _photoAdapter.notifyItemRangeChanged(0, this._photosInLayout.size)
    }

    /**
     * Adds a photo to the layout.
     *
     * @param photo The photo to add.
     */
    private fun addPhotoToLayout(photo: Photo) {
        if (this._photosInLayout.add(photo)) {
            this.updatePhotosInLayout()
        }
    }

    /**
     * Removes a photo from the layout.
     *
     * @param photo The photo to remove.
     */
    private fun removePhotoFromLayout(photo: Photo) {
        if (this._photosInLayout.remove(photo)) {
            this.updatePhotosInLayout()
        }
    }

    /**
     * Updates the layout to show the photos in `this._photosInLayout`. Will not necessarily do it immediately, allowing to batch updates to avoid flickering and performance issues during mass additions or deletions.
     */
    private fun updatePhotosInLayout() {
        val updateJob = this._updateJob
        if (updateJob == null || !updateJob.isActive) {
            this._updateJob = this.lifecycleScope.launch {
                delay(100.milliseconds)
                this._photoAdapter.submitList(this._photosInLayout.toList())
            }
        }
    }

    /**
     * Adapter for showing a photo in a RecyclerView.
     */
    private inner class PhotoAdapter :
        ListAdapter<Photo, PhotoAdapter.ViewHolder>(object : DiffUtil.ItemCallback<Photo>() {
            override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean {
                return oldItem.sha1 == newItem.sha1
            }

            override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean {
                return oldItem.sha1 == newItem.sha1
            }
        }) {
        public inner class ViewHolder(val thumbnailView: ThumbnailView) :
            RecyclerView.ViewHolder(thumbnailView)

        public override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val thumbnailView = ThumbnailView(parent.context)
            return ViewHolder(thumbnailView)
        }

        public override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val photo = this.getItem(position)
            holder.thumbnailView.setPhoto(photo)
            holder.thumbnailView.photoSelected = this@MainActivity.selectedPhotos().contains(photo)

            holder.thumbnailView.setOnClickListenerAsync {
                val updatedPhoto = PhotoManager.getUpdated(this@MainActivity, photo)
                if (this@MainActivity.selectedPhotos().isEmpty()) {
                    try {
                        val intent = Intent(this@MainActivity, PhotoActivity::class.java)
                        val index = PhotoManager.indexFromPhoto(updatedPhoto)
                        intent.putExtra(PhotoActivity.PHOTO_INDEX, index)
                        this@MainActivity.startActivity(intent)
                    }
                    catch (e: Exception) {
                        Log.w(this.javaClass.name, e.message, e)
                        Toast.makeText(
                            this@MainActivity,
                            this@MainActivity.getString(R.string.couldNotViewPhoto),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                else {
                    this@MainActivity.togglePhotoSelected(updatedPhoto)
                }
            }

            holder.thumbnailView.setOnLongClickListenerAsync {
                val updatedPhoto = PhotoManager.getUpdated(this@MainActivity, photo)
                this@MainActivity.togglePhotoSelected(updatedPhoto)
            }
        }
    }
}