package com.noahwallet.ui.settings

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.UUID

/** Android reports the chosen target, rather than completion inside the receiving app (same as RN). */
@Composable
internal fun rememberShareCompletion(onShared: () -> Unit): IntentSender {
    val context = LocalContext.current
    val completed by rememberUpdatedState(onShared)
    val action = remember { "${context.packageName}.SHARE_CHOSEN.${UUID.randomUUID()}" }
    val callback = remember(action) {
        PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }
    DisposableEffect(context, action) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == action && intent.hasExtra(Intent.EXTRA_CHOSEN_COMPONENT)) completed()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver); callback.cancel() }
    }
    return callback.intentSender
}
