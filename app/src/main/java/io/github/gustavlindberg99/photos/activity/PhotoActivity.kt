package io.github.gustavlindberg99.photos.activity

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.photo.PhotoManager

class PhotoActivity : PropertiesActivity() {
    private val _viewPager: ViewPager2 by lazy { this.findViewById(R.id.PhotoActivity_viewPager) }

    companion object {
        public const val PHOTO_INDEX = "photoIndex"
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()

        this.setContentView(R.layout.activity_photo)

        val index = intent.getIntExtra(PHOTO_INDEX, -1)
        if (index == -1) {
            Toast.makeText(this, R.string.couldNotViewPhoto, Toast.LENGTH_LONG).show()
            this.finish()
            return
        }

        this._viewPager.adapter = PhotoPagerAdapter()
        this._viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                this@PhotoActivity.deselectAllPhotos()
            }
        })
        this._viewPager.setCurrentItem(index, false)

        // If a photo is deleted while the PhotoActivity is open, it's probably the one we're viewing, so just close the activity
        PhotoManager.setPhotoRemovedListener(this, {
            this.finish()
        })
    }

    private inner class PhotoPagerAdapter : RecyclerView.Adapter<PhotoPagerAdapter.ViewHolder>() {
        public inner class ViewHolder(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView)

        public override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val photoView = PhotoView(parent.context)
            photoView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            return ViewHolder(photoView)
        }

        public override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            PhotoManager.photoFromIndex(position).showOnView(this@PhotoActivity, holder.photoView)
            holder.photoView.setOnLongClickListener {
                val photo = PhotoManager.photoFromIndex(position)
                this@PhotoActivity.togglePhotoSelected(photo)
                return@setOnLongClickListener true
            }
        }

        public override fun getItemCount(): Int {
            return PhotoManager.numberOfPhotos()
        }
    }
}