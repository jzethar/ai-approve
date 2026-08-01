package com.phoneapprove.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.phoneapprove.app.MainActivity
import com.phoneapprove.app.data.ConnectionState
import com.phoneapprove.app.data.DaemonLinkManager
import com.phoneapprove.app.data.PairingRepository
import com.phoneapprove.app.data.SettingsRepository
import com.phoneapprove.app.model.ApprovalRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Foreground service that keeps [DaemonLinkManager]'s connections alive
 * (and visible via notification) even while the app is backgrounded. Devices
 * can be added/removed live via [DaemonLinkManager] directly from
 * MainActivity - onCreate here only needs to restore whatever was already
 * paired, for the cold-start case where this service starts before any UI
 * has run. */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob())
    private val channelId = "phone_approve_connection"
    private val requestChannelId = "phone_approve_requests"
    private var notifiedReqIds: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildStatusNotification(emptyMap(), 0))

        // Only start links this process isn't already managing - if MainActivity just
        // called DaemonLinkManager.start() directly for a freshly-added pairing before
        // starting this service, restarting it here too would tear it down and race a
        // fresh connection against the one that's already up.
        for (pairing in PairingRepository(applicationContext).loadAll()) {
            if (!DaemonLinkManager.isManaging(pairing.id)) {
                DaemonLinkManager.start(pairing)
            }
        }

        scope.launch {
            combine(
                DaemonLinkManager.connectionStates,
                DaemonLinkManager.requests,
            ) { states, requests -> states to requests }
                .collect { (states, requests) ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildStatusNotification(states, requests.size))
                    updateRequestNotifications(requests)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        DaemonLinkManager.stopAll()
        cancelAllRequestNotifications()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Phone Approve connection", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(requestChannelId, "Phone Approve requests", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildStatusNotification(states: Map<String, ConnectionState>, pendingCount: Int): Notification {
        val connected = states.values.count { it == ConnectionState.CONNECTED }
        val total = states.size
        val text = when {
            total == 0 -> "Not connected"
            pendingCount > 0 -> "$connected/$total connected, $pendingCount pending approval(s)"
            else -> "$connected/$total connected"
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Phone Approve")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /** Posts/cancels one high-priority notification per pending request.
     * The notification itself is always useful as an alert; Settings only
     * controls whether it includes action buttons that can approve/deny
     * without opening the app. */
    private fun updateRequestNotifications(requests: List<ApprovalRequest>) {
        val currentIds = requests.map { it.reqId }.toSet()
        for (staleId in notifiedReqIds - currentIds) {
            NotificationManagerCompat.from(this).cancel(staleId.hashCode())
        }
        for (request in requests) {
            if (request.reqId !in notifiedReqIds) postRequestNotification(request)
        }
        notifiedReqIds = currentIds
    }

    private fun cancelAllRequestNotifications() {
        for (reqId in notifiedReqIds) {
            NotificationManagerCompat.from(this).cancel(reqId.hashCode())
        }
        notifiedReqIds = emptySet()
    }

    private fun postRequestNotification(request: ApprovalRequest) {
        val builder = NotificationCompat.Builder(this, requestChannelId)
            .setContentTitle("${request.toolName} — ${request.deviceName}")
            .setContentText(request.toolInput)
            .setSubText(request.cwd)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Hides content and actions on a locked screen until unlocked - these
            // buttons approve tool calls, so they shouldn't be tappable by anyone
            // who merely glances at a locked phone.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)

        if (SettingsRepository(applicationContext).notificationActionsEnabled()) {
            builder
                .addAction(0, "Allow", actionPendingIntent(request.reqId, "allow", 0))
                .addAction(0, "Allow always", actionPendingIntent(request.reqId, "allow_always", 1))
                .addAction(0, "Deny", actionPendingIntent(request.reqId, "deny", 2))
        }

        val notification = builder.build()
        NotificationManagerCompat.from(this).notify(request.reqId.hashCode(), notification)
    }

    private fun actionPendingIntent(reqId: String, action: String, code: Int): PendingIntent {
        val intent = Intent(this, ApprovalActionReceiver::class.java).apply {
            putExtra(ApprovalActionReceiver.EXTRA_REQ_ID, reqId)
            putExtra(ApprovalActionReceiver.EXTRA_ACTION, action)
        }
        return PendingIntent.getBroadcast(
            this, reqId.hashCode() * 10 + code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
