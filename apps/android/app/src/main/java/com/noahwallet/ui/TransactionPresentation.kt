package com.noahwallet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.noahwallet.R
import com.noahwallet.history.*
import java.text.DateFormat

/** Today's transaction-detail sheet's icon, colour and date (Home's rows still open it). */
internal fun transactionIcon(row: HistoryTransaction) = when {
    row.movementKind == "onboard" -> R.drawable.ic_receive
    row.movementKind == "offboard" -> R.drawable.ic_send
    row.type in listOf("Bolt11", "Lnurl") -> R.drawable.ic_history_lightning
    row.type == "Arkoor" -> R.drawable.ic_history_ark
    else -> R.drawable.ic_history_chain
}
@Composable
internal fun transactionColor(row: HistoryTransaction): Color {
    val dark = isSystemInDarkTheme()
    return when {
        row.isCanceled -> MaterialTheme.colorScheme.onSurfaceVariant
        row.isInternalTransfer -> if (dark) Color(0xFFFDBA74) else Color(0xFF945200)
        row.direction == "outgoing" -> if (dark) Color(0xFFFF8080) else Color(0xFFB42318)
        else -> if (dark) Color(0xFF4ADE80) else Color(0xFF137A36)
    }
}
internal fun HistoryTransaction.formattedDate(recent: Boolean = false): String = confirmationLabel ?: timestamp?.let {
    if (recent) DateFormat.getDateInstance(DateFormat.MEDIUM).format(it)
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it)
} ?: date
