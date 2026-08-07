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
import com.phoneapprove.app.model.SessionNotify
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
    // "_v2": channel importance is locked in at first creation and
    // createNotificationChannel() is a no-op afterward - an earlier debug
    // build created "phone_approve_sessions" before it was set to
    // IMPORTANCE_HIGH, so it silently stayed low (no heads-up alert) no
    // matter what this code says. A fresh id guarantees HIGH actually
    // takes effect instead of requiring everyone to fix it by hand in
    // system settings.
    private val sessionChannelId = "phone_approve_sessions_v2"
    private var notifiedReqIds: Set<String> = emptySet()
    private var notifiedSessionKeys: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildStatusNotification(emptyMap(), 0))

        // A notification posted by a previous process (killed by the OS, or a
        // crash) outlives that process - the fresh DaemonLinkManager singleton
        // starts with empty state, so notifiedReqIds/notifiedSessionKeys below
        // have nothing to diff those old ids against and would otherwise never
        // cancel them. Sweep once up front so a restart can't leave an orphaned
        // card/session notification stuck in the shade forever.
        cancelOrphanedNotifications()

        // Only start links this process isn't already managing - if MainActivity just
        // called DaemonLinkManager.start() directly for a freshly-added pairing before
        // starting this service, restarting it here too would tear it down and race a
        // fresh connection against the one that's already up.
        for (pairing in PairingRepository(applicationContext).loadAll()) {
            if (!DaemonLinkManager.isManaging(pairing.id)) {
                DaemonLinkManager.start(applicationContext, pairing)
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

        scope.launch {
            DaemonLinkManager.notifications.collect { notify -> postSessionNotification(notify) }
        }

        // Session notifications are posted once (above, from the one-shot
        // event flow) but can be dismissed two ways: swiping the single
        // SessionCard in-app, or tapping "Clear" on the whole history - both
        // just mutate sessionHistory and have no idea a real OS notification
        // is still up. Diffing this durable list the same way requests are
        // diffed above is what actually cancels it either way.
        scope.launch {
            DaemonLinkManager.sessionHistory.collect { history -> updateSessionNotifications(history) }
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
        manager.createNotificationChannel(
            NotificationChannel(sessionChannelId, "Phone Approve session updates", NotificationManager.IMPORTANCE_HIGH)
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

    /** Mirrors [updateRequestNotifications] for the session-finished channel:
     * [sessionHistory] is the durable list a dismissed/cleared entry actually
     * disappears from (see RequestsScreen's SessionCard "Dismiss" and the
     * history header's "Clear"), so diffing against it - rather than trusting
     * whoever removed the entry to also remember to cancel its notification -
     * is what makes both of those actually clear the notification shade. */
    private fun updateSessionNotifications(history: List<SessionNotify>) {
        val currentKeys = history.map { sessionKey(it.sessionId, it.ts) }.toSet()
        for (staleKey in notifiedSessionKeys - currentKeys) {
            NotificationManagerCompat.from(this).cancel(staleKey.hashCode())
        }
        notifiedSessionKeys = notifiedSessionKeys intersect currentKeys
    }

    /** A notification posted by a previous, now-dead process has no entry in
     * this fresh instance's notifiedReqIds/notifiedSessionKeys (both reset to
     * empty on process start) and DaemonLinkManager's own state is reset the
     * same way, so nothing here would otherwise think to cancel it - it would
     * just sit in the shade, un-actionable, until manually swiped away. Runs
     * once at startup, before any of the reactive collectors above take over. */
    private fun cancelOrphanedNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        val liveRequestIds = DaemonLinkManager.requests.value.map { it.reqId.hashCode() }.toSet()
        val liveSessionIds = DaemonLinkManager.sessionHistory.value
            .map { sessionKey(it.sessionId, it.ts).hashCode() }.toSet()
        for (sbn in manager.activeNotifications) {
            val stillLive = when (sbn.notification.channelId) {
                requestChannelId -> sbn.id in liveRequestIds
                sessionChannelId -> sbn.id in liveSessionIds
                else -> true
            }
            if (!stillLive) manager.cancel(sbn.id)
        }
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
            val options = request.options
            if (options != null && options.isNotEmpty()) {
                // A proposed-answer tool (e.g. AskUserQuestion): Allow/Allow
                // always/Deny don't correspond to any real answer, so offer
                // its own options instead - same "other" reply path RequestCard
                // uses in-app. A heads-up notification only ever surfaces
                // ~3 actions, so beyond that just skip inline actions (the
                // notification itself still alerts) and require opening the
                // app, where every option is always listed regardless of count.
                if (options.size <= MAX_NOTIFICATION_OPTIONS) {
                    options.forEachIndexed { i, option ->
                        builder.addAction(0, option, actionPendingIntent(request.reqId, "other", i, reply = option))
                    }
                }
            } else {
                builder
                    .addAction(0, "Allow", actionPendingIntent(request.reqId, "allow", 0))
                    .addAction(0, "Allow always", actionPendingIntent(request.reqId, "allow_always", 1))
                    .addAction(0, "Deny", actionPendingIntent(request.reqId, "deny", 2))
            }
        }

        val notification = builder.build()
        NotificationManagerCompat.from(this).notify(request.reqId.hashCode(), notification)
    }

    /** Posts a one-shot "session finished" alert. Unlike request notifications
     * (which are cancelled once resolved and can overwrite each other via a
     * per-req_id id), these carry no Allow/Deny power and nothing to resolve,
     * so each gets its own id and is left to stack like a normal chat ping. */
    private fun postSessionNotification(notify: SessionNotify) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, sessionChannelId)
            .setContentTitle("${notify.deviceName} — session finished")
            .setContentText(notify.message)
            .setSubText(notify.cwd)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val key = sessionKey(notify.sessionId, notify.ts)
        notifiedSessionKeys = notifiedSessionKeys + key
        NotificationManagerCompat.from(this).notify(key.hashCode(), notification)
    }

    /** sessionId+ts together identify one notify (see DaemonLinkManager.
     * dismissSessionNotification) - the single source of truth for turning
     * that pair into a notification id, so posting and cancelling can never
     * compute it differently. */
    private fun sessionKey(sessionId: String, ts: Double) = "${sessionId}_${ts}"

    private fun actionPendingIntent(reqId: String, action: String, code: Int, reply: String? = null): PendingIntent {
        val intent = Intent(this, ApprovalActionReceiver::class.java).apply {
            putExtra(ApprovalActionReceiver.EXTRA_REQ_ID, reqId)
            putExtra(ApprovalActionReceiver.EXTRA_ACTION, action)
            if (reply != null) putExtra(ApprovalActionReceiver.EXTRA_REPLY, reply)
        }
        return PendingIntent.getBroadcast(
            this, reqId.hashCode() * 10 + code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        // Heads-up notifications reliably render about 3 action buttons across
        // stock Android/OEM skins - beyond that, inline actions get silently
        // dropped or the notification gets crowded, so past this count it's
        // better to show none and send the person into the app instead.
        private const val MAX_NOTIFICATION_OPTIONS = 3
    }
}
