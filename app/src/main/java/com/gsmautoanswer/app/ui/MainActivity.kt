package com.gsmautoanswer.app.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.ui.check.DeviceCheckFragment
import com.gsmautoanswer.app.ui.home.HomeFragment
import com.gsmautoanswer.app.ui.settings.SettingsFragment
import com.gsmautoanswer.app.util.SystemBars
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private var selectedTab = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        SystemBars.apply(findViewById(R.id.main_root))

        bottomNav = findViewById(R.id.bottom_nav)
        selectedTab = savedInstanceState?.getInt(KEY_TAB, R.id.nav_home) ?: R.id.nav_home

        bottomNav.setOnItemSelectedListener { item ->
            selectedTab = item.itemId
            showTab(item.itemId)
            true
        }
        bottomNav.selectedItemId = selectedTab
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            showTab(selectedTab)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectedTab != R.id.nav_home) {
                        selectTab(R.id.nav_home)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    fun selectTab(itemId: Int) {
        if (bottomNav.selectedItemId == itemId) {
            showTab(itemId)
        } else {
            bottomNav.selectedItemId = itemId
        }
    }

    private fun showTab(itemId: Int) {
        val tag = when (itemId) {
            R.id.nav_check -> TAG_CHECK
            R.id.nav_settings -> TAG_SETTINGS
            else -> TAG_HOME
        }
        if (supportFragmentManager.findFragmentById(R.id.fragment_container)?.tag == tag) return
        val fragment: Fragment = when (itemId) {
            R.id.nav_check -> DeviceCheckFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_TAB, selectedTab)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val KEY_TAB = "selected_tab"
        const val TAG_HOME = "home"
        const val TAG_CHECK = "device_check"
        const val TAG_SETTINGS = "settings"
    }
}
