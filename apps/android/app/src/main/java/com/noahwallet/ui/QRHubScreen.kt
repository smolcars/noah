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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** QRHub's code is the literal Lightning address, never the unified Receive request. */
@Composable
fun QRHubScreen(lightningAddress: String?, displayName: String, onBack: () -> Unit, onProfile: () -> Unit, onScan: (String) -> Unit) {
    val address = lightningAddress?.takeIf { it.isNotEmpty() }
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted; permissionDenied = !granted
    }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && !(context as android.app.Activity).isChangingConfigurations) scanning = false }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { if (scanning) scanning = false else onBack() }
    fun scan(value: String) { scanning = false; onScan(value) }
    if (scanning) Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { SendScanner(onClose = { scanning = false }, onScan = ::scan) }
        TextButton({ clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.trim().isNotEmpty() }?.let(::scan) },
            modifier = Modifier.fillMaxWidth()) { Text(tr("Paste")) }
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        val qrSide = AdaptiveLayout.qrSide(maxHeight.value).dp
        val wide = AdaptiveLayout.useDetailPane(maxWidth.value, maxHeight.value)
        val controls: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(onBack) { Text(tr("Back")) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton({}, Modifier.weight(1f)) { Text(tr("My code")) }
                    OutlinedButton({
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                        else permission.launch(Manifest.permission.CAMERA)
                    }, Modifier.weight(1f)) { Text(tr("Scan")) }
                }
            }
        }
        val code: @Composable () -> Unit = {
            if (address != null) ReceiveQR(address, actionLabel = "Copy Lightning address", maximumSide = qrSide) {
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
    if (permissionDenied) WalletAlertDialog(onDismissRequest = { permissionDenied = false }, title = { Text(tr("Permission required")) },
        text = { Text(tr("Camera permission is required to scan QR codes.")) },
        confirmButton = { TextButton({ permissionDenied = false }) { Text(tr("OK")) } })
}
