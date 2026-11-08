package moe.antimony.hoshi.features.audio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Obtains a real SAF grant for opt-in instrumentation without using installed audio settings. */
class LocalAudioValidationActivity : ComponentActivity() {
    private val evidenceDirectory get() = cacheDir.resolve("local-audio-validation")
    private lateinit var status: TextView
    private var ownershipVerification: Job? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            status.text = "No database selected"
            return@registerForActivityResult
        }
        if (intent.getBooleanExtra("verifyOwnership", false)) {
            status.text = "Checking link authorization"
            ownershipVerification = lifecycleScope.launch {
                status.text = withContext(Dispatchers.IO) { verifyOwnership(uri) }
            }
            return@registerForActivityResult
        }
        status.text = try {
            val ownsGrant = evidenceDirectory.resolve("owned-grant-uri.txt")
            val alreadyGranted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            check(!ownsGrant.isFile || ownsGrant.readText() == uri.toString()) {
                "Release the previous fixture grant before selecting another document."
            }
            if (!alreadyGranted) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                evidenceDirectory.mkdirs()
                ownsGrant.writeText(uri.toString())
            }
            evidenceDirectory.mkdirs()
            evidenceDirectory.resolve("selected-uri.txt").writeText(uri.toString())
            "Selected $uri"
        } catch (failure: Exception) {
            "Unable to save fixture selection: ${failure.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("showImportOptions", false)) {
            setContent {
                MaterialTheme {
                    LocalAudioImportOptions(onDismiss = ::finish, onCopy = ::finish, onLink = ::finish)
                }
            }
            return
        }
        status = TextView(this).apply {
            val selected = evidenceDirectory.resolve("selected-uri.txt")
            text = if (selected.isFile) "Selected ${selected.readText()}" else "No database selected"
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(Button(context).apply {
                text = "Select fixture database"
                setOnClickListener { openPicker() }
            })
        })
        if (savedInstanceState == null && intent.getBooleanExtra("pick", true)) openPicker()
    }

    private suspend fun verifyOwnership(uri: Uri): String {
        val report = JSONObject().put("uri", uri.toString())
        val directory = evidenceDirectory.resolve("ownership")
        evidenceDirectory.mkdirs()
        return try {
            if (contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                report.put("result", "skipped").put("reason", "A persisted grant already exists")
                return "Skipped. This document already has a persisted grant."
            }
            check(directory.mkdir()) { "A previous ownership fixture exists" }
            val access = AndroidLocalAudioAccess(contentResolver)
            val repository = LocalAudioRepository(
                directory.resolve("files"), directory.resolve("state"), access, Dispatchers.IO,
            )
            val (audioFile, audio) = try {
                val linked = repository.importDatabase(uri, LocalAudioImportMode.Link)
                check(linked.isExternal)
                val hasGrant = hasPersistedReadGrant(uri)
                report.put("persistedReadAfterLink", hasGrant)
                check(hasGrant) { "Link did not take a persisted read grant" }
                val candidate = repository.findAudioCandidates("食べる", "たべる").firstOrNull()
                val file = candidate?.url?.let(LocalAudioResolver::parseAudioUrl)
                checkNotNull(file) { "No audio candidate for 食べる" }
                check(file.generation == linked.generation)
                val bytes = repository.loadAudio(file)
                check(bytes != null && bytes.isNotEmpty()) { "No audio bytes for 食べる" }
                file to bytes
            } finally {
                withContext(NonCancellable) {
                    try {
                        repository.deleteDatabase()
                    } finally {
                        check(directory.deleteRecursively()) { "Unable to clean ownership fixture" }
                    }
                }
            }
            val hasGrant = hasPersistedReadGrant(uri)
            report.put("persistedReadAfterUnlink", hasGrant)
            check(!hasGrant) { "Unlink did not release the persisted read grant" }
            access.openDocument(uri.toString()).use { reopened ->
                check(audio.contentEquals(reopened.loadAudio(audioFile.source, audioFile.file))) {
                    "Document audio changed after unlink"
                }
            }
            report.put("audioBytes", audio.size).put("result", "passed")
            "Link authorization verified"
        } catch (cancelled: CancellationException) {
            report.put("result", "cancelled")
            throw cancelled
        } catch (failure: Exception) {
            report.put("result", "failed").put("reason", failure.toString())
            "Link authorization check failed. ${failure.message}"
        } finally {
            withContext(NonCancellable) {
                evidenceDirectory.resolve("ownership-result.json").writeText(report.toString(2))
            }
        }
    }

    private fun hasPersistedReadGrant(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun openPicker() {
        if (ownershipVerification?.isActive == true) return
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            type = "*/*"
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"),
            )
        })
    }
}
