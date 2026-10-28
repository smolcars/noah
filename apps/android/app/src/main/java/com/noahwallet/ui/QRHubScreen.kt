package com.noahwallet.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.localization.tr
import com.noahwallet.ui.WalletAlertDialog
import kotlinx.coroutines.delay

/**
 * QRHub's code is the literal Lightning address, never the unified Receive request. It shows one thing: My code, or with
 * [startScanning] (Home's scan button) the scanner alone, which goes back once the camera closes or is refused.
 */
@Composable
fun QRHubScreen(lightningAddress: String?, displayName: String, onBack: () -> Unit, onProfile: () -> Unit, onScan: (String) -> Unit,
    startScanning: Boolean = false) {
    val address = lightningAddress?.takeIf { it.isNotEmpty() }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val cameraGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var scanning by rememberSaveable { mutableStateOf(startScanning && cameraGranted) }
    var askedToScan by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted; permissionDenied = !granted
    }
    // Opened from Home's scan button: ask for the camera once.
    LaunchedEffect(Unit) { if (startScanning && !cameraGranted && !askedToScan) { askedToScan = true; permission.launch(Manifest.permission.CAMERA) } }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    fun close() { scanning = false; if (startScanning) onBack() }
    CloseScannerOnStop({ scanning }, ::close)
    BackHandler { if (scanning) close() else onBack() }
    fun scan(value: String) { scanning = false; onScan(value) }
    fun copy(value: String) { clipboard.setPrimaryClip(ClipData.newPlainText("Lightning address", value)); copied = true }
    if (scanning) Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { SendScanner(onClose = ::close, onScan = ::scan) }
        TextButton({ clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.trim().isNotEmpty() }?.let(::scan) },
            modifier = Modifier.fillMaxWidth()) { Text(tr("Paste")) }
    } else if (!startScanning) MyCode(address, displayName, copied, onBack, onProfile, ::copy)
    if (permissionDenied) WalletAlertDialog(onDismissRequest = { permissionDenied = false; close() }, title = { Text(tr("Permission required")) },
        text = { Text(tr("Camera permission is required to scan QR codes.")) },
        confirmButton = { TextButton({ permissionDenied = false; close() }) { Text(tr("OK")) } })
}

/** Leaving the app (not a rotation) while [scanning] closes the scanner. */
@Composable
private fun CloseScannerOnStop(scanning: () -> Boolean, close: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true && scanning()) close()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** Your Lightning address as a QR, with your name and the address (tap either to [copy]); beside each other when wide. */
@Composable
private fun MyCode(address: String?, displayName: String, copied: Boolean, onBack: () -> Unit, onProfile: () -> Unit, copy: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = AdaptiveLayout.useDetailPane(maxWidth.value, maxHeight.value)
        val qrSide = AdaptiveLayout.qrSide(maxHeight.value - if (wide) 0f else 160f).dp
        val controls: @Composable () -> Unit = { TextButton(onBack) { Text(tr("Back")) } }
        NoahForm("QR Code") {
            if (wide && address != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f)) { LightningAddressQR(address, qrSide) { copy(address) } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { controls(); Identity(address, displayName, copied, copy) }
            } else {
                controls()
                if (address != null) { LightningAddressQR(address, qrSide) { copy(address) }; Identity(address, displayName, copied, copy) }
                else Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("Lightning address unavailable"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Finish setting up your Lightning address before sharing your QR."))
                        NoahButton("Open Profile", onClick = onProfile)
                    }
                }
            }
        }
    }
}

@Composable
private fun Identity(address: String, displayName: String, copied: Boolean, copy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (displayName.trim().isNotEmpty()) Text(tr(displayName), style = MaterialTheme.typography.titleLarge)
        Text(tr(if (copied) "Copied" else address.replace("@", "@\u200b").replace(".", ".\u200b")), Modifier.clickable { copy(address) })
        Text(tr("This QR contains only your Lightning address."))
    }
}

/** My code: the styled QR of the literal Lightning address on its always-white tile; tapping copies it. */
@Composable
private fun LightningAddressQR(address: String, maximumSide: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val label = tr("Copy Lightning address")
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    Box(Modifier.sizeIn(maxWidth = maximumSide).aspectRatio(1f).clip(shape)
        .background(com.noahwallet.ui.design.NoahTokens.Light.qrCard)
        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = label, onClick = onClick)
        .clearAndSetSemantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button; this.onClick(label) { onClick(); true } }
        .padding(20.dp)) {
        com.noahwallet.ui.home.StyledQR(address, Modifier.fillMaxSize(), reduceMotion = com.noahwallet.ui.onboarding.rememberReduceMotion())
    }
}
