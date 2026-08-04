package com.phoneapprove.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.phoneapprove.app.data.BluetoothRfcomm
import com.phoneapprove.app.data.ConnectionState
import com.phoneapprove.app.data.DaemonLinkManager
import com.phoneapprove.app.data.PairingInfo
import com.phoneapprove.app.data.SettingsRepository
import com.phoneapprove.app.data.ThemeMode
import com.phoneapprove.app.data.Transport
import com.phoneapprove.app.model.ApprovalRequest
import com.phoneapprove.app.model.SessionNotify

@Composable
fun RequestsScreen(
    pairings: List<PairingInfo>,
    onAddDevice: () -> Unit,
    onForgetDevice: (String) -> Unit,
) {
    val connectionStates by DaemonLinkManager.connectionStates.collectAsState()
    val activeTransports by DaemonLinkManager.activeTransports.collectAsState()
    val requests by DaemonLinkManager.requests.collectAsState()
    val sessionHistory by DaemonLinkManager.sessionHistory.collectAsState()
    var showDevices by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionSummary(pairings, connectionStates, activeTransports)
            Row {
                TextButton(onClick = { showSettings = true }) {
                    Text("Settings")
                }
                TextButton(onClick = { showDevices = true }) {
                    Text("Devices")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (requests.isEmpty() && sessionHistory.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No pending approval requests.")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(requests, key = { it.reqId }) { request -> RequestCard(request) }

                if (sessionHistory.isNotEmpty()) {
                    item(key = "session-history-header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Recent sessions", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { DaemonLinkManager.clearSessionHistory() }) {
                                Text("Clear")
                            }
                        }
                    }
                    items(sessionHistory, key = { "${it.sessionId}_${it.ts}" }) { notify ->
                        SessionCard(notify, onDismiss = {
                            DaemonLinkManager.dismissSessionNotification(notify.sessionId, notify.ts)
                        })
                    }
                }
            }
        }
    }

    if (showDevices) {
        DevicesDialog(
            pairings = pairings,
            connectionStates = connectionStates,
            activeTransports = activeTransports,
            onAddDevice = {
                showDevices = false
                onAddDevice()
            },
            onForgetDevice = onForgetDevice,
            onDismiss = { showDevices = false },
        )
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
private fun ConnectionSummary(
    pairings: List<PairingInfo>,
    states: Map<String, ConnectionState>,
    transports: Map<String, Transport>,
) {
    if (pairings.size == 1) {
        ConnectionBadge(states[pairings[0].id] ?: ConnectionState.DISCONNECTED, transports[pairings[0].id])
        return
    }
    val connected = pairings.count { states[it.id] == ConnectionState.CONNECTED }
    val color = when {
        pairings.isEmpty() -> MaterialTheme.colorScheme.error
        connected == pairings.size -> MaterialTheme.colorScheme.primary
        connected > 0 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Text("$connected/${pairings.size} connected", color = color, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun DevicesDialog(
    pairings: List<PairingInfo>,
    connectionStates: Map<String, ConnectionState>,
    activeTransports: Map<String, Transport>,
    onAddDevice: () -> Unit,
    onForgetDevice: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Devices") },
        text = {
            Column {
                for (pairing in pairings) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(pairing.name.ifBlank { pairing.host })
                            ConnectionBadge(
                                connectionStates[pairing.id] ?: ConnectionState.DISCONNECTED,
                                activeTransports[pairing.id],
                                style = MaterialTheme.typography.bodySmall,
                            )
                            // Bonding can be revoked (or never done) after pairing - a live,
                            // separate check from ConnectionBadge above, since a device can be
                            // CONNECTED (via TCP) while still not bonded for Bluetooth.
                            val btMac = pairing.btMac
                            if (btMac != null && !BluetoothRfcomm.isBonded(context, btMac)) {
                                Text(
                                    "Bluetooth: not bonded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        TextButton(onClick = { onForgetDevice(pairing.id) }) { Text("Forget") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onAddDevice) { Text("+ Add device") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    var notificationActionsEnabled by remember { mutableStateOf(settings.notificationActionsEnabled()) }
    val themeMode by SettingsRepository.themeModeFlow.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Approve from notification")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Show Allow / Allow always / Deny buttons on the request " +
                                "notification, so you can respond without opening the app. " +
                                "Anyone who can see or reach your notifications can approve " +
                                "requests this way, so this is off by default.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = notificationActionsEnabled,
                        onCheckedChange = {
                            notificationActionsEnabled = it
                            settings.setNotificationActionsEnabled(it)
                        },
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text("Theme")
                Spacer(modifier = Modifier.height(4.dp))
                for (mode in ThemeMode.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { settings.setThemeMode(mode) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = { settings.setThemeMode(mode) })
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "System default"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ConnectionBadge(
    state: ConnectionState,
    transport: Transport? = null,
    style: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val (label, color) = when (state) {
        ConnectionState.CONNECTED -> "Connected" to MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> "Connecting..." to MaterialTheme.colorScheme.tertiary
        ConnectionState.DISCONNECTED -> "Not connected" to MaterialTheme.colorScheme.error
    }
    val text = if (state == ConnectionState.CONNECTED && transport != null) {
        val via = if (transport == Transport.BLUETOOTH) "Bluetooth" else "TCP"
        "$label (via $via)"
    } else {
        label
    }
    Text(text, color = color, style = style)
}

@Composable
private fun RequestCard(request: ApprovalRequest) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(request.toolName, style = MaterialTheme.typography.titleMedium)
                Text(
                    request.deviceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(request.cwd, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(request.toolInput, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { DaemonLinkManager.respond(request.reqId, "allow") }) {
                    Text("Allow")
                }
                Button(onClick = { DaemonLinkManager.respond(request.reqId, "allow_always") }) {
                    Text("Allow always")
                }
                Button(
                    onClick = { DaemonLinkManager.respond(request.reqId, "deny") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Deny")
                }
            }
        }
    }
}

/** Informational, not actionable like [RequestCard] - nothing to Allow/Deny
 * here, just the last-reply snippet and a way to dismiss it from the list. */
@Composable
private fun SessionCard(notify: SessionNotify, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Session finished", style = MaterialTheme.typography.titleMedium)
                Text(
                    notify.deviceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(notify.cwd, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(notify.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}
