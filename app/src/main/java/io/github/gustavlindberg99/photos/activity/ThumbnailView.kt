package io.github.gustavlindberg99.photos.activity

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.flexbox.FlexboxLayoutManager
import io.github.gustavlindberg99.photos.R
import androidx.core.view.isVisible
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.launch
import io.github.gustavlindberg99.photos.photo.Photo
import kotlin.math.max

class ThumbnailView(
    context: Context,
    attrSet: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrSet, defStyleAttr) {
    private val _thumbnail: ImageView by lazy { this.findViewById(R.id.ThumbnailView_thumbnail) }
    private val _selectedMarker: ImageView by lazy { this.findViewById(R.id.ThumbnailView_selectedMarker) }

    init {
        View.inflate(context, R.layout.view_thumbnail, this)
    }

    /**
     * True if the file is selected, false otherwise.
     */
    public var photoSelected: Boolean
        get() = this._selectedMarker.isVisible
        set(value) {
            this._selectedMarker.isVisible = value
        }

    /**
     * Sets the thumbnail of the photo. This can't be done in the constructor because the SDK expects the constructor to have a specific signature.
     *
     * @param photo The photo to set the thumbnail of.
     */
    public fun setPhoto(photo: Photo) {
        (this.context as ComponentActivity).lifecycleScope.launch {
            this._thumbnail.setImageBitmap(photo.getThumbnail(context))
        }

        val windowInsets = ViewCompat.getRootWindowInsets(this)
            ?: ViewCompat.getRootWindowInsets((this.context as Activity).window.decorView)!! // Can only be null on API 20 and below
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
        lp.width = width
        lp.height = height
        this.layoutParams = lp

        val imageLp = this._thumbnail.layoutParams ?: FrameLayout.LayoutParams(width, height)
        imageLp.width = width
        imageLp.height = height
        this._thumbnail.layoutParams = imageLp
    }
}