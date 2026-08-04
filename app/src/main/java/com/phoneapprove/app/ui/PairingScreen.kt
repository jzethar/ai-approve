package com.phoneapprove.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phoneapprove.app.data.BluetoothRfcomm
import com.phoneapprove.app.data.PairingInfo
import com.phoneapprove.app.data.toPairingInfo
import com.phoneapprove.app.model.QrPairingPayload
import com.phoneapprove.app.model.parseQrPayload
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
@Composable
fun PairingScreen(onPaired: (PairingInfo) -> Unit, canCancel: Boolean = false, onCancel: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var manualCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var applied by remember { mutableStateOf(false) }
    // Set instead of calling onPaired() directly when the payload carries
    // Bluetooth info this phone isn't bonded with yet - see the advisory
    // card below. Pairing itself (TCP) is never blocked on this; it's just
    // a one-tap "you might want to bond first" heads-up, same non-blocking
    // tone as the daemon's own "no LAN IP found" pairing.py warning.
    var pendingBtBonding by remember { mutableStateOf<QrPairingPayload?>(null) }

    var hasBtPermission by remember { mutableStateOf(BluetoothRfcomm.hasRuntimePermission(context)) }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasBtPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasBtPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun completePairing(payload: QrPairingPayload) {
        applied = true
        pendingBtBonding = null
        onPaired(payload.toPairingInfo())
    }

    fun tryApply(text: String) {
        if (applied) return
        val payload = parseQrPayload(text.trim())
        if (payload == null) {
            error = "That doesn't look like a valid pairing code."
            return
        }
        error = null
        val btMac = payload.bt_mac
        if (btMac != null && !BluetoothRfcomm.isBonded(context, btMac)) {
            pendingBtBonding = payload
        } else {
            completePairing(payload)
        }
    }

    // Re-checks bonding whenever the user comes back from Bluetooth settings
    // (a plain Compose state change, e.g. leaving this screen, doesn't
    // trigger this - only a real Activity lifecycle event does), so bonding
    // there and returning here auto-continues without another QR scan.
    DisposableEffect(lifecycleOwner, pendingBtBonding) {
        val payload = pendingBtBonding
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && payload != null &&
                BluetoothRfcomm.isBonded(context, payload.bt_mac!!)
            ) {
                completePairing(payload)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (canCancel) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        Text(
            "Scan the pairing QR code shown by `daemon/pairing.py` on your computer",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (hasCameraPermission) {
            // clipToBounds() is required here: Compose doesn't clip an embedded AndroidView's
            // drawing to its layout bounds by default, and the camera's hardware-accelerated
            // surface was bleeding past its 320dp box and painting over the title/paste field
            // below it even though its measured/laid-out size was already correct.
            Box(modifier = Modifier.fillMaxWidth().height(320.dp).clipToBounds()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            // PERFORMANCE mode (the default) backs this with a SurfaceView, which
                            // composites from a separate surface layer and doesn't reliably respect
                            // the Box's bounds inside a Compose AndroidView - it was bleeding outside
                            // its 320dp box and covering the title/paste field. TextureView-backed
                            // COMPATIBLE mode behaves like a normal View and stays clipped.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val scanner = BarcodeScanning.getClient()
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val image = InputImage.fromMediaImage(
                                        mediaImage, imageProxy.imageInfo.rotationDegrees
                                    )
                                    scanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                                ?.rawValue?.let { value -> tryApply(value) }
                                        }
                                        .addOnCompleteListener { imageProxy.close() }
                                } else {
                                    imageProxy.close()
                                }
                            }
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                                )
                            } catch (_: Exception) {
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    onRelease = {
                        // bindToLifecycle() only auto-unbinds on a real Activity lifecycle
                        // event (e.g. onDestroy) - switching from this screen to
                        // RequestsScreen is just a Compose state change, not one of those,
                        // so without this the camera silently keeps running (and scanning
                        // frames) in the background for as long as the app is open.
                        try {
                            ProcessCameraProvider.getInstance(context).get().unbindAll()
                        } catch (_: Exception) {
                        }
                    },
                )
            }
        } else {
            Text("Camera permission is needed to scan the QR code.")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Or paste the pairing code manually:")
        OutlinedTextField(
            value = manualCode,
            onValueChange = { manualCode = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pairing code") },
        )
        Button(onClick = { tryApply(manualCode) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Use this code")
        }
        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        pendingBtBonding?.let { payload ->
            Spacer(modifier = Modifier.height(16.dp))
            BluetoothBondingHint(
                computerName = payload.name,
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                onContinueWithoutBluetooth = { completePairing(payload) },
            )
        }
    }
}

/** Non-blocking advisory shown when the scanned/pasted pairing payload
 * carries Bluetooth info this phone isn't bonded with yet. Bluetooth is a
 * bonus transport, not a requirement - TCP already works either way - so
 * this never stops the user from finishing pairing right now. */
@Composable
private fun BluetoothBondingHint(
    computerName: String,
    onOpenSettings: () -> Unit,
    onContinueWithoutBluetooth: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "For Bluetooth to work with \"$computerName\", bond this phone with it " +
                    "first via Bluetooth settings. Pairing works over Wi-Fi either way - " +
                    "this is optional.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                TextButton(onClick = onOpenSettings) { Text("Open Bluetooth settings") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onContinueWithoutBluetooth) { Text("Continue") }
            }
        }
    }
}
