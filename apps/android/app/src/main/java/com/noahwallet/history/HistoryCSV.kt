package com.noahwallet.history

object HistoryCSV {
    fun content(transactions: List<HistoryTransaction>, currency: String): String {
        val header = listOf("Payment ID", "Date", "Type", "Status", "Direction", "Amount (sats)",
            "BTC Price ($currency)", "Transaction ID", "Destination")
        val rows = transactions.map { row -> listOf(row.id, row.confirmationLabel ?: row.date.take(10),
            row.displayLabel, row.statusLabel ?: "", row.accounting.direction, row.accounting.amount.toString(),
            row.btcPrice?.toString() ?: "", row.txid ?: "", row.destination ?: "") }
        return (listOf(header) + rows).joinToString("\n", postfix = "\n") { row -> row.joinToString(",") { escape(it) } }
    }
    private fun escape(value: String) = if (value.any { it in ",\"\n\r" }) "\"${value.replace("\"", "\"\"")}\"" else value
}
