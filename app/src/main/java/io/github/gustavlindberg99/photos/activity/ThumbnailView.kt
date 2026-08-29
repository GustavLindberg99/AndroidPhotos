package io.github.gustavlindberg99.photos.activity

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.flexbox.FlexboxLayoutManager
import io.github.gustavlindberg99.photos.R
import androidx.core.view.isVisible
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.launch
import io.github.gustavlindberg99.photos.photo.Media
import io.github.gustavlindberg99.photos.photo.Video
import io.github.gustavlindberg99.photos.storage_client_utils.PhotoManager
import io.github.gustavlindberg99.photos.storage_client_utils.UploadManager
import kotlinx.coroutines.Job
import kotlin.math.max

class ThumbnailView(
    context: Context,
    attrSet: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrSet, defStyleAttr) {
    private val _thumbnail: ImageView by lazy { this.findViewById(R.id.ThumbnailView_thumbnail) }
    private val _selectedMarker: ImageView by lazy { this.findViewById(R.id.ThumbnailView_selectedMarker) }
    private val _uploadedMarker: ImageView by lazy { this.findViewById(R.id.ThumbnailView_uploadedMarker) }
    private val _uploadingMarker: ImageView by lazy { this.findViewById(R.id.ThumbnailView_uploadingMarker) }
    private val _failedMarker: ImageView by lazy { this.findViewById(R.id.ThumbnailView_failedMarker) }
    private val _videoMarker: ImageView by lazy { this.findViewById(R.id.ThumbnailView_videoMarker) }

    private var _loadJob: Job? = null
    private var _photo: Media? = null

    init {
        View.inflate(context, R.layout.view_thumbnail, this)
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        this._loadJob?.cancel()
    }

    /**
     * True if the file is selected, false otherwise.
     */
    public var photoSelected: Boolean
        get() = this._selectedMarker.isVisible
        set(value) {
            this._selectedMarker.changeVisibility(value)
            this.updateCloudStatus()
        }

    /**
     * Updates the cloud icon based on the current state of the photo.
     */
    public fun updateCloudStatus() {
        val photo = PhotoManager.getUpdated(this._photo ?: return)
        this._photo = photo

        val isUploading = UploadManager.isUploading(photo) || UploadManager.isQueued(photo)
        val failed = UploadManager.hasFailed(photo)

        // Hide the cloud icon if the photo is selected because it's in the same place as the selected icon and if it's selected we can see the detailed cloud status anyway
        this._uploadedMarker.changeVisibility(
            !this.photoSelected &&
            !isUploading &&
            !failed &&
            photo.handles.isBackedUp()
        )

        this._uploadingMarker.changeVisibility(!this.photoSelected && isUploading)
        this._failedMarker.changeVisibility(!this.photoSelected && failed)
    }

    /**
     * Sets the thumbnail of the photo. This can't be done in the constructor because the SDK expects the constructor to have a specific signature.
     *
     * @param photo The photo to set the thumbnail of.
     */
    public fun setPhoto(photo: Media) {
        if (photo == this._photo) {
            return
        }
        this._photo = photo

        val context = this.context as StorageManagerActivity
        this._loadJob?.cancel()
        this._loadJob = context.lifecycleScope.launch {
            this._thumbnail.setImageBitmap(photo.getThumbnail(context))
        }

        val windowInsets = ViewCompat.getRootWindowInsets(this)
            ?: ViewCompat.getRootWindowInsets(context.window.decorView)!! // Can only be null on API 20 and below
        val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val insetsWidth = systemBars.left + systemBars.right
        val availableWidth = this.resources.displayMetrics.widthPixels - insetsWidth

        val minThumbnailWidth = 300
        // Integer division takes care of rounding
        val numberOfColumns = max(availableWidth / minThumbnailWidth, 1)
        val width = availableWidth / numberOfColumns - this.marginLeft - this.marginRight
        val height = photo.height * width / photo.width

        val lp = (this.layoutParams as? FlexboxLayoutManager.LayoutParams)
            ?: FlexboxLayoutManager.LayoutParams(width, height)
        if (this.layoutParams == null || lp.width != width || lp.height != height) {
            lp.width = width
            lp.height = height
            this.layoutParams = lp
        }

        val imageLp = this._thumbnail.layoutParams ?: FrameLayout.LayoutParams(width, height)
        if (this._thumbnail.layoutParams == null || imageLp.width != width || imageLp.height != height) {
            imageLp.width = width
            imageLp.height = height
            this._thumbnail.layoutParams = imageLp
        }

        this._videoMarker.changeVisibility(photo is Video)
        this.updateCloudStatus()
    }

    companion object {
        /**
         * Sets the visibility of the view, making it invisible instead of gone if it shouldn't be visible (unlike the built-in `isVisible`). This is needed to avoid strange errors due to recycler views.
         *
         * @param visible True if the view should be visible, false otherwise.
         */
        private fun View.changeVisibility(visible: Boolean) {
            val newVisibility = if (visible) View.VISIBLE else View.INVISIBLE
            if (newVisibility != this.visibility) {
                this.visibility = newVisibility
            }
        }
    }
}