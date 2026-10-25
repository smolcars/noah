package com.noahwallet.ui

import com.noahwallet.ui.WalletAlertDialog

import com.noahwallet.localization.tr
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted; permissionDenied = !granted
    }
    // Opened from Home's scan button: ask for the camera once.
    LaunchedEffect(Unit) { if (startScanning && !cameraGranted && !askedToScan) { askedToScan = true; permission.launch(Manifest.permission.CAMERA) } }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    fun close() { scanning = false; if (startScanning) onBack() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && !(context as android.app.Activity).isChangingConfigurations && scanning) close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { if (scanning) close() else onBack() }
    fun scan(value: String) { scanning = false; onScan(value) }
    if (scanning) Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { SendScanner(onClose = ::close, onScan = ::scan) }
        TextButton({ clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.trim().isNotEmpty() }?.let(::scan) },
            modifier = Modifier.fillMaxWidth()) { Text(tr("Paste")) }
    } else if (!startScanning) BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = AdaptiveLayout.useDetailPane(maxWidth.value, maxHeight.value)
        val qrSide = AdaptiveLayout.qrSide(maxHeight.value - if (wide) 0f else 160f).dp
        val controls: @Composable () -> Unit = { TextButton(onBack) { Text(tr("Back")) } }
        val code: @Composable () -> Unit = {
            if (address != null) LightningAddressQR(address, qrSide) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Lightning address", address)); copied = true
            }
        }
        val identity: @Composable () -> Unit = {
            if (address != null) Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (displayName.trim().isNotEmpty()) Text(tr(displayName), style = MaterialTheme.typography.titleLarge)
                Text(tr(if (copied) "Copied" else address.replace("@", "@\u200b").replace(".", ".\u200b")),
                    Modifier.clickable { clipboard.setPrimaryClip(ClipData.newPlainText("Lightning address", address)); copied = true })
                Text(tr("This QR contains only your Lightning address."))
            }
        }
        NoahForm("QR Code") {
            if (wide && address != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.weight(1f)) { code() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { controls(); identity() }
            } else {
                controls()
                if (address != null) { code(); identity() }
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
    if (permissionDenied) WalletAlertDialog(onDismissRequest = { permissionDenied = false; close() }, title = { Text(tr("Permission required")) },
        text = { Text(tr("Camera permission is required to scan QR codes.")) },
        confirmButton = { TextButton({ permissionDenied = false; close() }) { Text(tr("OK")) } })
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
