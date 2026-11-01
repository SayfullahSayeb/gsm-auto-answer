package com.gsmautoanswer.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.util.SystemBars
import com.google.android.material.button.MaterialButton

class DialerActivity : AppCompatActivity() {

    private lateinit var dialNumber: TextView
    private lateinit var dialHint: TextView

    private var number = ""
    private var pendingCall: String? = null

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingCall
        pendingCall = null
        if (granted && pending != null) {
            placeCall(pending)
        } else if (!granted) {
            Toast.makeText(this, R.string.dialer_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_dialer)
        SystemBars.apply(findViewById(R.id.dialer_root))

        dialNumber = findViewById(R.id.dial_number)
        dialHint = findViewById(R.id.dial_hint)

        findViewById<MaterialButton>(R.id.setup_button).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.dial_call).setOnClickListener { dial() }
        findViewById<android.widget.ImageButton>(R.id.dial_backspace).setOnClickListener {
            deleteLast()
        }
        findViewById<android.widget.ImageButton>(R.id.dial_backspace).setOnLongClickListener {
            number = ""
            renderNumber()
            true
        }

        wireKeypad()

        intent?.data?.let { data ->
            if (data.scheme == "tel") {
                number = Uri.decode(data.schemeSpecificPart ?: "").trim()
                renderNumber()
            }
        }
    }

    private fun wireKeypad() {
        val keypad = findViewById<LinearLayout>(R.id.keypad)
        for (rowIndex in 0 until keypad.childCount) {
            val row = keypad.getChildAt(rowIndex) as? LinearLayout ?: continue
            for (keyIndex in 0 until row.childCount) {
                val key = row.getChildAt(keyIndex) as? MaterialButton ?: continue
                key.setOnClickListener {
                    if (number.length < MAX_NUMBER_LENGTH) {
                        number += key.text?.toString().orEmpty()
                        renderNumber()
                    }
                }
            }
        }
    }

    private fun deleteLast() {
        if (number.isNotEmpty()) {
            number = number.dropLast(1)
            renderNumber()
        }
    }

    private fun renderNumber() {
        dialNumber.text = number
        dialHint.isVisible = number.isEmpty()
    }

    private fun dial() {
        val current = number.trim()
        if (current.isEmpty()) {
            Toast.makeText(this, R.string.dialer_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingCall = current
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }
        placeCall(current)
    }

    private fun placeCall(target: String) {
        val uri = Uri.fromParts("tel", target, null)
        val intent = Intent(Intent.ACTION_CALL, uri)
        runCatching { startActivity(intent) }.onFailure {
            val message = if (it is SecurityException) {
                R.string.dialer_permission_needed
            } else {
                R.string.dialer_call_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val MAX_NUMBER_LENGTH = 30
    }
}
