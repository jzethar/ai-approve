package com.phoneapprove.app.data

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.phoneapprove.app.model.BleUuids
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val SCAN_TIMEOUT_MS = 8000L
private const val GATT_TIMEOUT_MS = 8000L
private const val WRITE_TIMEOUT_MS = 5000L
private const val DEFAULT_CHUNK_SIZE = 20 // ATT MTU floor (23) minus the 3-byte header
private const val REQUESTED_MTU = 517 // spec max; negotiated value may come back smaller
private const val PIPE_BUFFER_SIZE = 8192
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE-central counterpart to [BluetoothRfcomm], for daemons that run a BLE
 * peripheral/GATT server instead of classic RFCOMM - i.e. macOS (see
 * daemon/bt_backend_macos.py; classic RFCOMM server mode isn't reliably
 * available to third-party apps there, unlike on Linux/BlueZ).
 *
 * Unlike [BluetoothRfcomm], this scans (filtered to the fixed service UUID
 * in [BleUuids]) rather than looking up a bonded device: a CoreBluetooth
 * peripheral's advertised BLE address has no fixed relationship to its
 * classic Bluetooth MAC, so there's nothing stable to bond/match against
 * ahead of time. No OS-level bonding is required either - the app-layer
 * ECDH+AES-GCM handshake ([SecureChannel]) authenticates the link on its
 * own, exactly as it already does for the plain-TCP transport.
 */
object BluetoothLeClient {

    fun hasScanPermission(context: Context): Boolean {
        // BLUETOOTH_SCAN/_CONNECT are dangerous/runtime permissions only
        // from API 31; below that the manifest's normal BLUETOOTH permission
        // is granted automatically at install time.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Scans for a peripheral advertising [BleUuids.SERVICE], connects,
     * discovers the service, subscribes to its TX characteristic, and
     * negotiates an MTU - blocking until that whole sequence succeeds,
     * fails, or times out. Returns null on any failure (permission,
     * adapter off, no match found, GATT setup failure) - callers treat
     * that like any other connect failure and fall back to TCP for this
     * cycle, same as [BluetoothRfcomm]'s callers already do.
     *
     * `attempt` is set to the resulting [GattStreamSocket] as soon as one
     * exists (right after a scan match, before GATT setup finishes) so a
     * concurrent TCP win (see [DaemonLinkManager]'s race) can close() it to
     * interrupt a still-connecting attempt, same contract
     * `createFixedChannelSocket`'s callers rely on.
     */
    fun connect(context: Context, attempt: AtomicReference<Closeable?>): GattStreamSocket? {
        if (!hasScanPermission(context)) return null
        val bt = adapter(context) ?: return null
        if (!bt.isEnabled) return null
        val scanner = bt.bluetoothLeScanner ?: return null

        val device = scanForService(scanner) ?: return null

        val socket = GattStreamSocket()
        attempt.set(socket)
        return try {
            socket.connectAndSetup(context, device)
            socket
        } catch (e: Exception) {
            socket.close()
            null
        }
    }

    private fun scanForService(scanner: BluetoothLeScanner): BluetoothDevice? {
        val found = AtomicReference<BluetoothDevice?>(null)
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (found.compareAndSet(null, result.device)) latch.countDown()
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(BleUuids.SERVICE)))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(listOf(filter), settings, callback)
            latch.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            found.get()
        } catch (_: SecurityException) {
            null
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            }
        }
    }
}

/**
 * A GATT connection to one BLE peripheral, wrapped in the [InputStream]/
 * [OutputStream] surface [DaemonLinkManager]'s `handshake()` (and
 * [SecureChannel] above it) already expect from [BluetoothRfcomm]'s
 * `BluetoothSocket` - so no changes are needed above this layer. Chunking
 * both directions to the negotiated MTU, and reassembling read()-side
 * chunks by simple concatenation, is exactly why this can stay a drop-in:
 * the line-JSON framing above already establishes message boundaries via
 * newlines, so the byte-level chunking here is invisible to it.
 */
class GattStreamSocket internal constructor() : Closeable {
    private val pipeIn = PipedInputStream(PIPE_BUFFER_SIZE)
    private val pipeOut = PipedOutputStream(pipeIn)
    val inputStream: InputStream get() = pipeIn
    val outputStream: OutputStream = GattOutputStream()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var chunkSize = DEFAULT_CHUNK_SIZE
    @Volatile private var closed = false

    private val readyLatch = CountDownLatch(1)
    @Volatile private var readyOk = false

    private val writeLock = Object()
    private var writeLatch: CountDownLatch? = null
    @Volatile private var writeOk = false

    /** Blocks until GATT setup finishes (or fails/times out); throws
     * [IOException] on failure so callers can treat it like any other
     * connect-phase failure. */
    fun connectAndSetup(context: Context, device: BluetoothDevice) {
        val g = device.connectGatt(context, false, GattCallbackImpl(), BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        gatt = g
        val gotResponse = readyLatch.await(GATT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!gotResponse || !readyOk) {
            throw IOException("BLE GATT setup timed out or failed")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        try { pipeOut.close() } catch (_: Exception) { }
        readyLatch.countDown() // unblock connectAndSetup() if it's still waiting
        synchronized(writeLock) { writeLatch?.countDown() }
    }

    private inner class GattOutputStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            val end = off + len
            while (offset < end) {
                val n = minOf(chunkSize, end - offset)
                writeChunk(b, offset, n)
                offset += n
            }
        }

        /** Only one GATT write may be in flight at a time (an Android BLE
         * stack requirement) - write-with-response is used specifically so
         * each chunk's [onCharacteristicWrite] ack both enforces that and
         * gives natural backpressure, no separate flow-control needed. */
        private fun writeChunk(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("BLE connection closed")
            val g = gatt ?: throw IOException("BLE connection not established")
            val char = rxChar ?: throw IOException("BLE connection not established")
            val chunk = b.copyOfRange(off, off + len)
            synchronized(writeLock) {
                if (closed) throw IOException("BLE connection closed")
                val latch = CountDownLatch(1)
                writeLatch = latch
                writeOk = false
                val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    char.value = chunk
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(char)
                }
                if (!queued) throw IOException("failed to queue BLE characteristic write")
                val gotResponse = latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!gotResponse || !writeOk) throw IOException("BLE write timed out or failed")
            }
        }
    }

    private inner class GattCallbackImpl : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readyLatch.countDown()
                return
            }
            val service = g.getService(UUID.fromString(BleUuids.SERVICE))
            val rx = service?.getCharacteristic(UUID.fromString(BleUuids.RX_CHARACTERISTIC))
            val tx = service?.getCharacteristic(UUID.fromString(BleUuids.TX_CHARACTERISTIC))
            val cccd = tx?.getDescriptor(CCCD_UUID)
            if (rx == null || tx == null || cccd == null) {
                readyLatch.countDown()
                return
            }
            rxChar = rx
            g.setCharacteristicNotification(tx, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                g.requestMtu(REQUESTED_MTU)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) {
                chunkSize = mtu - 3
            }
            readyOk = true
            readyLatch.countDown()
        }

        // Pre-API-33 callback. Only this one fires on those OS versions -
        // the 3-arg overload below isn't called unless the device is on 33+.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(characteristic.value)
        }

        // API 33+ callback - overriding it means the deprecated 2-arg one
        // above is no longer invoked on those OS versions, so this doesn't
        // double-deliver.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(value)
        }

        private fun deliver(value: ByteArray?) {
            if (value == null || closed) return
            try {
                pipeOut.write(value)
                pipeOut.flush()
            } catch (_: IOException) {
                // Pipe already closed (connection torn down) - nothing to deliver to.
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeOk = status == BluetoothGatt.GATT_SUCCESS
            synchronized(writeLock) { writeLatch?.countDown() }
        }
    }
}
