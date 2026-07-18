package io.github.gustavlindberg99.photos.activity

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import com.github.gustavlindberg99.androidsuspendutils.SuspendableLauncher
import com.github.gustavlindberg99.androidsuspendutils.setOnClickListenerAsync
import io.github.gustavlindberg99.photos.R
import io.github.gustavlindberg99.photos.photo.PhotoManager
import io.github.gustavlindberg99.photos.storage_client.GoogleDriveClient
import io.github.gustavlindberg99.photos.storage_client.OneDriveStorageClient
import io.github.gustavlindberg99.photos.storage_client.PCloudClient
import io.github.gustavlindberg99.photos.storage_client.StorageClient
import kotlin.collections.iterator
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance
import androidx.core.net.toUri
import androidx.core.util.TypedValueCompat.dpToPx
import io.github.gustavlindberg99.photos.BuildConfig

class SettingsActivity : AppCompatActivity() {
    private val _storageSwitchesLayout: LinearLayout by lazy { this.findViewById(R.id.SettingsActivity_storageSwitchesLayout) }

    private val _clientTypes = mapOf<KClass<out StorageClient>, suspend () -> Unit>(
        GoogleDriveClient::class to
        { GoogleDriveClient.authenticate(this, this._googleLogInLauncher) },
        OneDriveStorageClient::class to { OneDriveStorageClient.authenticate(this, true) },
        PCloudClient::class to { PCloudClient.authenticate(this, this._pCloudLogInLauncher) }
    )

    private val _googleLogInLauncher = SuspendableLauncher(
        this,
        ActivityResultContracts.StartIntentSenderForResult()
    )

    private val _pCloudLogInLauncher = SuspendableLauncher(
        this,
        ActivityResultContracts.StartActivityForResult()
    )

    companion object {
        public const val CLIENTS = "clients"
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()
        this.setContentView(R.layout.activity_settings)
        this.supportActionBar!!.elevation = 0f

        ViewCompat.setOnApplyWindowInsetsListener(this.findViewById(R.id.SettingsActivity_main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            return@setOnApplyWindowInsetsListener insets
        }

        val clients = this.intent.getStringArrayExtra(CLIENTS)
        for ((clientType, signIn) in this._clientTypes) {
            val companion = clientType.companionObjectInstance as StorageClient.Companion

            val title = TextView(this)
            title.text = companion.name(this)
            title.textSize = 24f
            this._storageSwitchesLayout.addView(title)

            val useSwitch = SwitchCompat(this)
            useSwitch.text = this.getString(R.string.use, companion.name(this))
            useSwitch.textSize = 18f
            useSwitch.isChecked = clients?.contains(clientType.qualifiedName!!) ?: false
            this._storageSwitchesLayout.addView(useSwitch)

            val autoUploadSwitch = SwitchCompat(this)
            autoUploadSwitch.text = this.getString(R.string.autoUpload, companion.name(this))
            autoUploadSwitch.textSize = 18f
            autoUploadSwitch.isEnabled = useSwitch.isChecked
            autoUploadSwitch.isChecked =
                this.getSharedPreferences(companion.PREFERENCES_KEY, Context.MODE_PRIVATE)
                    .getBoolean(StorageClient.Companion.AUTOMATIC_UPLOAD, false)
            this._storageSwitchesLayout.addView(autoUploadSwitch)

            val defaultFolderInput = EditText(this)
            defaultFolderInput.hint = this.getString(R.string.photosFolder, companion.name(this))
            defaultFolderInput.textSize = 18f
            defaultFolderInput.setText(companion.photosFolder(this))
            defaultFolderInput.isEnabled = useSwitch.isChecked
            this._storageSwitchesLayout.addView(defaultFolderInput)

            useSwitch.setOnClickListenerAsync {
                useSwitch.isEnabled = false
                autoUploadSwitch.isEnabled = false
                defaultFolderInput.isEnabled = false
                if (useSwitch.isChecked) {
                    try {
                        signIn()
                        autoUploadSwitch.isEnabled = true
                        defaultFolderInput.isEnabled = true
                    }
                    catch (e: Exception) {
                        Toast.makeText(
                            this,
                            getString(R.string.failedToLogIn, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                        useSwitch.isChecked = false
                    }
                }
                else {
                    companion.signOut(this)
                }
                useSwitch.isEnabled = true
            }

            autoUploadSwitch.setOnClickListenerAsync {
                val allSha1s = PhotoManager.allPhotos(this).map { it.sha1 }.toSet()
                this.getSharedPreferences(companion.PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    putBoolean(StorageClient.Companion.AUTOMATIC_UPLOAD, autoUploadSwitch.isChecked)
                    putStringSet(
                        StorageClient.Companion.IGNORED_PHOTOS_FOR_AUTOMATIC_UPLOAD,
                        allSha1s
                    )
                }
            }

            defaultFolderInput.doOnTextChanged { charSequence, _, _, _ ->
                val text = charSequence.toString()
                    .replace('\\', '/')
                    .trim()
                    .replace(Regex("""(\s/)*/+(\s/)*"""), "/")
                    .replace(Regex("/$"), "")
                    .replace(Regex("^/"), "")

                val disallowedCharacters = setOf('"', '*', ':', '<', '>', '?', '#', '%', '|')
                if (disallowedCharacters.any { it in text }) {
                    Toast.makeText(
                        this,
                        this.getString(
                            R.string.invalidFileName,
                            disallowedCharacters.joinToString("")
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@doOnTextChanged
                }

                val maxLength = 255
                if (text.length > maxLength) {
                    Toast.makeText(
                        this,
                        this.getString(R.string.fileNameTooLong, maxLength),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@doOnTextChanged
                }

                this.getSharedPreferences(companion.PREFERENCES_KEY, Context.MODE_PRIVATE).edit {
                    putString(StorageClient.Companion.PHOTOS_FOLDER, text)
                }
            }
        }

        // Initialize help, feedback and about buttons
        this.findViewById<Button>(R.id.SettingsActivity_helpButton).setOnClickListener {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                "https://github.com/GustavLindberg99/AndroidPhotos/blob/master/README.md".toUri()
            )
            startActivity(browserIntent)
        }
        this.findViewById<Button>(R.id.SettingsActivity_feedbackButton).setOnClickListener {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                "https://github.com/GustavLindberg99/AndroidPhotos/issues".toUri()
            )
            startActivity(browserIntent)
        }
        this.findViewById<Button>(R.id.SettingsActivity_aboutButton).setOnClickListener {
            val textView = TextView(this)
            textView.text = HtmlCompat.fromHtml(
                String.format(
                    this.getString(R.string.aboutString),
                    BuildConfig.VERSION_NAME,
                    "https://github.com/GustavLindberg99/AndroidPhotos",
                    "https://github.com/GustavLindberg99/AndroidPhotos/blob/master/LICENSE"
                ), HtmlCompat.FROM_HTML_MODE_LEGACY
            )
            textView.setTextColor(this.getColor(R.color.black))
            textView.setLinkTextColor(Color.BLUE)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val value = TypedValue()
            if (this.theme.resolveAttribute(
                    androidx.appcompat.R.attr.dialogPreferredPadding,
                    value,
                    true
                )
            ) {
                val padding = TypedValue.complexToDimensionPixelSize(
                    value.data,
                    this.resources.displayMetrics
                )
                textView.setPadding(
                    padding,
                    dpToPx(8f, this.resources.displayMetrics).toInt(),
                    padding,
                    0
                )
            }
            textView.movementMethod = LinkMovementMethod.getInstance()
            AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setView(textView)
                .setPositiveButton(R.string.ok, { _: DialogInterface?, _: Int -> })
                .create()
                .show()
        }
    }
}