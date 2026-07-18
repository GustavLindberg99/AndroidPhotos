package io.github.gustavlindberg99.photos.activity

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.drawable.toDrawable
import androidx.core.util.TypedValueCompat.dpToPx
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.launch
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import io.github.gustavlindberg99.photos.storage_client_utils.UploadManager

class UploadsActivity : StorageManagerActivity() {
    private val _listLayout: LinearLayout by lazy { this.findViewById(R.id.UploadsActivity_listLayout) }
    private val _views = mutableMapOf<Photo, TextView>()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()
        this.setContentView(R.layout.activity_uploads)
        this.supportActionBar!!.elevation = 0f

        ViewCompat.setOnApplyWindowInsetsListener(this.findViewById(R.id.UploadsActivity_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            return@setOnApplyWindowInsetsListener insets
        }

        this.lifecycleScope.launch {
            for (client in this.storageClients()) {
                for (photo in UploadManager.currentUploads(client)) {
                    this.updateUploads(photo, client, UploadManager.UploadState.UPLOADING)
                }
                for (photo in UploadManager.queuedUploads(client)) {
                    this.updateUploads(photo, client, UploadManager.UploadState.QUEUED)
                }
            }

            UploadManager.setStateChangedListener(this::updateUploads)
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        UploadManager.removeStateChangedListener(this::updateUploads)
    }

    /**
     * Updates the list of uploads.
     *
     * @param photo     The photo that was uploaded.
     * @param client    The storage client that was used to upload the photo.
     * @param state     The state of the upload.
     */
    private fun updateUploads(
        photo: Photo,
        client: StorageClient,
        state: UploadManager.UploadState
    ) {
        if (state == UploadManager.UploadState.FINISHED) {
            val view = this._views.remove(photo)
            val parent = view?.parent as? ViewGroup
            parent?.removeView(view)
        }
        else {
            val existingView = this._views[photo]
            val text =
                if (state == UploadManager.UploadState.QUEUED) this.getString(
                    R.string.photoQueued,
                    photo.fileName,
                    client.name
                )
                else this.getString(
                    R.string.photoUploading,
                    photo.fileName,
                    client.name
                )

            if (existingView != null) {
                existingView.text = text
            }
            else {
                val textView = TextView(this)
                textView.text = text
                textView.textSize = 16f
                textView.compoundDrawablePadding =
                    dpToPx(4f, this.resources.displayMetrics).toInt()
                this.lifecycleScope.launch {
                    val thumbnail = photo.getThumbnail(this).toDrawable(this.resources)
                    val aspectRatio = photo.width.toFloat() / photo.height.toFloat()
                    val height = dpToPx(32f, this.resources.displayMetrics)
                    val width = height * aspectRatio
                    thumbnail.setBounds(0, 0, width.toInt(), height.toInt())
                    textView.setCompoundDrawables(thumbnail, null, null, null)
                }
                this._listLayout.addView(textView)
                this._views[photo] = textView
            }
        }
    }
}