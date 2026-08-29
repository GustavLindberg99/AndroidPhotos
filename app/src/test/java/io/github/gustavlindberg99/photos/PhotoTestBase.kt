package io.github.gustavlindberg99.photos

import androidx.core.net.toUri
import io.github.gustavlindberg99.photos.file_handle.GoogleDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.HandleList
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.photo.Photo
import org.junit.Before

abstract class PhotoTestBase {
    lateinit var photo1: Photo
    lateinit var photo2: Photo
    lateinit var photo3: Photo
    lateinit var photo4: Photo
    lateinit var photo5: Photo
    lateinit var photo6: Photo
    lateinit var photo7: Photo
    lateinit var photo8: Photo
    lateinit var photo9: Photo
    lateinit var photo10: Photo
    lateinit var photo11: Photo
    lateinit var photo12: Photo
    lateinit var photo13: Photo

    @Before
    fun resetTestPhotos() {
        photo1 = Photo(
            "photo1.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "a28f27cc77d046a6ba430d7b45814799",
            "2020:01:01 10:00:00",
            "+01:00",
            HandleList(localStorageHandle = UriHandle("file://fake-filesystem/photo1.jpg".toUri()))
        )

        photo2 = Photo(
            "photo2.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "a28f27cc77d046a6ba430d7b45814799", // Same SHA1 as photo1
            "2020:01:01 10:00:00",
            "+01:00",
            HandleList(googleDriveHandle = GoogleDriveFileHandle("id2"))
        )

        photo3 = Photo(
            "photo3.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "db8c193a59f10d390f84e75dff8dc85e",
            "2020:01:01 10:00:00",
            "+01:00",
            HandleList(googleDriveHandle = GoogleDriveFileHandle("id3"))
        )

        photo4 = Photo(
            "photo4.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "e0bbe8f44ee84a953fee219201d8e11d",
            "2020:01:01 09:00:00",
            "-07:00",
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo4.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id4")
            )
        )

        photo5 = Photo(
            "photo5.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "cd8be7c57b778ce54a5c0cfc2a3a866c",
            "2020:01:01 09:00:00+",
            "01:00",
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo5.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id5")
            )
        )

        photo6 = Photo(
            "photo6.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "01d367fae1fb1d24e05c6c05e7c5c002",
            null,
            null,
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo6.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id6")
            )
        )

        photo7 = Photo(
            "photo7.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "29b2dd541f46415a90d9909379141cc0",
            null,
            null,
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo7.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id7")
            )
        )

        photo8 = Photo(
            "photo8.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "a65d608eb64ceb8df0f52daf4faba04d",
            null,
            null,
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo8.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id8")
            )
        )

        photo9 = Photo(
            "photo9.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "c7ee2bddf0adcf67d0ee077e4e295436",
            null,
            null,
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo9.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id9")
            )
        )

        photo10 = Photo(
            "photo10.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "2f69c8aa1168b478e09cbdf68313683d",
            null,
            null,
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo10.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id10")
            )
        )

        photo11 = Photo(
            "photo11.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "66e3c472e4f521c6ad94342229b5fe95",
            null,
            null,
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo11.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id11")
            )
        )

        photo12 = Photo(
            "photo12.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "8e96ac7441415b4c6b1db92e65355751",
            null,
            null,
            HandleList(
                localStorageHandle = UriHandle("file://fake-filesystem/photo12.jpg".toUri()),
                googleDriveHandle = GoogleDriveFileHandle("id12")
            )
        )

        photo13 = Photo(
            "photo13.jpg",
            "image/jpeg",
            128,
            256,
            0,
            null,
            "8e96ac7441415b4c6b1db92e65355751", // Same SHA1 as photo12
            null,
            null,
            HandleList(localStorageHandle = UriHandle("file://fake-filesystem/photo13.jpg".toUri()))
        )
    }
}