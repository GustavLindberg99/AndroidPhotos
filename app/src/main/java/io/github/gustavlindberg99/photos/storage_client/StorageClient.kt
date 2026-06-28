package io.github.gustavlindberg99.photos.storage_client

import android.app.Activity
import android.content.Context
import io.github.gustavlindberg99.photos.file_handle.FileHandle
import io.github.gustavlindberg99.photos.photo.Photo
import kotlinx.coroutines.flow.Flow

interface StorageClient {
    public override fun equals(other: Any?): Boolean
    public override fun hashCode(): Int

    /**
     * The name of the storage service.
     */
    public val name: String

    /**
     * Get all photos from the storage client as a flow (not as a list as that would cause performance issues, even for local photos).
     *
     * @return A flow of all photos.
     *
     * @throws Exception If the photos could not be fetched.
     */
    public fun getAllPhotos(): Flow<Photo>

    /**
     * Get all possible photo handles from the storage client. Some handles might not correspond to valid photos (they might correspond to folders or non-photo files), but all valid photos are guaranteed to be present. Used to invalidate cached photos that have been deleted.
     *
     * @return A list of all photo handles.
     *
     * @throws Exception If the handles could not be fetched.
     */
    public suspend fun allPhotoHandles(): Set<FileHandle>

    /**
     * Save a photo to the storage client.
     *
     * @param photo The photo to save. Adds the new URI to the photo.
     *
     * @throws Exception If the photo could not be saved.
     */
    public suspend fun save(photo: Photo)

    /**
     * Overwrite a photo in the storage client. Assumes that the old and new photo both have the same MIME type.
     *
     * @param oldPhoto  The photo to overwrite. Removes the URI for this client from this photo (since it has been overwritten).
     * @param newBytes  The bytes to write.
     *
     * @return The new photo.
     *
     * @throws Exception If the photo could not be overwritten.
     */
    public suspend fun overwrite(oldPhoto: Photo, newBytes: ByteArray): Photo

    /**
     * Delete a photo from the storage client.
     *
     * @param photo The photo to delete. Removes the URI for this client from this photo.
     *
     * @throws Exception If the photo could not be deleted.
     */
    public suspend fun delete(photo: Photo)

    /**
     * Base interface for storage client companion objects.
     */
    public interface Companion {
        companion object {
            public const val AUTOMATIC_UPLOAD = "automaticUpload"
            public const val IGNORED_PHOTOS_FOR_AUTOMATIC_UPLOAD = "ignoredPhotosForAutomaticUpload"
            public const val PHOTOS_FOLDER = "photosFolder"
        }

        public val PREFERENCES_KEY: String
        public val DEFAULT_FOLDER: String

        /**
         * The name of the storage service.
         *
         * @param context   The context of the application.
         *
         * @return The name of the storage service.
         */
        public fun name(context: Context): String

        /**
         * Signs out from the storage client.
         *
         * @param context   The context of the application.
         */
        public suspend fun signOut(context: Activity)

        /**
         * Gets the folder in which photos are stored.
         *
         * @param context   The context of the application.
         *
         * @return The folder in which photos are stored.
         */
        public fun photosFolder(context: Context): String {
            return context
                .getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
                .getString(PHOTOS_FOLDER, null) ?: DEFAULT_FOLDER
        }
    }
}