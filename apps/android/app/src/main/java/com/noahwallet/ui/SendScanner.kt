package com.noahwallet.ui

import com.noahwallet.localization.tr
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable fun SendScanner(onClose: () -> Unit, onScan: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = androidx.compose.ui.platform.LocalView.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val scan by rememberUpdatedState(onScan)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> permitted = allowed; denied = !allowed }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onClose)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().readableWidth().then(if (!permitted) Modifier.verticalScroll(scroll) else Modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("Scan QR code"), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onClose) { Text(tr("Close")) }
        }
        if (permitted) {
            val preview = remember { PreviewView(context) }
            val executor = remember { Executors.newSingleThreadExecutor() }
            DisposableEffect(owner, preview) {
                val active = AtomicBoolean(true); val scanned = AtomicBoolean(false)
                val future = ProcessCameraProvider.getInstance(context)
                val main = ContextCompat.getMainExecutor(context)
                val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                val analysis = ImageAnalysis.Builder().setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val cameraPreview = Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider = preview.surfaceProvider }
                val displays = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) {}
                    override fun onDisplayRemoved(displayId: Int) {}
                    override fun onDisplayChanged(displayId: Int) {
                        if (view.display?.displayId == displayId) {
                            val current = view.display?.rotation ?: android.view.Surface.ROTATION_0
                            analysis.targetRotation = current
                            cameraPreview.targetRotation = current
                        }
                    }
                }
                displays.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
                val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13), DecodeHintType.TRY_HARDER to true)) }
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (!active.get() || scanned.get()) return@setAnalyzer
                        val plane = image.planes[0]; val buffer = plane.buffer; val origin = buffer.position()
                        val luminance = ByteArray(image.width * image.height)
                        for (y in 0 until image.height) for (x in 0 until image.width) luminance[y * image.width + x] = buffer.get(origin + y * plane.rowStride + x * plane.pixelStride)
                        val value = QRFrameDecoder.decode(luminance, image.width, image.height, image.imageInfo.rotationDegrees, reader)
                        if (value != null && scanned.compareAndSet(false, true)) main.execute { if (active.get()) scan(value) }
                    } catch (_: Exception) { /* Unreadable frames are expected; never log a scanned payload. */ }
                    finally { image.close(); reader.reset() }
                }
                future.addListener({
                    if (active.get()) try {
                        val provider = future.get()
                        if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) cameraError = "No camera is available. You can paste the payment request instead."
                        else provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
                    } catch (_: Exception) { cameraError = "Camera unavailable. Close the scanner and try again." }
                }, main)
                onDispose {
                    active.set(false); analysis.clearAnalyzer(); displays.unregisterDisplayListener(displayListener)
                    if (future.isDone) runCatching { future.get().unbind(cameraPreview, analysis) }
                    executor.shutdown()
                }
            }
            AndroidView(factory = { preview }, modifier = Modifier.weight(1f).fillMaxWidth())
            Text(tr("Point your camera at a payment QR code."))
        }
        cameraError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
        if (denied) {
            Text(tr("Permission required"), style = MaterialTheme.typography.titleLarge)
            Text(tr("Camera permission is required to scan QR codes."))
            NoahButton("Allow camera") { permission.launch(Manifest.permission.CAMERA) }
            TextButton(onClick = {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
            }) { Text(tr("Open settings")) }
        }
    }
}

/** Y-plane only; respect row/pixel strides before calling. Rotation is testable without a camera. */
internal object QRFrameDecoder {
    fun decode(bytes: ByteArray, width: Int, height: Int, rotation: Int, reader: MultiFormatReader = MultiFormatReader()): String? {
        require(width > 0 && height > 0 && bytes.size == width * height)
        val turns = ((rotation % 360 + 360) % 360) / 90
        var data = bytes; var w = width; var h = height
        repeat(turns) {
            val next = ByteArray(data.size)
            for (y in 0 until h) for (x in 0 until w) next[x * h + (h - 1 - y)] = data[y * w + x]
            data = next; val old = w; w = h; h = old
        }
        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
    }
}
