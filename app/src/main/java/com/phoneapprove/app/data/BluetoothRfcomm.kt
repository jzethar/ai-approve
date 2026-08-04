package com.phoneapprove.app.data

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Small isolated helper for the app's Bluetooth RFCOMM transport (kept
 * separate from [DaemonLinkManager]/[DeviceLink] so the reflection and
 * bonding-check logic are easy to reason about/replace on their own).
 *
 * We deliberately never scan/discover: the daemon only ever hands out a
 * fixed RFCOMM channel + its own Bluetooth MAC in the pairing payload (see
 * daemon/pairing.py), and the connecting side is expected to have already
 * bonded that device via the normal OS Bluetooth settings UI (decision:
 * secure/bonded sockets only, no insecure fallback) - so all we need here is
 * [BluetoothAdapter.getBondedDevices] plus a fixed-channel connect.
 */
object BluetoothRfcomm {

    fun hasRuntimePermission(context: Context): Boolean {
        // BLUETOOTH_CONNECT is a dangerous/runtime permission only from API 31;
        // below that, the manifest's normal BLUETOOTH permission (maxSdkVersion
        // 30) is granted automatically at install time.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    /** Bonded devices lookup itself is BLUETOOTH_CONNECT-gated on API 31+, so
     * this returns false (rather than throwing) whenever the permission isn't
     * granted yet - callers already treat "not bonded" as a reason to fall
     * back to TCP-only, which is the right behavior for "no permission" too. */
    fun isBonded(context: Context, macAddress: String): Boolean =
        findBondedDevice(context, macAddress) != null

    fun findBondedDevice(context: Context, macAddress: String): BluetoothDevice? {
        if (!hasRuntimePermission(context)) return null
        val bt = adapter(context) ?: return null
        if (!bt.isEnabled) return null
        return try {
            bt.bondedDevices?.firstOrNull { it.address.equals(macAddress, ignoreCase = true) }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Creates (but does not yet connect) a *secure* (bonded, link-encrypted)
     * RFCOMM socket to a fixed channel number, bypassing the normal
     * SDP-UUID lookup that [BluetoothDevice.createRfcommSocketToServiceRecord]
     * requires - the daemon doesn't publish an SDP record on Linux (see
     * daemon/phone_link.py's LinuxBtPhoneLink), so there's nothing to look up.
     *
     * `createRfcommSocket(int)` is `@hide`n from the public SDK but has been
     * reachable via reflection since Android 2.x and is exercised by many
     * production serial/terminal Bluetooth apps for exactly this reason.
     * "Secure" here is orthogonal to "SDP vs fixed channel": this reflects
     * into the *secure* overload (not `createInsecureRfcommSocket`), so the
     * bonded/encrypted guarantee is unaffected by skipping SDP.
     *
     * Deliberately split from calling `connect()`: callers (see
     * [DaemonLinkManager]'s TCP/Bluetooth race) need the [BluetoothSocket]
     * handle *before* the blocking `connect()` call, so a concurrent TCP win
     * can close() this socket to interrupt a still-connecting attempt rather
     * than waiting out its full connect timeout.
     *
     * Throws on any failure (reflection unavailable on some OEM build, etc.)
     * - callers treat that like any other connect failure and fall back to
     * TCP for this cycle, they don't crash.
     */
    fun createFixedChannelSocket(device: BluetoothDevice, channel: Int): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, channel) as BluetoothSocket
    }
}
