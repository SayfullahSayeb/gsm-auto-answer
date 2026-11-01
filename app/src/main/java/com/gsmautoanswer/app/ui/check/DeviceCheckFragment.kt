package com.gsmautoanswer.app.ui.check

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gsmautoanswer.app.AutoAnswerApp
import com.gsmautoanswer.app.AppGraph
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.compat.CompatReport
import com.gsmautoanswer.app.compat.CompatVerdict
import com.gsmautoanswer.app.data.LiveTestResult
import com.gsmautoanswer.app.diagnostics.SetupChecks
import com.gsmautoanswer.app.shizuku.ShizukuState
import com.gsmautoanswer.app.ui.SimSelectionActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceCheckFragment : Fragment(R.layout.fragment_device_check) {

    private lateinit var graph: AppGraph
    private lateinit var verdict: TextView
    private lateinit var verdictSummary: TextView
    private lateinit var verdictDot: View
    private lateinit var device: TextView
    private lateinit var reportText: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var copyButton: MaterialButton
    private lateinit var shareButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuAction: MaterialButton
    private lateinit var shizukuOutput: TextView
    private lateinit var liveStatus: TextView
    private lateinit var liveArm: MaterialButton
    private lateinit var liveResultContainer: View
    private lateinit var liveYes: MaterialButton
    private lateinit var liveNo: MaterialButton
    private lateinit var liveSimValue: TextView
    private lateinit var evidenceToggle: TextView
    private lateinit var reportBody: View

    private var latestReport: CompatReport? = null
    private var running = false
    private var setupRunning = false
    private var evidenceExpanded = false

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (SetupChecks.isDefaultDialer(requireContext())) {
            continueArmFlow()
        } else {
            Toast.makeText(requireContext(), R.string.compat_live_need_role, Toast.LENGTH_LONG).show()
            updateLiveTestUi()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        armNow()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        graph = (requireActivity().application as AutoAnswerApp).graph

        verdict = view.findViewById(R.id.compat_verdict)
        verdictSummary = view.findViewById(R.id.compat_verdict_summary)
        verdictDot = view.findViewById(R.id.verdict_dot)
        device = view.findViewById(R.id.compat_device)
        reportText = view.findViewById(R.id.compat_report_text)
        scanButton = view.findViewById(R.id.compat_scan_button)
        copyButton = view.findViewById(R.id.compat_copy_button)
        shareButton = view.findViewById(R.id.compat_share_button)
        progress = view.findViewById(R.id.compat_progress)
        shizukuStatus = view.findViewById(R.id.shizuku_status)
        shizukuAction = view.findViewById(R.id.shizuku_action_button)
        shizukuOutput = view.findViewById(R.id.shizuku_output)
        liveStatus = view.findViewById(R.id.live_status)
        liveArm = view.findViewById(R.id.live_arm_button)
        liveResultContainer = view.findViewById(R.id.live_result_container)
        liveYes = view.findViewById(R.id.live_yes_button)
        liveNo = view.findViewById(R.id.live_no_button)
        liveSimValue = view.findViewById(R.id.live_sim_value)
        evidenceToggle = view.findViewById(R.id.compat_evidence_toggle)
        reportBody = view.findViewById(R.id.compat_report_body)

        if (resources.getBoolean(R.bool.lite_mode)) {
            view.findViewById<View>(R.id.technical_card).isVisible = false
            view.findViewById<View>(R.id.shizuku_card).isVisible = false
            view.findViewById<View>(R.id.live_sim_divider).isVisible = false
            view.findViewById<View>(R.id.live_sim_row).isVisible = false
        }

        scanButton.setOnClickListener { runScan() }
        copyButton.setOnClickListener { copyReport() }
        shareButton.setOnClickListener { shareReport() }
        shizukuAction.setOnClickListener { handleShizukuAction() }
        liveArm.setOnClickListener { toggleLiveTest() }
        liveYes.setOnClickListener { recordLiveTest(LiveTestResult.PASSED) }
        liveNo.setOnClickListener { recordLiveTest(LiveTestResult.FAILED) }
        view.findViewById<View>(R.id.live_sim_row).setOnClickListener {
            startActivity(Intent(requireContext(), SimSelectionActivity::class.java))
        }
        view.findViewById<View>(R.id.evidence_header).setOnClickListener {
            setEvidenceExpanded(!evidenceExpanded)
        }

        device.text = getString(
            R.string.check_device_line,
            deviceName(),
            Build.VERSION.RELEASE,
        )
        updateEvidenceUi()
        updateShizukuUi()
        updateLiveTestUi()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.shizuku.state.collect { state ->
                    updateShizukuUi()
                    if (!running &&
                        state == ShizukuState.READY &&
                        latestReport?.shizukuState != ShizukuState.READY
                    ) {
                        runScan()
                    }
                }
            }
        }
        runScan()
    }

    override fun onResume() {
        super.onResume()
        graph.shizuku.refresh()
        updateShizukuUi()
        updateLiveTestUi()
        val report = latestReport
        val settings = graph.settings.current
        if (report == null) {
            if (!running) runScan()
        } else if (
            settings.liveTestStartedAtMs > report.generatedAt ||
            settings.liveTestResult != report.liveResult
        ) {
            runScan()
        }
    }

    private fun runScan() {
        if (running) return
        running = true
        progress.isVisible = true
        scanButton.isEnabled = false
        scanButton.setText(R.string.check_scanning)
        copyButton.isEnabled = false
        shareButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val report = graph.compatScanner.scan()
                latestReport = report
                renderReport(report)
                copyButton.isEnabled = true
                shareButton.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                verdict.setText(R.string.compat_verdict_unknown)
                verdictSummary.text = e.message ?: e.javaClass.simpleName
                reportText.text = e.message ?: e.javaClass.simpleName
                setVerdictColor(R.color.status_bad)
            } finally {
                running = false
                progress.isVisible = false
                scanButton.isEnabled = true
                scanButton.setText(R.string.check_scan)
                updateShizukuUi()
                updateLiveTestUi()
            }
        }
    }

    private fun renderReport(report: CompatReport) {
        val (label, summary, color) = verdictUi(report.verdict)
        verdict.setText(label)
        verdictSummary.setText(summary)
        setVerdictColor(color)
        reportText.text = report.text
        device.text = getString(
            R.string.check_device_line,
            deviceName(),
            Build.VERSION.RELEASE,
        )
        updateLiveTestUi()
    }

    private fun setVerdictColor(colorRes: Int) {
        val color = ContextCompat.getColor(requireContext(), colorRes)
        verdict.setTextColor(color)
        verdictDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun verdictUi(verdict: CompatVerdict): Triple<Int, Int, Int> = when (verdict) {
        CompatVerdict.VERIFIED -> Triple(
            R.string.compat_verdict_verified,
            R.string.compat_summary_verified,
            R.color.status_good,
        )
        CompatVerdict.LIKELY_SUPPORTED -> Triple(
            R.string.compat_verdict_likely,
            R.string.compat_summary_likely,
            R.color.status_warn,
        )
        CompatVerdict.FRAMEWORK_ONLY -> Triple(
            R.string.compat_verdict_framework,
            R.string.compat_summary_framework,
            R.color.status_bad,
        )
        CompatVerdict.NOT_SUPPORTED -> Triple(
            R.string.compat_verdict_no,
            R.string.compat_summary_no,
            R.color.status_bad,
        )
        CompatVerdict.CANDIDATE -> Triple(
            R.string.compat_verdict_candidate,
            R.string.compat_summary_candidate,
            R.color.status_warn,
        )
        CompatVerdict.TEST_FAILED -> Triple(
            R.string.compat_verdict_test_failed,
            R.string.compat_summary_test_failed,
            R.color.status_bad,
        )
        CompatVerdict.TEST_INCONCLUSIVE -> Triple(
            R.string.compat_verdict_inconclusive,
            R.string.compat_summary_inconclusive,
            R.color.status_warn,
        )
        CompatVerdict.UNKNOWN -> Triple(
            R.string.compat_verdict_unknown,
            R.string.compat_summary_unknown,
            R.color.text_muted,
        )
    }

    private fun updateShizukuUi() {
        val state = graph.shizuku.state.value
        val text = when (state) {
            ShizukuState.NOT_INSTALLED -> R.string.compat_shizuku_not_installed
            ShizukuState.NOT_RUNNING -> R.string.compat_shizuku_not_running
            ShizukuState.PERMISSION_REQUIRED -> R.string.compat_shizuku_permission
            ShizukuState.READY -> R.string.compat_shizuku_ready
        }
        shizukuStatus.setText(text)
        val action = when (state) {
            ShizukuState.NOT_INSTALLED -> null
            ShizukuState.NOT_RUNNING -> R.string.compat_shizuku_open
            ShizukuState.PERMISSION_REQUIRED -> R.string.compat_shizuku_request
            ShizukuState.READY -> R.string.compat_shizuku_setup
        }
        if (action == null) {
            shizukuAction.isVisible = false
        } else {
            shizukuAction.isVisible = true
            shizukuAction.setText(if (setupRunning) R.string.compat_shizuku_setup_running else action)
            shizukuAction.isEnabled = !setupRunning
        }
    }

    private fun handleShizukuAction() {
        when (graph.shizuku.state.value) {
            ShizukuState.PERMISSION_REQUIRED -> graph.shizuku.requestPermission()
            ShizukuState.NOT_RUNNING -> openShizuku()
            ShizukuState.READY -> confirmShizukuSetup()
            ShizukuState.NOT_INSTALLED -> Unit
        }
    }

    private fun confirmShizukuSetup() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.compat_shizuku_setup_confirm_title)
            .setMessage(R.string.compat_shizuku_setup_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.compat_shizuku_setup_confirm_action) { _, _ ->
                runShizukuSetup()
            }
            .show()
    }

    private fun openShizuku() {
        if (!graph.shizuku.openShizuku()) {
            Toast.makeText(requireContext(), R.string.compat_shizuku_open_failed, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun runShizukuSetup() {
        if (!graph.shizuku.isReady()) {
            Toast.makeText(requireContext(), R.string.compat_shizuku_setup_failed, Toast.LENGTH_LONG)
                .show()
            return
        }
        setupRunning = true
        updateShizukuUi()
        shizukuOutput.isVisible = true
        shizukuOutput.text = getString(R.string.compat_shizuku_setup_running)
        viewLifecycleOwner.lifecycleScope.launch {
            val output = try {
                graph.shizuku.applySetup(requireContext().packageName)
            } catch (e: Exception) {
                getString(R.string.compat_shizuku_setup_failed) + "\n" +
                    (e.message ?: e.javaClass.simpleName)
            }
            setupRunning = false
            if (!isAdded) return@launch
            shizukuOutput.text = getString(R.string.compat_shizuku_setup_title) + "\n" + output
            graph.shizuku.refresh()
            updateShizukuUi()
            updateLiveTestUi()
            runScan()
        }
    }

    private fun updateEvidenceUi() {
        reportBody.isVisible = evidenceExpanded
        evidenceToggle.setText(
            if (evidenceExpanded) R.string.check_hide else R.string.check_show,
        )
    }

    private fun setEvidenceExpanded(expanded: Boolean) {
        evidenceExpanded = expanded
        updateEvidenceUi()
    }

    private fun updateLiveTestUi() {
        val settings = graph.settings.current
        val armed = settings.isLiveTestArmed
        liveArm.setText(if (armed) R.string.check_live_disarm else R.string.check_live_arm)
        liveArm.isEnabled = true
        val date = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            .format(Date(settings.liveTestStartedAtMs))
        val color = when {
            armed -> R.color.brand_primary
            settings.liveTestResult == LiveTestResult.PASSED -> R.color.status_good
            settings.liveTestResult == LiveTestResult.FAILED -> R.color.status_bad
            else -> R.color.text_muted
        }
        liveStatus.setTextColor(ContextCompat.getColor(requireContext(), color))
        liveStatus.text = when {
            armed -> getString(
                R.string.check_live_armed,
                settings.liveTestArmedRemainingMs / 60_000L,
            )
            settings.liveTestStartedAtMs > 0L -> {
                val route = getString(
                    if (settings.liveTestRouteVerified) {
                        R.string.check_live_route_ok
                    } else {
                        R.string.check_live_route_none
                    },
                )
                val error = settings.liveTestError
                    ?.take(120)
                    ?.let { " · $it" }
                    ?: ""
                getString(R.string.check_live_last, date, route, error)
            }
            else -> getString(R.string.check_live_not_run)
        }
        liveResultContainer.isVisible =
            settings.liveTestStartedAtMs > 0L &&
            settings.liveTestResult == LiveTestResult.NOT_RUN
        liveSimValue.text = when {
            !settings.usesSpecificSim -> getString(R.string.check_sim_all)
            settings.simLabel != null -> settings.simLabel ?: getString(R.string.check_sim_selected)
            else -> getString(R.string.check_sim_selected)
        }
    }

    private fun toggleLiveTest() {
        if (graph.settings.current.isLiveTestArmed) {
            graph.settings.disarmLiveTest()
            updateLiveTestUi()
            Toast.makeText(requireContext(), R.string.check_live_disarmed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!SetupChecks.isDefaultDialer(requireContext())) {
            showRoleRequiredDialog()
            return
        }
        continueArmFlow()
    }

    private fun continueArmFlow() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.READ_PHONE_STATE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            armNow()
        }
    }

    private fun armNow() {
        graph.settings.armLiveTest()
        updateLiveTestUi()
        val message = if (!SetupChecks.notificationsAllowed(requireContext())) {
            getString(R.string.compat_live_setup_hint)
        } else {
            getString(R.string.check_live_armed, LIVE_TEST_WINDOW_MINUTES)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        if (graph.settings.current.usesSpecificSim &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(requireContext(), R.string.sim_permission_body, Toast.LENGTH_LONG).show()
        }
    }

    private fun recordLiveTest(result: LiveTestResult) {
        graph.settings.setLiveTestResult(result)
        Toast.makeText(
            requireContext(),
            if (result == LiveTestResult.PASSED) {
                R.string.check_live_passed_toast
            } else {
                R.string.check_live_failed_toast
            },
            Toast.LENGTH_SHORT,
        ).show()
        updateLiveTestUi()
        runScan()
    }

    private fun showRoleRequiredDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.check_live_title)
            .setMessage(R.string.compat_live_need_role)
            .setPositiveButton(R.string.compat_live_need_role_action) { _, _ ->
                requestDefaultDialerRole()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestDefaultDialerRole() {
        val roleManager = requireContext().getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            Toast.makeText(requireContext(), R.string.role_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }.onFailure {
            Toast.makeText(requireContext(), R.string.role_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyReport() {
        val text = latestReport?.text ?: return
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.check_page_title), text),
        )
        Toast.makeText(requireContext(), R.string.compat_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val text = latestReport?.text ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.check_page_title))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.compat_share)))
        }
    }

    private companion object {
        const val LIVE_TEST_WINDOW_MINUTES = 30L
    }
    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return "$maker ${Build.MODEL}"
    }

}
