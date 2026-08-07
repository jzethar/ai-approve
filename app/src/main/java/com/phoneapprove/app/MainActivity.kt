package com.phoneapprove.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.phoneapprove.app.data.DaemonLinkManager
import com.phoneapprove.app.data.PairingRepository
import com.phoneapprove.app.data.SettingsRepository
import com.phoneapprove.app.data.ThemeMode
import com.phoneapprove.app.service.ConnectionService
import com.phoneapprove.app.ui.PairingScreen
import com.phoneapprove.app.ui.RequestsScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissionsIfNeeded()

        val pairingRepo = PairingRepository(applicationContext)
        if (pairingRepo.loadAll().isNotEmpty()) {
            startConnectionService()
        }
        SettingsRepository(applicationContext).apply {
            syncThemeModeFlowFromPrefs()
            syncAutoApproveFlowFromPrefs()
        }

        setContent {
            var pairings by remember { mutableStateOf(pairingRepo.loadAll()) }
            var showPairingScreen by remember { mutableStateOf(pairings.isEmpty()) }
            val themeMode by SettingsRepository.themeModeFlow.collectAsState()
            val isDark = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (showPairingScreen) {
                        PairingScreen(
                            canCancel = pairings.isNotEmpty(),
                            onCancel = { showPairingScreen = false },
                            onPaired = { info ->
                                pairingRepo.save(info)
                                pairings = pairingRepo.loadAll()
                                DaemonLinkManager.start(applicationContext, info)
                                startConnectionService()
                                showPairingScreen = false
                            },
                        )
                    } else {
                        RequestsScreen(
                            pairings = pairings,
                            onAddDevice = { showPairingScreen = true },
                            onForgetDevice = { id ->
                                pairingRepo.remove(id)
                                pairings = pairingRepo.loadAll()
                                DaemonLinkManager.stop(id)
                                if (pairings.isEmpty()) {
                                    stopService(Intent(this, ConnectionService::class.java))
                                    showPairingScreen = true
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun startConnectionService() {
        val intent = Intent(this, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
