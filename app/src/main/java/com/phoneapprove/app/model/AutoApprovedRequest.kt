package com.phoneapprove.app.model

/** A record of a request that was answered automatically (always with
 * "allow", never "allow_always") because Settings' auto-approve toggle was
 * on when it arrived - see SettingsRepository.autoApproveFlow and
 * DaemonLinkManager's onRequest handling. Purely a history entry, like
 * [SessionNotify]: nothing left to act on, just kept around so the user can
 * review what was approved on their behalf and clear it. */
data class AutoApprovedRequest(
    val reqId: String,
    val toolName: String,
    val toolInput: String,
    val cwd: String,
    val ts: Double,
    val deviceId: String,
    val deviceName: String,
)
