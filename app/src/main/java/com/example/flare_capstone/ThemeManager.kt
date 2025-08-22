package com.example.flare_capstone

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    const val PREF_NAME = "user_preferences"
    const val DARK_MODE_KEY = "dark_mode_enabled"

    fun applyTheme(context: Context) {
        val sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val darkModeEnabled = sharedPreferences.getBoolean(DARK_MODE_KEY, false)
        if (darkModeEnabled) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    fun setDarkModeEnabled(context: Context, enabled: Boolean) {
        val sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sharedPreferences.edit().putBoolean(DARK_MODE_KEY, enabled).apply()
    }
}
