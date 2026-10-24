package com.noahwallet.history

object HistoryCSV {
    fun content(transactions: List<HistoryTransaction>, currency: String): String {
        val header = listOf("Payment ID", "Date", "Type", "Status", "Direction", "Amount (sats)",
            "BTC Price ($currency)", "Transaction ID", "Destination")
        // The amount the row proves, signed as the accounting signs it; blank when it proves none.
        val rows = transactions.map { row -> listOf(row.id, row.confirmationLabel ?: row.date.take(10),
            row.displayLabel, row.statusLabel ?: "", row.accounting.direction,
            row.provenAmount?.let { row.copy(amount = it).accounting.amount.toString() } ?: "",
            row.btcPrice?.toString() ?: "", row.txid ?: "", row.destination ?: "") }
        return (listOf(header.map { escape(it) }) + rows.map { row ->
            row.mapIndexed { index, value -> escape(value, numeric = index == 5 || index == 6) }
        }).joinToString("\n", postfix = "\n") { it.joinToString(",") }
    }
    private fun escape(raw: String, numeric: Boolean = false): String {
        val value = if (!numeric && raw.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'" + raw else raw
        return if (value.any { it in ",\"\n\r" }) "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
