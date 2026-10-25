import Foundation

/// How a payment's detail reads (activity.md, "Detail"). Only what the record holds.
struct ActivityDetail: Equatable {
    enum Tone: Equatable { case plain, pending, failure, muted }
    enum Rail: Equatable { case ark, arkRound, lightning, lightningOffer, onchain }
    enum Fee: Equatable {
        case free, nothing, upTo(Int64), amount(Int64)
        /// "Ark fee" and "Network fee".
        case split(ark: Int64, network: Int64)
        case network(Int64)
    }

    /// Source strings, localized by the view; "Renewed {n} coins" takes `renewedCoins`.
    let status: String
    let renewedCoins: Int?
    let tone: Tone
    let rail: Rail
    /// The recipient, for sends that name one: an address, never an invoice or offer.
    let to: String?
    let toIsAddress: Bool
    let fee: Fee?
    /// What left the wallet, when fees make it differ from the amount.
    let total: Int64?
    /// Shown struck through and muted.
    let unsuccessful: Bool
    /// The proof of payment: a successful Lightning send's preimage.
    let proof: String?
    /// An on-chain transaction ID, with the explorer.
    let chainTxid: String?
    /// Waiting for (or counting) confirmations: Send's block row.
    let blockTxid: String?
    let note: String?
    /// A confirmed transaction whose block time isn't known has no date; Confirmed names its block.
    let showsDate: Bool

    init(_ transaction: HistoryTransaction, ownDomain: String) {
        let row = HomeActivityRow(transaction, ownDomain: ownDomain)
        let outgoing = transaction.direction == "outgoing"
        let lightning = ["Bolt11", "Lnurl"].contains(transaction.type)
        let methods = (outgoing ? transaction.sentTo : transaction.receivedOn) ?? []
        let walletRow = transaction.source == "onchain-wallet"
        let onchain = row.avatar == .chain || walletRow
        unsuccessful = row.status.isUnsuccessful
        renewedCoins = transaction.isRenewal ? (transaction.inputVtxos?.count ?? 0) : nil

        switch row.status {
        case .refunded: status = "Refunded · back in your wallet"; tone = .failure
        case .failed(let none): status = none ? "Failed · nothing left your wallet" : "Failed"; tone = .failure
        case .canceled(let none): status = none ? "Canceled · nothing left your wallet" : "Canceled"; tone = .muted
        case .exiting: status = "Exiting"; tone = .pending
        case .inRound: status = "Sending · waiting for an Ark round"; tone = .pending
        case .sending, .confirming:
            tone = .pending
            if !outgoing { status = "Arriving" }
            else if lightning { status = "Sending · still finding a route" }
            else if onchain { status = "Sending · waiting for confirmations" }
            else { status = "Sending" }
        case .done:
            tone = .plain
            // An unknown status stays "Recorded": only a completed renewal reads "Renewed {n} coins".
            if transaction.receiptStatus == "Recorded" { status = "Recorded" }
            else if transaction.isRenewal { status = (renewedCoins ?? 0) > 0 ? "Renewed {n} coins" : "Renewed" }
            else if transaction.isInternalTransfer { status = "Moved to Ark" }
            else if transaction.movementKind == "exit" { status = "Exited" }
            else { status = outgoing ? "Sent" : "Received" }
        }

        // What the record shows it moved over comes first: a Lightning address paid over Ark keeps the legacy
        // "Lnurl" type (the filter still groups it by type), but bark recorded it as an Ark send.
        let recorded = Set(methods.compactMap(\.paymentMethod))
        let offer = recorded.contains("offer")
        if transaction.isRenewal { rail = .arkRound }
        else if onchain || recorded.contains("bitcoin") { rail = .onchain }
        else if ["bark.lightning_send", "bark.lightning_receive"].contains(transaction.subsystemName) { rail = offer ? .lightningOffer : .lightning }
        else if transaction.subsystemName == "bark.arkoor" || recorded.contains("ark") { rail = .ark }
        else if lightning { rail = offer ? .lightningOffer : .lightning }
        else if transaction.type == "Onchain" { rail = .onchain }
        else { rail = .ark }

        // Only an address names the recipient; incoming records hold our own receiving method.
        let named = outgoing ? (transaction.repeatPayment?.destination ?? transaction.destination) : nil
        if let named, let address = RepeatPayment.normalizeLightningAddress(named) {
            to = address
            toIsAddress = false
        } else if let named, !named.isEmpty, methods.contains(where: { ["bitcoin", "ark"].contains($0.paymentMethod) }) {
            to = named
            toIsAddress = true
        } else {
            to = nil
            toIsAddress = false
        }

        let settledLightningSend = outgoing && lightning && transaction.subsystemName == "bark.lightning_send"
            && transaction.movementStatus == "successful"
        // A settled Lightning send's fee comes from its balance change; nil when nothing proves it.
        let arkFee: Int64? = walletRow ? nil : settledLightningSend ? Self.lightningSendFee(transaction) : transaction.offchainFeeSat
        let networkFee = (walletRow ? (transaction.hasOnchainFee == true ? transaction.onchainFeeSat : nil) : transaction.onchainFeeSat) ?? 0
        if unsuccessful { fee = .nothing }
        else if row.status == .sending && lightning { fee = .upTo(transaction.offchainFeeSat ?? 0) }
        else if transaction.isRenewal { fee = .amount(transaction.amount) }
        else if let arkFee, arkFee > 0, networkFee > 0 { fee = .split(ark: arkFee, network: networkFee) }
        else if networkFee > 0 { fee = .network(networkFee) }
        else if let arkFee, arkFee > 0 { fee = .amount(arkFee) }
        // "Free" only for a send whose Ark or Lightning zero is proven; receives show a recorded fee only.
        else if arkFee == 0 && outgoing { fee = .free }
        else { fee = nil }

        // The total follows the fee. A movement's own debit is its total, shown only when it differs from the
        // amount: an offboard's amount already includes its fee, and BDK's balance change includes its own.
        let fees = (arkFee ?? 0) + networkFee
        if outgoing && !unsuccessful && !transaction.isRenewal && !walletRow, fees > 0, arkFee != nil || networkFee > 0 {
            let spent = transaction.effectiveBalanceSat.map { -$0 }
            total = settledLightningSend ? transaction.amount + fees : spent.flatMap { $0 > transaction.amount ? $0 : nil }
        } else {
            total = nil
        }

        proof = outgoing && lightning && transaction.movementStatus == "successful" ? transaction.preimage : nil
        // Only a transaction the record names; never a coin ID's parent. A renewal names its round's.
        chainTxid = rail == .onchain ? transaction.chainTxid : transaction.isRenewal ? transaction.fundingTxid : nil
        let broadcast = row.status == .confirming && ["offboard", "send-onchain"].contains(transaction.movementKind)
        blockTxid = walletRow && row.status.isPending ? transaction.txid : broadcast ? transaction.broadcastTxid : nil
        let comment = transaction.repeatPayment?.comment ?? ""
        note = !comment.isEmpty ? comment : row.title == transaction.paymentDescription ? nil : transaction.paymentDescription
        showsDate = transaction.dateLabel == nil || transaction.hasConfirmation != true
    }

    /// What a settled Lightning send's balance change took beyond what it sent (`SendReconciliation.lightningFee`):
    /// a retried invoice's reused movement keeps its first attempt's fee field. An amountless invoice may have
    /// been paid by a retry for another amount, so its numbers prove nothing, as Send's already-paid receipts.
    static func lightningSendFee(_ transaction: HistoryTransaction) -> Int64? {
        let sent = transaction.sentTo ?? []
        if transaction.paidAmountlessInvoice { return nil }
        let actual = sent.reduce(Int64(0)) { $0 + $1.amount_sat }
        guard let effective = transaction.effectiveBalanceSat, effective <= 0, -effective >= actual, actual > 0 else { return nil }
        return -effective - actual
    }

    /// The header's fiat line, for the amount shown: the historical price for settled rows, the current one
    /// for today's and pending rows. None for failed and canceled rows, or without an amount or a price.
    static func fiat(_ transaction: HistoryTransaction, current: Double?, currency: String, locale: Locale,
                     now: Date = Date(), calendar: Calendar = .current) -> String? {
        guard !HomeActivityRow(transaction, ownDomain: "").status.isUnsuccessful, let sats = transaction.shownAmount else { return nil }
        let today = transaction.timestamp.map { calendar.isDate($0, inSameDayAs: now) } ?? true
        if !transaction.isPending, !today, transaction.dateLabel == nil, let historical = transaction.btcPrice {
            let amount = FiatCurrency.display(sats: sats, btcPrice: historical, currency: currency, locale: locale)
            return L10n.text("{amount} at the time", amount, locale: locale.identifier)
        }
        guard let price = current ?? transaction.btcPrice else { return nil }
        return FiatCurrency.display(sats: sats, btcPrice: price, currency: currency, locale: locale)
    }

    /// Confirmations an Ark-funded on-chain send waits for; a wallet send needs one.
    static func requiredConfirmations(_ transaction: HistoryTransaction, variant: AppVariant = .current) -> Int {
        ["offboard", "send-onchain"].contains(transaction.movementKind)
            ? Int(WalletConfiguration.base(for: variant).roundTxRequiredConfirmations) : 1
    }

    /// The plain-text receipt Share sends: amount, date, To or From, rail, and the
    /// transaction ID (on-chain) or proof of payment (successful Lightning sends). Never
    /// Details, the raw transaction or a receive's preimage.
    static func receipt(_ transaction: HistoryTransaction, ownDomain: String, unit: BitcoinAmountUnit,
                        locale: Locale, date: String) -> String {
        let detail = ActivityDetail(transaction, ownDomain: ownDomain)
        let language = locale.identifier
        let row = HomeActivityRow(transaction, ownDomain: ownDomain)
        let status = detail.renewedCoins.map { L10n.plural("Renewed {n} coins", count: $0, locale: language) }
            .flatMap { detail.status == "Renewed {n} coins" ? $0 : nil } ?? L10n.text(detail.status, locale: language)
        // A paid amountless invoice shares its proven debit, or no amount (`shownAmount`).
        let amount = transaction.shownAmount.map { " " + row.sign + BitcoinAmount.format(sats: $0, unit: unit, locale: locale) } ?? ""
        var lines = [status + amount, date]
        if let to = detail.to { lines.append(L10n.text("To", locale: language) + ": " + to) }
        lines.append(L10n.text("Via", locale: language) + ": " + L10n.text(detail.rail.title, locale: language))
        if let proof = detail.proof { lines.append(L10n.text("Proof of payment", locale: language) + ": " + proof) }
        else if let txid = detail.chainTxid { lines.append(L10n.text("Transaction ID", locale: language) + ": " + txid) }
        return lines.joined(separator: "\n")
    }
}

extension ActivityDetail.Rail {
    var title: String {
        switch self {
        case .ark: "Ark"
        case .arkRound: "Ark round"
        case .lightning: "Lightning"
        case .lightningOffer: "Lightning offer"
        case .onchain: "On-chain"
        }
    }

    var symbol: String {
        switch self {
        case .ark, .arkRound: "sailboat"
        case .lightning, .lightningOffer: "bolt"
        case .onchain: "link"
        }
    }
}
