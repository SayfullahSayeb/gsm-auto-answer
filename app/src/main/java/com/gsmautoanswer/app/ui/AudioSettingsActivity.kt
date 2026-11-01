package com.gsmautoanswer.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.gsmautoanswer.app.AutoAnswerApp
import com.gsmautoanswer.app.AppGraph
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.util.SystemBars
import com.gsmautoanswer.app.util.formatDuration
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioSettingsActivity : AppCompatActivity() {

    private lateinit var graph: AppGraph

    private lateinit var greetingName: TextView
    private lateinit var greetingMeta: TextView
    private lateinit var greetingProgress: LinearProgressIndicator
    private lateinit var greetingButton: MaterialButton

    private lateinit var holdName: TextView
    private lateinit var holdMeta: TextView
    private lateinit var holdProgress: LinearProgressIndicator
    private lateinit var holdButton: MaterialButton

    private lateinit var requiredNote: TextView

    private val greetingPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { decodeAndSave(Target.GREETING, it) } }

    private val holdPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { decodeAndSave(Target.HOLD, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph = (application as AutoAnswerApp).graph
        setContentView(R.layout.activity_audio_settings)
        SystemBars.apply(findViewById(R.id.audio_root))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        greetingName = findViewById(R.id.greeting_name)
        greetingMeta = findViewById(R.id.greeting_meta)
        greetingProgress = findViewById(R.id.greeting_progress)
        greetingButton = findViewById(R.id.greeting_button)

        holdName = findViewById(R.id.hold_name)
        holdMeta = findViewById(R.id.hold_meta)
        holdProgress = findViewById(R.id.hold_progress)
        holdButton = findViewById(R.id.hold_button)

        requiredNote = findViewById(R.id.audio_required_note)

        greetingProgress.max = 100
        holdProgress.max = 100

        greetingButton.setOnClickListener { launchPicker(greetingPicker) }
        holdButton.setOnClickListener { launchPicker(holdPicker) }

        renderAll()
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    private fun launchPicker(launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
        runCatching { launcher.launch(arrayOf("audio/*")) }
            .onFailure { toast(getString(R.string.audio_screen_pick_failed)) }
    }

    private fun decodeAndSave(target: Target, uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        setBusy(target, true)
        lifecycleScope.launch {
            try {
                val cached = graph.audioCache.ensure(uri.toString()) { progress ->
                    runOnUiThread {
                        if (!isDestroyed) {
                            val indicator = progressFor(target)
                            indicator.isIndeterminate = false
                            indicator.setProgressCompat((progress * 100).toInt(), true)
                        }
                    }
                }
                if (target == Target.GREETING) {
                    graph.settings.setGreetingUri(uri.toString())
                } else {
                    graph.settings.setHoldUri(uri.toString())
                }
                graph.log.info("Saved ${target.name.lowercase()} audio: ${cached.displayName}")
                setBusy(target, false)
                renderAll()
                deleteUnusedCaches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isDestroyed) return@launch
                setBusy(target, false)
                renderAll()
                toast(getString(R.string.decode_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun renderAll() {
        render(Target.GREETING)
        render(Target.HOLD)
        val settings = graph.settings.current
        requiredNote.isVisible =
            settings.greetingUri.isNullOrBlank() || settings.holdUri.isNullOrBlank()
    }

    private fun render(target: Target) {
        val settings = graph.settings.current
        val uri = if (target == Target.GREETING) settings.greetingUri else settings.holdUri
        val nameView = nameFor(target)
        val metaView = metaFor(target)
        val button = buttonFor(target)

        if (uri.isNullOrBlank()) {
            nameView.setText(R.string.file_not_selected)
            metaView.text = ""
            button.setText(R.string.choose_file)
            return
        }

        val cached = graph.audioCache.cachedFileFor(uri)
        nameView.text = cached?.displayName ?: queryDisplayName(uri)
        metaView.text = if (cached != null) {
            getString(R.string.decoded_audio, formatDuration(cached.durationMs), "16 kHz mono")
        } else {
            getString(R.string.audio_not_prepared)
        }
        button.setText(R.string.replace_file)
    }

    private fun setBusy(target: Target, busy: Boolean) {
        val indicator = progressFor(target)
        val button = buttonFor(target)
        val nameView = nameFor(target)
        val metaView = metaFor(target)
        indicator.isVisible = busy
        indicator.isIndeterminate = false
        if (busy) {
            indicator.setProgressCompat(0, false)
            nameView.setText(R.string.decoding_audio)
            metaView.text = ""
        }
        button.isEnabled = !busy
    }

    private fun queryDisplayName(uriString: String): String {
        val uri = Uri.parse(uriString)
        var name: String? = null
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    name = cursor.getString(0)
                }
            }
        }
        return name ?: Uri.decode(uri.lastPathSegment ?: getString(R.string.file_not_selected))
    }

    private fun deleteUnusedCaches() {
        val settings = graph.settings.current
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                graph.audioCache.deleteUnusedCaches(
                    listOf(settings.greetingUri, settings.holdUri),
                )
            }
        }
    }

    private fun nameFor(target: Target): TextView =
        if (target == Target.GREETING) greetingName else holdName

    private fun metaFor(target: Target): TextView =
        if (target == Target.GREETING) greetingMeta else holdMeta

    private fun progressFor(target: Target): LinearProgressIndicator =
        if (target == Target.GREETING) greetingProgress else holdProgress

    private fun buttonFor(target: Target): MaterialButton =
        if (target == Target.GREETING) greetingButton else holdButton

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private enum class Target { GREETING, HOLD }
}
