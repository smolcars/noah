package com.noahwallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.noahwallet.localization.tr

internal data class WalletPrivacy(val covered: Boolean = false, val unlock: (() -> Unit)? = null)
internal val LocalWalletPrivacy = compositionLocalOf { WalletPrivacy() }

/** Dialogs have their own windows; every slot stays mounted and shields itself, even if added late. */
@Composable
internal fun WalletModalContent(cover: @Composable () -> Unit, content: @Composable () -> Unit) {
    val covered = LocalWalletPrivacy.current.covered
    Box {
        Box(Modifier.walletContentProtection(covered).onPreviewKeyEvent { covered }.pointerInput(covered) {
            if (covered) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }) { content() }
        if (covered) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) { cover() }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WalletAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    val privacy = LocalWalletPrivacy.current
    AlertDialog(onDismissRequest = { if (!privacy.covered) onDismissRequest() }, modifier = modifier.semantics { testTagsAsResourceId = true }.windowInsetsPadding(WindowInsets.safeDrawing).avoidHinge(reserveWindow = true),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        confirmButton = { WalletModalContent(cover = {
            privacy.unlock?.let { TextButton(it) { Text(tr("Unlock")) } }
        }, content = confirmButton) },
        dismissButton = dismissButton?.let { { WalletModalContent(cover = {}, content = it) } },
        icon = icon?.let { { WalletModalContent(cover = {}, content = it) } },
        title = title?.let { { WalletModalContent(cover = { Text(tr("Noah is locked")) }, content = it) } },
        text = text?.let { { WalletModalContent(cover = { Text(tr("Unlock to see your wallet.")) }, content = { Box(Modifier.verticalScroll(rememberScrollState())) { it() } }) } })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun WalletModalBottomSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    sheetMaxWidth: androidx.compose.ui.unit.Dp = AdaptiveLayout.readableWidth.dp,
    capAboveHorizontalFold: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    val privacy = LocalWalletPrivacy.current
    val hinges = LocalFoldingFeatures.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowHeight = androidx.window.layout.WindowMetricsCalculator.getOrCreate()
        .computeCurrentWindowMetrics(androidx.compose.ui.platform.LocalContext.current as android.app.Activity).bounds.height()
    val horizontalFold = if (capAboveHorizontalFold) hinges.filter { it.width > it.height }.maxOfOrNull { it.bottom } else null
    // Sheets anchor to the bottom window edge; keep their entire height below a horizontal fold.
    val sheetHeight = with(density) { (windowHeight - (horizontalFold ?: 0)).coerceAtLeast(1).toDp() }
    val verticalFolds = hinges.filter { it.height > it.width }
    ModalBottomSheet(onDismissRequest = { if (!privacy.covered) onDismissRequest() }, modifier = modifier.semantics { testTagsAsResourceId = true }.heightIn(max = sheetHeight),
        sheetMaxWidth = sheetMaxWidth,
        sheetState = sheetState, sheetGesturesEnabled = !privacy.covered, dragHandle = dragHandle?.let { handle -> {
            Box(Modifier.fillMaxWidth().heightIn(max = sheetHeight).avoidHinge(verticalFolds),
                contentAlignment = Alignment.Center) { handle() }
        } }) {
        Box(Modifier.fillMaxWidth().heightIn(max = (sheetHeight - 56.dp).coerceAtLeast(1.dp)).avoidHinge(verticalFolds)) {
            WalletModalContent(cover = {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("Noah is locked"))
                privacy.unlock?.let { NoahButton("Unlock", onClick = it) }
            }
            }) { Column(content = content) }
        }
    }
}
