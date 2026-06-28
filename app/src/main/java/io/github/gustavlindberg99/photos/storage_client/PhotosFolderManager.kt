package io.github.gustavlindberg99.photos.storage_client

import com.github.gustavlindberg99.androidsuspendutils.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Class for creating and getting the photos folder for ID-based storage clients that don't handle subfolders natively (OneDrive and Google Drive). Does not support the case where the Photos folder is the root folder.
 *
 * @param _getPhotosFolderPath  A function that returns the path to the Photos folder.
 *
 * @tparam FileType The type used to represent files in the API library.
 */
abstract class PhotosFolderManager<FileType>(private val _getPhotosFolderPath: () -> String) {
    /**
     * Gets the direct subfolders in the given folder.
     *
     * @param parent    The folder to get the subfolders from. Null for the root folder.
     *
     * @return The direct subfolders in the given folder.
     */
    protected abstract suspend fun getSubFolders(parent: FileType?): List<FileType>

    /**
     * Creates the folder with the given name.
     *
     * @param parent    The folder to create a subfolder in. Null for the root folder.
     * @param name      The name of the folder. Should not contain any slashes.
     *
     * @return The newly created folder.
     */
    protected abstract suspend fun createFolder(parent: FileType?, name: String): FileType

    /**
     * Gets the name of the file.
     *
     * @param file  The file.
     *
     * @return The name of the file.
     */
    protected abstract fun fileName(file: FileType): String

    /**
     * Gets the Photos folder as configured by the user.
     *
     * @return The Photos folder, or null if it doesn't exist.
     */
    public suspend fun getPhotosFolder(): FileType? {
        return this.getRecursiveFolder(null, this._getPhotosFolderPath())
    }

    /**
     * Creates the Photos folder as configured by the user.
     *
     * @return The newly created Photos folder.
     */
    public suspend fun createPhotosFolder(): FileType {
        return this.createRecursiveFolder(null, this._getPhotosFolderPath())
    }

    /**
     * Gets the folder with the given path in the folder with the given ID. If no parameters are specified, this will be the Photos folder configured by the user.
     *
     * @param parent    The parent folder. Null for the root folder.
     * @param name      The name of the folder.
     *
     * @return The folder, or null if it doesn't exist.
     */
    private suspend fun getRecursiveFolder(
        parent: FileType?,
        name: String
    ): FileType? = withContext(Dispatchers.IO) {
        val allSubFolders = this.getSubFolders(parent)
        if (name.contains('/')) {
            val topFolderName = name.split('/').first()
            val topFolder = allSubFolders.firstOrNull { this.fileName(it) == topFolderName }
                ?: return@withContext null
            return@withContext this.getRecursiveFolder(
                topFolder,
                name.removePrefix("$topFolderName/")
            )
        }
        else {
            return@withContext allSubFolders.firstOrNull { this.fileName(it) == name }
        }
    }

    /**
     * Creates the folder with the given path in the folder with the given ID. If no parameters are specified, this will be the Photos folder configured by the user.
     *
     * @param parent    The parent folder. Null for the root folder.
     * @param name      The name of the folder.
     *
     * @return The newly created folder.
     */
    private suspend fun createRecursiveFolder(
        parent: FileType?,
        name: String
    ): FileType = withContext(Dispatchers.IO) {
        if (name.contains('/')) {
            val topFolderName = name.split('/').first()
            val subFolderPath = name.removePrefix("$topFolderName/")
            val topFolder = this.getRecursiveFolder(parent, topFolderName)
                ?: this.createFolder(parent, topFolderName)
            return@withContext this.createRecursiveFolder(topFolder, subFolderPath)
        }
        else {
            return@withContext this.createFolder(parent, name)
        }
    }
}