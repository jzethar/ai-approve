package com.phoneapprove.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Mirrors daemon/protocol.py's message schemas - keep the two in sync. */

@Serializable
data class QrPairingPayload(
    val v: Int,
    val id: String,
    val host: String,
    val port: Int,
    val tok: String,
    val name: String,
    // Optional: absent (null) when the daemon had no usable Bluetooth adapter
    // at pairing time, in which case this pairing is TCP-only. Both default
    // to null so a v3 (TCP-only) payload still parses fine here. Classic-
    // RFCOMM-only (a Linux daemon); a macOS daemon sets bt_le instead (see
    // below), never these two.
    val bt_mac: String? = null,
    val bt_channel: Int? = null,
    // True when the daemon is macOS and runs a BLE peripheral/GATT server
    // (see daemon/bt_backend_macos.py) rather than classic RFCOMM - classic
    // RFCOMM server mode isn't reliably available to third-party apps on
    // macOS. Defaults to false so a pre-v5 payload still parses fine here.
    val bt_le: Boolean = false,
)

/** Mirrors daemon/protocol.py's BT_LE_* constants - fixed GATT UUIDs for the
 * macOS BLE transport, see BluetoothLeClient.kt. */
object BleUuids {
    const val SERVICE = "7f3a2b1a-0001-4c7a-9c1f-6f3f2b6a8e11"
    const val RX_CHARACTERISTIC = "7f3a2b1a-0002-4c7a-9c1f-6f3f2b6a8e11" // phone -> daemon (write)
    const val TX_CHARACTERISTIC = "7f3a2b1a-0003-4c7a-9c1f-6f3f2b6a8e11" // daemon -> phone (notify)
}

@Serializable
data class HelloMessage(val type: String = "hello", val tok: String)

@Serializable
data class HelloAckMessage(val type: String = "hello_ack", val ok: Boolean)

@Serializable
data class RequestMessage(
    val type: String = "request",
    val req_id: String,
    val session_id: String,
    val tool_name: String,
    val tool_input: String,
    val cwd: String,
    val ts: Double,
    // Present only for a proposed-answer tool (Claude Code's AskUserQuestion -
    // see hooks/pretooluse_approve.py's _question()); null for every ordinary
    // tool call, which keeps showing the plain Allow/Allow always/Deny row.
    val options: List<String>? = null,
)

@Serializable
data class ResponseMessage(
    val type: String = "response",
    val req_id: String,
    val action: String,
    val reply: String? = null,
)

@Serializable
data class NotifyMessage(
    val type: String = "notify",
    val session_id: String,
    val cwd: String,
    val message: String,
    val ts: Double,
)

@Serializable
data class CancelMessage(
    val type: String = "cancel",
    val req_id: String,
)

// encodeDefaults=true is required: HelloMessage/ResponseMessage's `type`
// field has a default value ("hello"/"response"), and kotlinx.serialization
// omits fields that equal their default unless told otherwise - confirmed
// via hands-on testing, where the daemon received {"tok":"..."} with no
// "type" key at all and silently rejected it as a handshake mismatch.
val protocolJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

sealed class IncomingMessage {
    data class Ack(val ok: Boolean) : IncomingMessage()
    data class Req(val message: RequestMessage) : IncomingMessage()
    data class Notify(val message: NotifyMessage) : IncomingMessage()
    data class Cancel(val reqId: String) : IncomingMessage()
    object Unknown : IncomingMessage()
}

fun parseIncoming(line: String): IncomingMessage {
    return try {
        val obj = protocolJson.parseToJsonElement(line).jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "hello_ack" -> IncomingMessage.Ack(protocolJson.decodeFromJsonElement(HelloAckMessage.serializer(), obj).ok)
            "request" -> IncomingMessage.Req(protocolJson.decodeFromJsonElement(RequestMessage.serializer(), obj))
            "notify" -> IncomingMessage.Notify(protocolJson.decodeFromJsonElement(NotifyMessage.serializer(), obj))
            "cancel" -> IncomingMessage.Cancel(protocolJson.decodeFromJsonElement(CancelMessage.serializer(), obj).req_id)
            else -> IncomingMessage.Unknown
        }
    } catch (e: Exception) {
        IncomingMessage.Unknown
    }
}

fun parseQrPayload(text: String): QrPairingPayload? {
    return try {
        protocolJson.decodeFromString(QrPairingPayload.serializer(), text)
    } catch (e: Exception) {
        null
    }
}
