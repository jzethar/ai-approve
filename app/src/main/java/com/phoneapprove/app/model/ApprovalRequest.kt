package com.phoneapprove.app.model

data class ApprovalRequest(
    val reqId: String,
    val sessionId: String,
    val toolName: String,
    val toolInput: String,
    val cwd: String,
    val ts: Double,
    val deviceId: String,
    val deviceName: String,
    // Proposed answers for a question tool (see RequestMessage.options) - when
    // present, the UI offers these as buttons instead of Allow/Allow always/Deny.
    val options: List<String>? = null,
)
