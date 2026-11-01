package com.gsmautoanswer.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.gsmautoanswer.app.AutoAnswerApp
import com.gsmautoanswer.app.AppGraph
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.audio.InjectionCapability
import com.gsmautoanswer.app.data.LiveTestResult
import com.gsmautoanswer.app.diagnostics.SetupChecks
import com.gsmautoanswer.app.ui.AudioSettingsActivity
import com.gsmautoanswer.app.ui.CallFlowSettingsActivity
import com.gsmautoanswer.app.ui.MainActivity
import com.gsmautoanswer.app.ui.SimSelectionActivity
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var graph: AppGraph
    private lateinit var masterSwitch: MaterialSwitch
    private lateinit var masterStatus: TextView
    private lateinit var warning: TextView
    private lateinit var setupBanner: View
    private lateinit var setupSubtitle: TextView
    private lateinit var tileCheck: View
    private lateinit var tileAudio: View
    private lateinit var tileSim: View
    private lateinit var tileFlow: View

    private var capability: InjectionCapability? = null
    private var probeJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        graph = (requireActivity().application as AutoAnswerApp).graph

        masterSwitch = view.findViewById(R.id.master_switch)
        masterStatus = view.findViewById(R.id.home_master_status)
        warning = view.findViewById(R.id.home_warning)
        setupBanner = view.findViewById(R.id.home_setup_banner)
        setupSubtitle = view.findViewById(R.id.home_setup_subtitle)
        tileCheck = view.findViewById(R.id.tile_check)
        tileAudio = view.findViewById(R.id.tile_audio)
        tileSim = view.findViewById(R.id.tile_sim)
        tileFlow = view.findViewById(R.id.tile_flow)

        masterSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != graph.settings.current.autoAnswerEnabled) {
                graph.settings.setAutoAnswerEnabled(checked)
                render()
            }
        }
        setupBanner.setOnClickListener {
            (requireActivity() as MainActivity).selectTab(R.id.nav_settings)
        }
        tileCheck.setOnClickListener {
            (requireActivity() as MainActivity).selectTab(R.id.nav_check)
        }
        tileAudio.setOnClickListener {
            startActivity(Intent(requireContext(), AudioSettingsActivity::class.java))
        }
        tileSim.setOnClickListener {
            startActivity(Intent(requireContext(), SimSelectionActivity::class.java))
        }
        tileFlow.setOnClickListener {
            startActivity(Intent(requireContext(), CallFlowSettingsActivity::class.java))
        }

        render()
        probeCapability()
    }

    override fun onResume() {
        super.onResume()
        render()
        probeCapability()
    }

    private fun render() {
        val settings = graph.settings.current
        val roleReady = SetupChecks.isDefaultDialer(requireContext())
        val notificationsReady = SetupChecks.notificationsAllowed(requireContext())
        val batteryReady = SetupChecks.isBatteryExempt(requireContext())
        val audioReady = !settings.greetingUri.isNullOrBlank() && !settings.holdUri.isNullOrBlank()
        val liveVerified = settings.liveTestResult == LiveTestResult.PASSED &&
            settings.liveTestStartedAtMs > 0L &&
            settings.liveTestRouteVerified
        val injectionReady = liveVerified ||
            capability?.isReadyForAutoAnswer() == true

        if (masterSwitch.isChecked != settings.autoAnswerEnabled) {
            masterSwitch.isChecked = settings.autoAnswerEnabled
        }
        masterStatus.setText(
            if (settings.autoAnswerEnabled) R.string.home_master_on else R.string.home_master_off,
        )

        val setupMissing = listOf(roleReady, notificationsReady, batteryReady, audioReady)
            .count { !it }
        setupBanner.isVisible = setupMissing > 0
        if (setupMissing > 0) {
            setupSubtitle.text = resources.getQuantityString(
                R.plurals.home_setup_tasks,
                setupMissing,
                setupMissing,
            )
        }

        val basicReady = roleReady && notificationsReady && audioReady
        val showWarning = settings.autoAnswerEnabled && (!basicReady || !injectionReady)
        warning.isVisible = showWarning
        if (showWarning) {
            if (basicReady && !injectionReady) {
                warning.setText(R.string.home_audio_unverified_warning)
                warning.setOnClickListener {
                    (requireActivity() as MainActivity).selectTab(R.id.nav_check)
                }
            } else {
                warning.setText(R.string.home_setup_warning)
                warning.setOnClickListener {
                    (requireActivity() as MainActivity).selectTab(R.id.nav_settings)
                }
            }
        }

        val checkValue = when {
            settings.isLiveTestArmed -> getString(R.string.home_check_armed)
            liveVerified -> getString(R.string.home_check_passed)
            settings.liveTestResult == LiveTestResult.FAILED -> getString(R.string.home_check_failed)
            else -> getString(R.string.home_check_not_tested)
        }
        setTile(
            tileCheck,
            R.drawable.ic_nav_check,
            getString(R.string.home_tile_check),
            checkValue,
        )
        setTile(
            tileAudio,
            R.drawable.ic_music,
            getString(R.string.home_tile_audio),
            getString(if (audioReady) R.string.home_audio_ready else R.string.home_audio_missing),
        )
        setTile(
            tileSim,
            R.drawable.ic_phone,
            getString(R.string.home_tile_sim),
            if (settings.usesSpecificSim) {
                settings.simLabel ?: getString(R.string.home_sim_all)
            } else {
                getString(R.string.home_sim_all)
            },
        )
        setTile(
            tileFlow,
            R.drawable.ic_flow,
            getString(R.string.home_tile_flow),
            getString(R.string.home_flow_value, settings.countdownSeconds),
        )
    }

    private fun setTile(root: View, iconRes: Int, label: String, value: String) {
        root.findViewById<ImageView>(R.id.quick_icon).setImageResource(iconRes)
        root.findViewById<TextView>(R.id.quick_label).text = label
        root.findViewById<TextView>(R.id.quick_value).text = value
        root.contentDescription = "$label: $value"
    }

    private fun probeCapability() {
        if (probeJob?.isActive == true) return
        probeJob = viewLifecycleOwner.lifecycleScope.launch {
            capability = runCatching { graph.capabilityProbe.probe() }
                .onFailure { graph.log.warn("Capability probe failed: ${it.message}") }
                .getOrNull()
            if (isAdded) render()
        }
    }
}
