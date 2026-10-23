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
            if transaction.isRenewal { status = (renewedCoins ?? 0) > 0 ? "Renewed {n} coins" : "Renewed" }
            else if transaction.isInternalTransfer { status = "Moved to Ark" }
            else if transaction.movementKind == "exit" { status = "Exited" }
            else if transaction.receiptStatus == "Recorded" { status = "Recorded" }
            else { status = outgoing ? "Sent" : "Received" }
        }

        if transaction.isRenewal { rail = .arkRound }
        else if lightning { rail = methods.contains { $0.paymentMethod == "offer" } ? .lightningOffer : .lightning }
        else if onchain || transaction.type == "Onchain" { rail = .onchain }
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

        let arkFee = transaction.offchainFeeSat ?? 0
        let networkFee = (walletRow ? (transaction.hasOnchainFee == true ? transaction.onchainFeeSat : nil) : transaction.onchainFeeSat) ?? 0
        if unsuccessful { fee = .nothing }
        else if row.status == .sending && lightning { fee = .upTo(arkFee) }
        else if transaction.isRenewal { fee = .amount(transaction.amount) }
        else if arkFee > 0 && networkFee > 0 { fee = .split(ark: arkFee, network: networkFee) }
        else if networkFee > 0 && (outgoing || transaction.isInternalTransfer) { fee = .network(networkFee) }
        else if outgoing { fee = arkFee > 0 ? .amount(arkFee) : .free }
        else { fee = arkFee > 0 ? .amount(arkFee) : nil }

        // The balance change is the movement's own total; BDK's already includes its fee.
        if outgoing && !unsuccessful && !transaction.isRenewal && !walletRow && arkFee + networkFee > 0 {
            let spent = transaction.effectiveBalanceSat.map { -$0 } ?? 0
            total = spent > transaction.amount ? spent : transaction.amount + arkFee + networkFee
        } else {
            total = nil
        }

        proof = outgoing && lightning && transaction.movementStatus == "successful" ? transaction.preimage : nil
        chainTxid = transaction.explorerURL(variant: .mainnet) != nil ? transaction.txid?.split(separator: ":").first.map(String.init) : nil
        let broadcast = row.status == .confirming && ["offboard", "send-onchain"].contains(transaction.movementKind)
        blockTxid = walletRow && row.status.isPending ? transaction.txid : broadcast ? transaction.broadcastTxid : nil
        let comment = transaction.repeatPayment?.comment ?? ""
        note = !comment.isEmpty ? comment : row.title == transaction.paymentDescription ? nil : transaction.paymentDescription
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
        var lines = [status + " " + row.sign + BitcoinAmount.format(sats: transaction.amount, unit: unit, locale: locale), date]
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
