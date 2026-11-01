package com.gsmautoanswer.app.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.gsmautoanswer.app.AutoAnswerApp
import com.gsmautoanswer.app.AppGraph
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.data.ThemeMode
import com.gsmautoanswer.app.diagnostics.SetupChecks
import com.gsmautoanswer.app.ui.AudioSettingsActivity
import com.gsmautoanswer.app.ui.CallFlowSettingsActivity
import com.gsmautoanswer.app.ui.DialerActivity
import com.gsmautoanswer.app.ui.DiagnosticsActivity
import com.gsmautoanswer.app.ui.SimSelectionActivity
import com.gsmautoanswer.app.util.ThemeController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var graph: AppGraph
    private lateinit var roleRow: Row
    private lateinit var notificationRow: Row
    private lateinit var batteryRow: Row
    private lateinit var simRow: Row
    private lateinit var audioRow: Row
    private lateinit var flowRow: Row
    private lateinit var diagnosticsRow: Row
    private lateinit var dialerRow: Row
    private lateinit var appearanceRow: Row
    private lateinit var aboutRow: Row

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { render() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { render() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        graph = (requireActivity().application as AutoAnswerApp).graph
        val access = view.findViewById<LinearLayout>(R.id.group_access)
        val calls = view.findViewById<LinearLayout>(R.id.group_calls)
        val advanced = view.findViewById<LinearLayout>(R.id.group_advanced)
        val about = view.findViewById<LinearLayout>(R.id.group_about)
        listOf(access, calls, advanced, about).forEach { (it.parent as? View)?.clipToOutline = true }

        roleRow = addRow(access, R.drawable.ic_phone, getString(R.string.settings_role_title), ::requestRole)
        notificationRow = addRow(
            access,
            R.drawable.ic_notifications,
            getString(R.string.settings_notifications_title),
            ::requestNotifications,
        )
        batteryRow = addRow(
            access,
            R.drawable.ic_battery,
            getString(R.string.settings_battery_title),
            ::requestBatteryExemption,
        )

        simRow = addRow(
            calls,
            R.drawable.ic_phone,
            getString(R.string.settings_sim_title),
        ) { open(SimSelectionActivity::class.java) }
        audioRow = addRow(
            calls,
            R.drawable.ic_music,
            getString(R.string.settings_audio_title),
        ) { open(AudioSettingsActivity::class.java) }
        flowRow = addRow(
            calls,
            R.drawable.ic_flow,
            getString(R.string.settings_flow_title),
        ) { open(CallFlowSettingsActivity::class.java) }

        diagnosticsRow = addRow(
            advanced,
            R.drawable.ic_diagnostics,
            getString(R.string.settings_diagnostics_title),
        ) { open(DiagnosticsActivity::class.java) }
        dialerRow = addRow(
            advanced,
            R.drawable.ic_dialpad,
            getString(R.string.settings_dialer_title),
        ) { open(DialerActivity::class.java) }

        appearanceRow = addRow(
            about,
            R.drawable.ic_palette,
            getString(R.string.settings_appearance_title),
            ::showAppearanceDialog,
        )
        aboutRow = addRow(
            about,
            R.drawable.ic_info,
            getString(R.string.settings_about_title),
            ::showAboutDialog,
        )

        render()
    }

    override fun onResume() {
        super.onResume()
        if (::graph.isInitialized) render()
    }

    private fun render() {
        val context = requireContext()
        val settings = graph.settings.current
        val roleReady = SetupChecks.isDefaultDialer(context)
        val notificationsReady = SetupChecks.notificationsAllowed(context)
        val batteryReady = SetupChecks.isBatteryExempt(context)
        val audioReady = !settings.greetingUri.isNullOrBlank() && !settings.holdUri.isNullOrBlank()

        roleRow.subtitle.setText(
            if (roleReady) R.string.settings_role_ready else R.string.settings_role_missing,
        )
        roleRow.action.isVisible = !roleReady
        roleRow.chevron.isVisible = false
        roleRow.action.setText(R.string.settings_role_action)

        notificationRow.subtitle.setText(
            if (notificationsReady) {
                R.string.settings_notifications_ready
            } else {
                R.string.settings_notifications_missing
            },
        )
        notificationRow.action.isVisible = !notificationsReady
        notificationRow.chevron.isVisible = false
        notificationRow.action.setText(R.string.settings_notifications_action)

        batteryRow.subtitle.setText(
            if (batteryReady) R.string.settings_battery_ready else R.string.settings_battery_missing,
        )
        batteryRow.action.isVisible = !batteryReady
        batteryRow.chevron.isVisible = false
        batteryRow.action.setText(R.string.settings_battery_action)

        simRow.subtitle.text = if (settings.usesSpecificSim) {
            settings.simLabel ?: getString(R.string.home_sim_all)
        } else {
            getString(R.string.home_sim_all)
        }
        audioRow.subtitle.setText(
            if (audioReady) R.string.settings_audio_ready else R.string.settings_audio_missing,
        )
        val delay = if (settings.answerDelaySeconds == 0) {
            getString(R.string.answer_immediately)
        } else {
            getString(R.string.answer_delay_seconds, settings.answerDelaySeconds)
        }
        flowRow.subtitle.text = getString(
            R.string.settings_flow_value,
            settings.countdownSeconds,
            delay,
        )
        diagnosticsRow.subtitle.setText(R.string.settings_diagnostics_summary)
        dialerRow.subtitle.setText(R.string.settings_dialer_summary)
        appearanceRow.subtitle.text = ThemeController.label(context, settings.themeMode)
        appearanceRow.chevron.isVisible = true
        aboutRow.subtitle.text = getString(R.string.settings_about_version, versionName())
        aboutRow.chevron.isVisible = true
    }

    private fun addRow(
        container: LinearLayout,
        iconRes: Int,
        title: String,
        onClick: (() -> Unit)? = null,
    ): Row {
        val view = layoutInflater.inflate(R.layout.item_settings_row, container, false)
        val divider = view.findViewById<View>(R.id.row_divider)
        val root = view.findViewById<View>(R.id.row_main)
        val action = view.findViewById<MaterialButton>(R.id.row_action)
        val subtitle = view.findViewById<TextView>(R.id.row_subtitle)
        val chevron = view.findViewById<ImageView>(R.id.row_chevron)
        divider.isVisible = container.childCount > 0
        view.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.row_title).text = title
        if (onClick != null) {
            root.setOnClickListener { onClick() }
            action.setOnClickListener { onClick() }
        } else {
            root.isClickable = false
            root.isFocusable = false
        }
        container.addView(
            view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return Row(root, divider, subtitle, action, chevron)
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(requireContext(), activity))
    }

    private fun requestRole() {
        if (SetupChecks.isDefaultDialer(requireContext())) return
        val roleManager = requireContext().getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            toast(R.string.role_unavailable)
            return
        }
        runCatching {
            roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }.onFailure {
            toast(R.string.role_unavailable)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        runCatching { startActivity(intent) }
            .onFailure { toast(R.string.notification_settings_unavailable) }
    }

    private fun requestBatteryExemption() {
        if (SetupChecks.isBatteryExempt(requireContext())) return
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${requireContext().packageName}"),
        )
        runCatching { startActivity(direct) }.onFailure {
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            runCatching { startActivity(list) }
                .onFailure { toast(R.string.battery_settings_unavailable) }
        }
    }

    private fun showAppearanceDialog() {
        val modes = ThemeMode.entries
        val labels = modes.map { ThemeController.label(requireContext(), it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.appearance_dialog_title)
            .setSingleChoiceItems(
                labels,
                modes.indexOf(graph.settings.current.themeMode),
            ) { dialog, which ->
                val mode = modes[which]
                graph.settings.setThemeMode(mode)
                dialog.dismiss()
                ThemeController.apply(mode)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, versionName()))
            .setPositiveButton(R.string.about_view_source) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)))
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun versionName(): String = runCatching {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
    }.getOrNull() ?: "1.0"

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_LONG).show()
    }

    private data class Row(
        val root: View,
        val divider: View,
        val subtitle: TextView,
        val action: MaterialButton,
        val chevron: ImageView,
    )

    private companion object {
        const val REPOSITORY_URL = "https://github.com/SayfullahSayeb/gsm-auto-answer"
    }
}
