package io.github.gustavlindberg99.photos.activity

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.photo.Video
import io.github.gustavlindberg99.photos.storage_client_utils.PhotoManager

class PhotoActivity : PropertiesActivity() {
    private val _viewPager: ViewPager2 by lazy { this.findViewById(R.id.PhotoActivity_viewPager) }

    companion object {
        public const val PHOTO_INDEX = "photoIndex"

        private const val PHOTO_TYPE = 0
        private const val VIDEO_TYPE = 1
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

    private inner class PhotoPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private inner class PhotoViewHolder(val photoView: PhotoView) :
            RecyclerView.ViewHolder(photoView)

        private inner class VideoViewHolder(val playerView: PlayerView) :
            RecyclerView.ViewHolder(playerView)

        public override fun getItemViewType(position: Int): Int {
            when (PhotoManager.photoFromIndex(position)) {
                is Photo -> return PHOTO_TYPE
                is Video -> return VIDEO_TYPE
            }
        }

        public override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RecyclerView.ViewHolder {
            when (viewType) {
                PHOTO_TYPE -> {
                    val photoView = PhotoView(parent.context)
                    photoView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    return PhotoViewHolder(photoView)
                }

                VIDEO_TYPE -> {
                    val playerView = PlayerView(parent.context)
                    playerView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    return VideoViewHolder(playerView)
                }

                else -> throw IllegalArgumentException("Unknown view type: $viewType")
            }
        }

        public override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            try {
                val photo = PhotoManager.photoFromIndex(position)
                if (photo is Photo && holder is PhotoViewHolder) {
                    photo.showOnView(this@PhotoActivity, holder.photoView)
                }
                else if (photo is Video && holder is VideoViewHolder) {
                    photo.setupPlayer(this@PhotoActivity, holder.playerView)
                }
                else {
                    throw IllegalArgumentException("Illegal combination of view type and media type: ${photo::class.qualifiedName}, ${holder::class.qualifiedName}")
                }
                holder.itemView.setOnLongClickListener {
                    val photo = PhotoManager.photoFromIndex(position)
                    this@PhotoActivity.togglePhotoSelected(photo)
                    return@setOnLongClickListener true
                }
            }
            catch (e: Exception) {
                Log.w(this.javaClass.name, e.message, e)

                // Assume the photo has been deleted, and close the activity
                this@PhotoActivity.finish()
            }
        }

        public override fun getItemCount(): Int {
            return PhotoManager.numberOfPhotos()
        }

        public override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            if (holder is VideoViewHolder) {
                holder.playerView.player?.release()
                holder.playerView.player = null
            }
        }
    }
}