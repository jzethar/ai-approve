package com.phoneapprove.app.model

data class SessionNotify(
    val sessionId: String,
    val cwd: String,
    val message: String,
    val ts: Double,
    val deviceId: String,
    val deviceName: String,
)
