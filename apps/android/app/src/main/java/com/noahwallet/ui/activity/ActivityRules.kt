package com.noahwallet.ui.activity

import com.noahwallet.history.HistoryFilter
import com.noahwallet.history.HistoryMetadata
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.history.RepeatPayment
import com.noahwallet.send.SendReconciliation
import com.noahwallet.ui.home.HomeDays
import com.noahwallet.ui.home.HomeRow
import java.util.Date
import java.util.Locale

/**
 * Activity's rules (docs/native-rewrite/design/activity.md), from history rows read the way Home reads them
 * ([HomeRow]). English sources; callers translate them.
 */
object ActivityRules {
    /** One list row: the record and how Home reads it. */
    data class Row(val record: HistoryTransaction, val home: HomeRow) {
        val id get() = record.id
    }
    fun rows(records: List<HistoryTransaction>, ownDomain: String?) = records.map { Row(it, HomeRow.of(it, ownDomain)) }

    sealed interface Group {
        data object Pending : Group
        data class Day(val day: HomeDays.Day) : Group
    }

    /** "Pending" first (everything still moving), then days, newest first; undated settled rows join the day above. */
    fun groups(rows: List<Row>, now: Date): List<Pair<Group, List<Row>>> {
        val (pending, settled) = rows.partition { it.home.pending }
        return listOfNotNull(pending.takeIf { it.isNotEmpty() }?.let { Group.Pending to it }) +
            HomeDays.grouped(settled, { it.home.date }, now).map { (day, list) -> Group.Day(day) to list }
    }

    /** Everything search looks in: names, notes, kinds, IDs and hashes; [words] adds the translated words shown. */
    fun haystack(row: Row, words: List<String> = emptyList()): String = (listOf(row.home.title, row.home.counterparty, row.home.kind,
        row.home.state, row.record.destination, row.record.repeatPayment?.destination, row.record.repeatPayment?.comment,
        row.record.description, row.record.recurring?.label, row.record.displayLabel, row.record.txid, row.record.paymentHash,
        row.record.id, row.record.movementId?.toString()) + words).filterNotNull().joinToString("\n").lowercase(Locale.ROOT)

    private val separators = setOf(',', '.', ' ', ' ', ' ', '\'', '_')

    /** Case-insensitive; the amount shown matches with or without separators or a leading ₿ (none shown, none matched). */
    fun matches(haystack: String, amount: Long?, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT).removePrefix("₿").trim()
        if (q.isEmpty()) return true
        val digits = q.filterNot { it in separators }
        if (digits.isNotEmpty() && digits.all(Char::isDigit) && amount?.toString()?.contains(digits) == true) return true
        return haystack.contains(q)
    }

    fun visible(rows: List<Row>, filter: HistoryFilter, query: String, haystacks: Map<String, String>): List<Row> =
        rows.filter { filter.includes(it.record) && matches(haystacks[it.id] ?: haystack(it), it.home.amount, query) }

    /** The rows Export CSV writes: today's export rows narrowed by the filter, never the newly shown failures. */
    fun exported(records: List<HistoryTransaction>, filter: HistoryFilter) = records.filter { filter.includes(it) && it.exported }

    /** The filter's name on the chip and in the menu. */
    fun filterName(filter: HistoryFilter) = when (filter) {
        HistoryFilter.All -> "All"; HistoryFilter.Lightning -> "Lightning"; HistoryFilter.Ark -> "Ark"; HistoryFilter.Onchain -> "On-chain"
    }
    /** The empty filtered list. */
    fun noneMessage(filter: HistoryFilter) = when (filter) {
        HistoryFilter.Lightning -> "No Lightning payments"; HistoryFilter.Ark -> "No Ark payments"
        HistoryFilter.Onchain -> "No on-chain payments"; HistoryFilter.All -> "No activity yet"
    }

    /** The rail a row moved on, for Via and the receipt. */
    enum class Rail(val label: String) { ark("Ark"), round("Ark round"), lightning("Lightning"), offer("Lightning offer"), chain("On-chain") }
    /** The rail it actually moved on: bark's subsystem first (a Noah user paid by address goes over Ark), then the type. */
    fun rail(record: HistoryTransaction): Rail = when {
        record.isRenewal -> Rail.round
        record.offer != null -> Rail.offer
        record.subsystemName in listOf("bark.lightning_send", "bark.lightning_receive") -> Rail.lightning
        record.subsystemName == "bark.arkoor" -> Rail.ark
        record.type == "Onchain" || record.source == "onchain-wallet" -> Rail.chain
        record.type == "Bolt11" || record.type == "Lnurl" -> Rail.lightning
        record.type == "Arkoor" -> Rail.ark
        else -> Rail.chain
    }
    /** "Free" is a claim only Ark and Lightning sends can make; on-chain kinds without a recorded fee show none. */
    fun offchain(rail: Rail) = rail != Rail.chain

    /**
     * The on-chain transaction a payment made, from explicit evidence only: the on-chain wallet's own transaction, or
     * the offboard txid, board anchor or a renewal's round funding txid bark recorded. Never a coin's ID: before an offboard's transaction exists, that
     * names the coin's parent transaction, which this payment didn't make.
     */
    fun chainTxid(record: HistoryTransaction): String? {
        val txid = when (record.source) {
            "onchain-wallet" -> record.txid
            "ark" -> HistoryMetadata.parse(record.metadataJson ?: "").let {
                it.offboardTxid ?: it.chainAnchor?.substringBefore(':') ?: it.fundingTxid?.takeIf { record.isRenewal }
            }
            else -> null
        }
        return txid?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
    /** The explorer page for [chainTxid], with today's per-network links. */
    fun explorerURL(record: HistoryTransaction, variant: String) = chainTxid(record)?.let { record.copy(txid = it).explorerURL(variant) }

    /**
     * The one status line under the amount (English source and its tone). [coins]: a renewal's coin count. Unknown
     * statuses keep today's "Recorded".
     */
    enum class Tone { plain, pending, failed, muted }
    /** The one status with a plural resource: only a settled renewal counts its coins. */
    const val renewed = "Renewed {n} coins"
    fun status(row: Row): Pair<String, Tone> {
        val record = row.record; val home = row.home
        return when {
            home.refunded -> "Refunded · back in your wallet" to Tone.failed
            home.failed -> (if (home.unchanged) "Failed · nothing left your wallet" else "Failed") to Tone.failed
            home.canceled -> (if (home.unchanged) "Canceled · nothing left your wallet" else "Canceled") to Tone.muted
            home.pending -> pendingStatus(record, home) to Tone.pending
            record.receiptStatus == "Recorded" -> "Recorded" to Tone.plain
            record.isRenewal -> renewed to Tone.plain
            record.isInternalTransfer -> "Moved to Ark" to Tone.plain
            record.movementKind == "exit" -> "Exited" to Tone.plain
            home.incoming -> "Received" to Tone.plain
            else -> "Sent" to Tone.plain
        }
    }
    private fun pendingStatus(record: HistoryTransaction, home: HomeRow): String = when {
        record.movementKind == "exit" -> "Exiting"
        record.movementKind in listOf("offboard", "send-onchain") ->
            if (HistoryMetadata.parse(record.metadataJson ?: "").offboardTxid == null) "Sending · waiting for an Ark round"
            else "Sending · waiting for confirmations"
        record.isInternalTransfer -> "Moving to Ark"
        home.incoming -> "Arriving"
        rail(record) == Rail.lightning || rail(record) == Rail.offer -> "Sending · still finding a route"
        else -> "Sending"
    }
    /** Failed, refunded and canceled amounts are struck through and have no value at the time. */
    fun struck(home: HomeRow) = home.failed || home.canceled

    /**
     * Who the payment went to, as the record names it: a Lightning address, an on-chain or Ark address. Invoices and
     * offers name no one (they're in Details), and incoming records never name a sender.
     */
    fun recipient(record: HistoryTransaction): String? {
        // An exit pays your own on-chain address.
        if (record.direction != "outgoing" || record.isRenewal) return null
        if (record.isInternalTransfer || record.movementKind == "exit") return null
        // Noah pays a Lightning address with the invoice it fetched, so bark records the invoice; the address is in
        // the repeat metadata Noah wrote.
        record.repeatPayment?.destination?.takeIf { it.isNotEmpty() }?.let { return it }
        if (record.invoice != null || record.offer != null) return null
        return record.destination?.takeIf { it.isNotEmpty() }
    }
    /** Lightning and Noah addresses read as words; everything else is an identifier shown in monospace. */
    fun readable(value: String) = RepeatPayment.normalizeLightningAddress(value) != null

    /** The successful Lightning send's preimage: its proof of payment. Never a receive's. */
    fun proof(record: HistoryTransaction) = record.preimage?.takeIf { record.direction == "outgoing" && record.movementStatus == "successful" }

    /** A fee row's value: a proven amount, the most a pending Lightning send may take, "Free" or "None". */
    sealed interface FeeValue {
        data class Sats(val sats: Long) : FeeValue
        data class UpTo(val sats: Long) : FeeValue
        data object Free : FeeValue
        data object None : FeeValue
    }
    data class FeeLine(val label: String, val value: FeeValue)

    /**
     * The Ark fee a record proves, zero included; null when it proves none. A settled Lightning send's is what its
     * effective balance took beyond what it sent (Send's reconciliation rule), because bark's reused movement keeps
     * a retried invoice's first fee field. An amountless invoice may have been retried for another amount, so, as
     * with Send's already-paid receipts, it proves no fee.
     */
    fun arkFee(record: HistoryTransaction): Long? {
        if (record.source != "ark") return null
        if (record.subsystemName == "bark.lightning_send" && record.movementStatus == "successful") return lightningSendFee(record)
        return record.offchainFeeSat
    }
    private fun lightningSendFee(record: HistoryTransaction): Long? {
        if (record.invoice?.let { !SendReconciliation.fixesAmount(it) } == true) return null
        val sent = runCatching { record.sentTo.orEmpty().fold(0L) { sum, d -> Math.addExact(sum, d.amountSat.also { require(it >= 0) }) } }
            .getOrNull()?.takeIf { it > 0 } ?: return null
        return SendReconciliation.lightningFee(record.effectiveBalanceSat ?: return null, sent)
    }
    /** On-chain fees count for payments out and boards only. */
    fun networkFee(record: HistoryTransaction) = record.onchainFeeSat?.takeIf {
        it > 0 && (record.direction == "outgoing" || record.isInternalTransfer) && (record.source == "ark" || record.hasOnchainFee == true)
    }

    /** The fee rows a payment shows, Activity's and Home's sheet alike. */
    fun fees(record: HistoryTransaction, home: HomeRow): List<FeeLine> {
        val rail = rail(record)
        if (struck(home)) return listOf(FeeLine("Fee", FeeValue.None))
        val lightning = rail == Rail.lightning || rail == Rail.offer
        if (home.pending && !home.incoming && lightning)
            return listOf(FeeLine("Fee", record.offchainFeeSat?.takeIf { it > 0 }?.let(FeeValue::UpTo) ?: FeeValue.Free))
        if (record.isRenewal) return listOf(FeeLine("Fee", FeeValue.Sats(record.amount)))
        return settledFees(record, rail)
    }
    private fun settledFees(record: HistoryTransaction, rail: Rail): List<FeeLine> {
        val ark = arkFee(record)
        val paid = ark?.takeIf { it > 0 }
        val network = networkFee(record)
        return when {
            paid != null && network != null -> listOf(FeeLine("Ark fee", FeeValue.Sats(paid)), FeeLine("Network fee", FeeValue.Sats(network)))
            network != null -> listOf(FeeLine("Network fee", FeeValue.Sats(network)))
            // A receive or a board shows the fee it paid (a Lightning receive's, a board's Ark fee); "Free" stays a claim for sends.
            record.direction != "outgoing" -> listOfNotNull(paid?.let { FeeLine("Fee", FeeValue.Sats(it)) })
            paid != null -> listOf(FeeLine("Fee", FeeValue.Sats(paid)))
            // "Free" is a claim only a proven zero on Ark or Lightning can make; a fee nothing proves has no row.
            ark == 0L && offchain(rail) -> listOf(FeeLine("Fee", FeeValue.Free))
            else -> emptyList()
        }
    }
    /**
     * Amount plus the fees shown, on sends only, and only where the amount excludes them: an on-chain wallet send's
     * amount is its whole balance change and an offboard's is what left the Ark balance, so they have no separate total.
     */
    fun total(record: HistoryTransaction, home: HomeRow): Long? {
        if (record.direction != "outgoing" || record.isRenewal || struck(home)) return null
        if (record.source == "onchain-wallet" || record.movementKind == "offboard") return null
        val fees = fees(record, home).sumOf { when (val value = it.value) { is FeeValue.Sats -> value.sats; is FeeValue.UpTo -> value.sats; else -> 0L } }
        return if (fees > 0) record.amount + fees else null
    }

    /**
     * The plain-text receipt Share sends: amount, date, To, rail, and the transaction ID (on-chain) or proof of payment
     * (successful Lightning sends). Never Details, the raw transaction or a receive's preimage. [t] translates.
     */
    fun receipt(row: Row, amount: String?, date: String?, txid: String?, t: (String, Array<out Any>) -> String): String {
        val record = row.record
        val status = status(row).first
        fun line(label: String, value: String) = t(label, emptyArray()) + ": " + value
        return listOfNotNull(
            t(status, if (status == renewed) arrayOf(record.inputVtxos.orEmpty().size.toString()) else emptyArray()) + amount?.let { " $it" }.orEmpty(),
            date?.let { line("Date", it) },
            recipient(record)?.let { line("To", it) },
            line("Via", t(rail(record).label, emptyArray())),
            txid?.let { line("Transaction ID", it) },
            proof(record)?.let { line("Proof of payment", it) },
        ).joinToString("\n")
    }

    /**
     * Activity and Home share one in-memory "new" set: rows Home hasn't seen ([seen] is Home's, null until it has
     * loaded once). Activity adds a row once its dot has been on screen, so Home counts it as seen too.
     */
    fun newRows(rows: List<HistoryTransaction>, seen: Set<String>?): Set<String> =
        if (seen == null) emptySet() else rows.filter { HomeRow.received(it) && it.id !in seen }.map { it.id }.toSet()
    fun seen(seen: Set<String>?, id: String): Set<String>? = seen?.plus(id)
}
