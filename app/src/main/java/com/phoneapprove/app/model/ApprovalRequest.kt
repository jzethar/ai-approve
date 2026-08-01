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
)
