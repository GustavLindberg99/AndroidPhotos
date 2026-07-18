package io.github.gustavlindberg99.photos

import io.github.gustavlindberg99.photos.storage_client_utils.PhotosFolderManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PhotosFolderManagerTest(private val photosFolder: String, private val parents: Set<String>) {
    companion object {
        @Parameterized.Parameters
        @JvmStatic
        fun data(): Collection<Array<Any>> = listOf(
            arrayOf("Photos", setOf("Photos")),
            arrayOf("Photos/Test1", setOf("Photos", "Photos/Test1")),
            arrayOf("Photos/Test1/Test2", setOf("Photos", "Photos/Test1", "Photos/Test1/Test2"))
        )
    }

    private val allFolders = mutableSetOf<String>()

    private val photosFolderManager = object : PhotosFolderManager<String>({ photosFolder }) {
        protected override suspend fun getSubFolders(parent: String?): List<String> {
            if (parent == null) {
                return allFolders.filter { !it.contains('/') }
            }
            else {
                return allFolders.filter { path ->
                    path.startsWith("$parent/") && path.count { it == '/' } == parent.count { it == '/' } + 1
                }
            }
        }

        protected override suspend fun createFolder(parent: String?, name: String): String {
            if (parent == null) {
                allFolders.add(name)
                return name
            }
            else {
                allFolders.add("$parent/$name")
                return "$parent/$name"
            }
        }

        protected override fun fileName(file: String): String = file.split('/').last()
    }

    @Before
    fun resetAllFolders() {
        this.allFolders.clear()
    }

    @Test
    fun getPhotosFolderNull() = runTest {
        allFolders.add("Unrelated")
        allFolders.add("Unrelated/Folder")
        if (photosFolder == "") {
            assertEquals(photosFolderManager.getPhotosFolder(), "")
        }
        else {
            assertNull(photosFolderManager.getPhotosFolder())
        }
    }

    @Test
    fun getPhotosFolderNonNull() = runTest {
        for (parent in parents) {
            allFolders.add(parent)
        }
        assertEquals(photosFolderManager.getPhotosFolder(), photosFolder)
    }

    @Test
    fun createPhotosFolder() = runTest {
        allFolders.add("Unrelated")
        assertNull(photosFolderManager.getPhotosFolder())
        assertEquals(photosFolderManager.createPhotosFolder(), photosFolder)
        assertEquals(photosFolderManager.getPhotosFolder(), photosFolder)
        assertEquals(allFolders, setOf("Unrelated") + parents)
    }
}