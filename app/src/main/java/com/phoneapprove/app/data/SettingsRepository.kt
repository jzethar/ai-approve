package com.phoneapprove.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Plain (unencrypted) prefs for non-secret app settings - separate from
 * [PairingRepository], which holds the actual credential. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("phone_approve_settings", Context.MODE_PRIVATE)

    /** Off by default: notification actions let anyone who can see/reach the
     * notification approve a request without unlocking/opening the app. */
    fun notificationActionsEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_ACTIONS, false)

    fun setNotificationActionsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_ACTIONS, enabled).apply()
    }

    fun themeMode(): ThemeMode =
        prefs.getString(KEY_THEME_MODE, null)?.let { saved ->
            ThemeMode.entries.find { it.name == saved }
        } ?: ThemeMode.SYSTEM

    /** Persists the choice and pushes it to [themeModeFlow] so the UI - which
     * SharedPreferences alone can't notify - recomposes immediately. */
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeModeFlow.value = mode
    }

    /** Call once (MainActivity.onCreate, before setContent) to seed the flow
     * below from whatever was last persisted. */
    fun syncThemeModeFlowFromPrefs() {
        _themeModeFlow.value = themeMode()
    }

    companion object {
        private const val KEY_NOTIFICATION_ACTIONS = "notification_actions_enabled"
        private const val KEY_THEME_MODE = "theme_mode"

        private val _themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)
        val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow.asStateFlow()
    }
}
