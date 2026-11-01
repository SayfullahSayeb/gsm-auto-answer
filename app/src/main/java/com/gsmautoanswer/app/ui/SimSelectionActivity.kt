package com.gsmautoanswer.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.gsmautoanswer.app.AutoAnswerApp
import com.gsmautoanswer.app.AppGraph
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.data.AppSettings
import com.gsmautoanswer.app.util.SystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.radiobutton.MaterialRadioButton

class SimSelectionActivity : AppCompatActivity() {

    private lateinit var graph: AppGraph
    private lateinit var permissionCard: MaterialCardView
    private lateinit var permissionButton: MaterialButton
    private lateinit var options: LinearLayout
    private lateinit var status: TextView
    private lateinit var refresh: MaterialButton

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { loadSubscriptions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph = (application as AutoAnswerApp).graph
        setContentView(R.layout.activity_sim_selection)
        SystemBars.apply(findViewById(R.id.sim_root))

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        permissionCard = findViewById(R.id.sim_permission_card)
        permissionButton = findViewById(R.id.sim_permission_button)
        options = findViewById(R.id.sim_options)
        status = findViewById(R.id.sim_status)
        refresh = findViewById(R.id.sim_refresh_button)

        permissionButton.setOnClickListener { requestPhonePermission() }
        refresh.setOnClickListener {
            if (hasPhonePermission()) loadSubscriptions() else requestPhonePermission()
        }
        loadSubscriptions()
    }

    override fun onResume() {
        super.onResume()
        loadSubscriptions()
    }

    @SuppressLint("MissingPermission")
    private fun loadSubscriptions() {
        options.removeAllViews()
        val permitted = hasPhonePermission()
        refresh.isVisible = permitted
        if (!permitted) {
            permissionCard.isVisible = true
            status.isVisible = false
            return
        }
        permissionCard.isVisible = false

        val subscriptions = runCatching {
            getSystemService(SubscriptionManager::class.java)
                .activeSubscriptionInfoList
                .orEmpty()
                .sortedBy { it.simSlotIndex }
        }.getOrElse {
            Toast.makeText(this, it.message ?: getString(R.string.sim_empty), Toast.LENGTH_LONG)
                .show()
            emptyList()
        }

        addOption(
            subscriptionId = AppSettings.ALL_SIMS,
            title = getString(R.string.sim_all_title),
            description = getString(R.string.sim_all_description),
        )
        subscriptions.forEach { addSimOption(it) }

        if (subscriptions.isEmpty()) {
            addMessage(getString(R.string.sim_empty))
        }

        val settings = graph.settings.current
        val savedMissing = settings.usesSpecificSim &&
            subscriptions.none { it.subscriptionId == settings.simSubscriptionId }
        status.isVisible = savedMissing
        if (savedMissing) status.text = getString(R.string.sim_stale)

        if (settings.usesSpecificSim) refreshSelection(settings.simSubscriptionId)
    }

    private fun addSimOption(info: SubscriptionInfo) {
        val slot = info.simSlotIndex + 1
        val carrier = info.carrierName?.toString()
            ?.takeIf { it.isNotBlank() && !it.equals("Cellular", ignoreCase = true) }
            ?: getString(R.string.sim_unknown_carrier)
        val displayName = info.displayName?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.sim_unknown_name)
        addOption(
            subscriptionId = info.subscriptionId,
            title = getString(R.string.sim_option_title, slot, carrier),
            description = getString(
                R.string.sim_option_description,
                displayName,
                slot,
            ),
        )
    }

    private fun addOption(subscriptionId: Int, title: String, description: String) {
        val view = layoutInflater.inflate(R.layout.item_sim_option, options, false)
        val card = view.findViewById<MaterialCardView>(R.id.sim_option_card)
        val radio = view.findViewById<MaterialRadioButton>(R.id.sim_option_radio)
        view.findViewById<TextView>(R.id.sim_option_title).text = title
        view.findViewById<TextView>(R.id.sim_option_desc).text = description
        card.tag = subscriptionId
        radio.isChecked = graph.settings.current.simSubscriptionId == subscriptionId
        card.setOnClickListener {
            val label = if (subscriptionId == AppSettings.ALL_SIMS) null else title
            graph.settings.setSimSelection(subscriptionId, label)
            refreshSelection(subscriptionId)
            Toast.makeText(this, R.string.sim_saved, Toast.LENGTH_SHORT).show()
        }
        options.addView(
            view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun addMessage(text: String) {
        val message = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@SimSelectionActivity, R.color.text_muted))
            textSize = 14f
            setLineSpacing(0f, 1.3f)
            setPadding(4, 28, 4, 8)
        }
        options.addView(message)
    }

    private fun refreshSelection(subscriptionId: Int) {
        for (index in 0 until options.childCount) {
            val card = options.getChildAt(index) as? MaterialCardView ?: continue
            val radio = card.findViewById<MaterialRadioButton>(R.id.sim_option_radio)
            radio.isChecked = card.tag == subscriptionId
        }
    }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPhonePermission() {
        permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }
}
