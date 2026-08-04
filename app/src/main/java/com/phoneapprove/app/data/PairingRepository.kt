package com.phoneapprove.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.phoneapprove.app.model.QrPairingPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PairingInfo(
    val id: String, val host: String, val port: Int, val token: String, val name: String,
    // Optional: null means this pairing is TCP-only (the daemon had no usable
    // Bluetooth adapter when it was paired) - see QrPairingPayload.
    val btMac: String? = null, val btChannel: Int? = null,
)

fun QrPairingPayload.toPairingInfo() = PairingInfo(id, host, port, tok, name, bt_mac, bt_channel)

private val json = Json { ignoreUnknownKeys = true }
private val pairingListSerializer = ListSerializer(PairingInfo.serializer())

/** EncryptedSharedPreferences-backed storage for paired computers - each
 * token authenticates that computer's TCP link, so it's treated like any
 * other credential. Supports multiple simultaneous pairings, keyed by the
 * daemon's stable per-machine id (not its host/IP, which can change on
 * DHCP renewal): re-scanning a computer's QR again (e.g. to rotate its
 * token, or after its IP changed) updates that entry in place rather than
 * adding a duplicate. */
class PairingRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "phone_approve_pairing",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(info: PairingInfo) {
        writeAll(loadAll().filterNot { it.id == info.id } + info)
    }

    fun loadAll(): List<PairingInfo> {
        val raw = prefs.getString(KEY_PAIRINGS, null) ?: return emptyList()
        return try {
            json.decodeFromString(pairingListSerializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun remove(id: String) {
        writeAll(loadAll().filterNot { it.id == id })
    }

    private fun writeAll(pairings: List<PairingInfo>) {
        prefs.edit().putString(KEY_PAIRINGS, json.encodeToString(pairingListSerializer, pairings)).apply()
    }

    companion object {
        private const val KEY_PAIRINGS = "pairings"
    }
}
