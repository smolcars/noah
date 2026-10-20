package com.noahwallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable
internal fun WalletAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    val privacy = LocalWalletPrivacy.current
    AlertDialog(onDismissRequest = { if (!privacy.covered) onDismissRequest() }, modifier = modifier,
        confirmButton = { WalletModalContent(cover = {
            privacy.unlock?.let { TextButton(it) { Text(tr("Unlock")) } }
        }, content = confirmButton) },
        dismissButton = dismissButton?.let { { WalletModalContent(cover = {}, content = it) } },
        icon = icon?.let { { WalletModalContent(cover = {}, content = it) } },
        title = title?.let { { WalletModalContent(cover = { Text(tr("Noah is Locked")) }, content = it) } },
        text = text?.let { { WalletModalContent(cover = { Text(tr("Authenticate to access your wallet")) }, content = it) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletModalBottomSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit) {
    val privacy = LocalWalletPrivacy.current
    ModalBottomSheet(onDismissRequest = { if (!privacy.covered) onDismissRequest() }, modifier = modifier,
        sheetState = sheetState, sheetGesturesEnabled = !privacy.covered, dragHandle = dragHandle) {
        WalletModalContent(cover = {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("Noah is Locked"))
                privacy.unlock?.let { NoahButton("Unlock", onClick = it) }
            }
        }) { Column(content = content) }
    }
}
