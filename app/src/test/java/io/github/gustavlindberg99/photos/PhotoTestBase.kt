package io.github.gustavlindberg99.photos

import androidx.core.net.toUri
import io.github.gustavlindberg99.photos.file_handle.GoogleDriveFileHandle
import io.github.gustavlindberg99.photos.file_handle.UriHandle
import io.github.gustavlindberg99.photos.photo.Photo
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import org.junit.Before

abstract class PhotoTestBase {
    lateinit var photo1: Photo
    lateinit var photo2: Photo
    lateinit var photo3: Photo
    lateinit var photo4: Photo
    lateinit var photo5: Photo

    @Before
    fun resetTestPhotos() {
        photo1 = Photo(
            "photo1.jpg",
            "image/jpeg",
            128,
            256,
            null,
            "a28f27cc77d046a6ba430d7b45814799",
            "2020:01:01 10:00:00",
            "+01:00",
            "file://fake-filesystem/thumbnails/photo1.jpg".toUri(),
            mutableMapOf(LocalStorageClient::class to UriHandle("file://fake-filesystem/photo1.jpg".toUri()))
        )

        photo2 = Photo(
            "photo2.jpg",
            "image/jpeg",
            128,
            256,
            null,
            "a28f27cc77d046a6ba430d7b45814799",
            "2020:01:01 10:00:00",
            "+01:00",
            "file://fake-filesystem/thumbnails/photo2.jpg".toUri(),
            mutableMapOf(GoogleDriveClient::class to GoogleDriveFileHandle("id2"))
        )

        photo3 = Photo(
            "photo3.jpg",
            "image/jpeg",
            128,
            256,
            null,
            "db8c193a59f10d390f84e75dff8dc85e",
            "2020:01:01 10:00:00",
            "+01:00",
            "file://fake-filesystem/thumbnails/photo3.jpg".toUri(),
            mutableMapOf(GoogleDriveClient::class to GoogleDriveFileHandle("id3"))
        )

        photo4 = Photo(
            "photo4.jpg",
            "image/jpeg",
            128,
            256,
            null,
            "e0bbe8f44ee84a953fee219201d8e11d",
            "2020:01:01 09:00:00",
            "-07:00",
            "file://fake-filesystem/thumbnails/photo4.jpg".toUri(),
            mutableMapOf(
                LocalStorageClient::class to UriHandle("file://fake-filesystem/photo4.jpg".toUri()),
                GoogleDriveClient::class to GoogleDriveFileHandle("id4")
            )
        )

        photo5 = Photo(
            "photo4.jpg",
            "image/jpeg",
            128,
            256,
            null,
            "cd8be7c57b778ce54a5c0cfc2a3a866c",
            "2020:01:01 09:00:00+",
            "01:00",
            "file://fake-filesystem/thumbnails/photo4.jpg".toUri(),
            mutableMapOf(
                LocalStorageClient::class to UriHandle("file://fake-filesystem/photo5.jpg".toUri()),
                GoogleDriveClient::class to GoogleDriveFileHandle("id5")
            )
        )
    }
}