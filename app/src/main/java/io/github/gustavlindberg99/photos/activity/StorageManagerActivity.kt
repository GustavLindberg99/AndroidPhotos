package io.github.gustavlindberg99.photos.activity

import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.gustavlindberg99.androidsuspendutils.SuspendableLauncher
import com.github.gustavlindberg99.androidsuspendutils.async
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import io.github.gustavlindberg99.photos.storage_client.LocalStorageClient
import io.github.gustavlindberg99.photos.storage_client.OneDriveStorageClient
import io.github.gustavlindberg99.photos.storage_client.PCloudClient
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

/**
 * Base class for activities that use storage clients.
 */
abstract class StorageManagerActivity : AppCompatActivity() {
    private val _requestPermissionLauncher = SuspendableLauncher(
        this,
        ActivityResultContracts.RequestPermission(),
    )

    private val _intentSenderLauncher = SuspendableLauncher(
        this,
        ActivityResultContracts.StartIntentSenderForResult()
    )

    private var _promises: List<Deferred<StorageClient?>>? = null

    /**
     * Checks again which storage clients are available.
     */
    public fun resetStorageClients() {
        this._promises = this.createClients()
    }

    /**
     * Gets the available storage clients.
     */
    public suspend fun storageClients(): Set<StorageClient> {
        val promises = this._promises ?: this.createClients()
        this._promises = promises
        return promises.mapNotNull { promise ->
            try {
                promise.await()
            }
            catch (_: CancellationException) {
                // The job was canceled, so we can ignore this exception
                null
            }
            catch (e: Exception) {
                Log.w(this.javaClass.name, e.message, e)
                Toast.makeText(
                    this,
                    this.getString(R.string.failedToLogIn, e.message),
                    Toast.LENGTH_LONG
                ).show()
                null
            }
        }.toSet()
    }

    /**
     * Starts the authentication process for each storage client.
     *
     * @return A list of promises that will be resolved when the authentication process is complete.
     */
    private fun createClients(): List<Deferred<StorageClient?>> {
        return listOf(
            this.lifecycleScope.async {
                LocalStorageClient.authenticate(
                    this,
                    this._requestPermissionLauncher,
                    this._intentSenderLauncher
                )
            },
            this.lifecycleScope.async {
                GoogleDriveClient.authenticate(this, null)
            },
            this.lifecycleScope.async {
                OneDriveStorageClient.authenticate(this, false)
            },
            this.lifecycleScope.async {
                PCloudClient.authenticate(this, null)
            }
        )
    }
}