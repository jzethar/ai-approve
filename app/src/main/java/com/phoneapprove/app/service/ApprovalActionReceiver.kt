package com.phoneapprove.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.phoneapprove.app.data.DaemonLinkManager

/** Handles Allow/Allow always/Deny taps on a request notification, replying
 * directly via [DaemonLinkManager] without launching the app. Only ever
 * fires while [ConnectionService] (and thus the process hosting the
 * DaemonLinkManager singleton) is alive, since that's what keeps the
 * daemon connections open in the first place. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reqId = intent.getStringExtra(EXTRA_REQ_ID) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        DaemonLinkManager.respond(reqId, action)
        NotificationManagerCompat.from(context).cancel(reqId.hashCode())
    }

    companion object {
        const val EXTRA_REQ_ID = "req_id"
        const val EXTRA_ACTION = "action"
    }
}
