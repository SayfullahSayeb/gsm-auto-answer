package com.gsmautoanswer.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.gsmautoanswer.app.R
import com.gsmautoanswer.app.data.ThemeMode

object ThemeController {
    fun apply(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    fun label(context: Context, mode: ThemeMode): String = context.getString(
        when (mode) {
            ThemeMode.LIGHT -> R.string.appearance_light
            ThemeMode.DARK -> R.string.appearance_dark
            ThemeMode.SYSTEM -> R.string.appearance_system
        },
    )
}
