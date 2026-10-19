import Foundation
import Observation

@MainActor @Observable final class SendModel {
    var amount = ""
    var destination = ""
    var comment = ""
    var useFiat = false
    var btcPrice: Double?
    var fiatCurrency = "USD"
    var stage: SendStage = .amount
    var progress = SendProgress()
    var parsed = SendDestination()
    var route: SendRecipientRoute?
    var source: SendSource?
    var arkBalance: UInt64 = 0
    var onchainBalance: UInt64 = 0
    var suggestions: [String] = []
    var error: String?
    var busy = false
    var resolvingRecipient = false
    var quote: SendQuote?
    var payment: SendPayment?
    var showReview = false
    let controller: SendPaymentController
    private let session: WalletSession
    init(session: WalletSession = .shared, controller: SendPaymentController = .shared) {
        self.session = session
        self.controller = controller
    }
    var isMax: Bool { progress.entry == .max }
    var sats: UInt64 {
        if isMax { return SendFlow.maxBalance(source: source, onchain: onchainBalance, offchain: arkBalance) }
        if useFiat, let btcPrice {
            return UInt64(max(0, FiatCurrency.fiatToSats(Double(amount) ?? .nan, btcPrice: btcPrice)))
        }
        return UInt64(amount) ?? 0
    }
    var rails: [SendRail] {
        if parsed.destinationType == "lnurl", route?.arkAddress != nil, comment.isEmpty { return [.ark, .lightning] }
        return SendFlow.rails(type: parsed.destinationType, request: parsed.bip321)
    }
    var availability: [String: Bool] {
        [
            "ark": sats > 0 && arkBalance >= sats, "lightning": sats > 0 && arkBalance >= sats,
            "onchain": isMax || (sats > 0 && (arkBalance >= sats || onchainBalance >= sats)),
        ]
    }
    var sources: [SendSource] {
        SendSource.allCases.filter {
            let balance = $0 == .offchain ? arkBalance : onchainBalance
            return isMax ? balance > 0 : sats > 0 && balance >= sats
        }
    }
    func load() async {
        do {
            let summary = try await session.summary()
            arkBalance = summary?.balance.ark.spendable ?? 0
            onchainBalance = summary?.balance.onchain.confirmed ?? 0
        } catch { self.error = "Unable to read available balances." }
        if controller.state == .uncertain { await controller.checkStatus() }
    }
    func maxSend() {
        guard !controller.locked else { return }
        progress = SendProgress(entry: .max)
        source = nil
        parsed = SendDestination()
        destination = ""
        quote = nil
        stage = .source
    }
    func recipientFirst() {
        progress.entry = .recipientFirst
        stage = .recipient
    }
    func importRecipient(_ value: String) async {
        destination = value
        if stage == .amount { progress.entry = sats > 0 ? .amountFirst : .recipientFirst }
        stage = .recipient
        await acceptRecipient()
    }
    func previewRecipient() async {
        let candidate = destination
        guard SendDestination.parse(candidate).destinationType == "lnurl" else {
            resolvingRecipient = false
            return
        }
        resolvingRecipient = true
        defer { if candidate == destination { resolvingRecipient = false } }
        let resolved = try? await SendRecipientService.resolve(
            SendDestination.cleaned(candidate).lowercased(), session: session)
        guard !Task.isCancelled, candidate == destination else { return }
        route = resolved
    }
    func acceptRecipient() async {
        guard !busy, !controller.locked else { return }
        busy = true
        error = nil
        defer { busy = false }
        let previousFixedAmount = parsed.amount
        parsed = SendDestination.parse(destination)
        if previousFixedAmount != nil && parsed.amount == nil {
            amount = ""
            progress.amountConfirmed = false
        }
        progress.recipientConfirmed = false
        progress.railConfirmed = false
        guard let type = parsed.destinationType, parsed.error == nil else {
            error = parsed.error ?? "Enter a valid Bitcoin, Lightning, or Ark destination."
            return
        }
        if isMax && (!SendFlow.canMax(type: type, request: parsed.bip321) || parsed.amount != nil) {
            error = "MAX requires an on-chain address without a fixed amount."
            return
        }
        if let fixed = parsed.amount {
            let number = NSDecimalNumber(decimal: fixed)
            guard fixed <= 2_100_000_000_000_000, fixed == Decimal(number.uint64Value) else {
                error = "The invoice must request whole satoshis."
                return
            }
            amount = String(number.uint64Value)
            useFiat = false
            progress.amountConfirmed = true
        }
        if type == "lnurl", route == nil {
            destination = SendDestination.cleaned(destination).lowercased()
            do {
                route = try await SendRecipientService.resolve(destination, session: session)
                return
            } catch {
                self.error = "Unable to resolve this Lightning recipient. Please check the address."
                return
            }
        }
        progress.recipientConfirmed = true
        if sats > 0 { progress.amountConfirmed = true }
        progress.selectedRail = isMax ? .onchain : SendFlow.recommend(rails, availability: availability)
        await advance()
    }
    func advance() async {
        error = nil
        progress.rails = rails
        progress.sourceOptions = sources
        if !progress.railConfirmed && !isMax {
            progress.selectedRail = SendFlow.recommend(rails, availability: availability)
        }
        progress.selectedRailAvailable = progress.selectedRail.map { availability[$0.rawValue] ?? false }
        if let selected = source, !sources.contains(selected) {
            source = nil
            progress.sourceConfirmed = false
        }
        if sources.count == 1 && source == nil { source = sources[0] }
        stage = progress.next
        if stage == .review { await review() }
    }
    func continueStage() async {
        guard !busy, !controller.locked else { return }
        switch stage {
        case .amount:
            guard sats > 0, sats <= 2_100_000_000_000_000 else {
                error = "Enter an amount greater than zero."
                return
            }
            progress.amountConfirmed = true
        case .recipient:
            await acceptRecipient()
            return
        case .method:
            guard let selected = progress.selectedRail, availability[selected.rawValue] == true else {
                error = "Insufficient funds for this payment method."
                return
            }
            progress.railConfirmed = true
        case .source:
            guard let source, sources.contains(source) else {
                error = "Choose a balance with sufficient funds."
                return
            }
            progress.sourceConfirmed = true
            if isMax { progress.selectedRail = .onchain }
        case .review: break
        }
        await advance()
    }
    func review() async {
        busy = true
        defer { busy = false }
        do {
            guard let rail = progress.selectedRail else { throw SendFailure.invalid }
            var method = parsed.destinationType ?? ""
            var target = SendDestination.cleaned(destination)
            if method == "lnurl" { target = target.lowercased() }
            if let request = parsed.bip321 {
                guard let selected = SendFlow.method(rail: rail, request: request) else { throw SendFailure.invalid }
                method = selected
                target = request[
                    [
                        "ark": "arkAddress", "lightning": "lightningInvoice", "offer": "offer",
                        "onchain": "onchainAddress",
                    ][selected]!]!
            }
            if let route {
                let (msat, overflow) = sats.multipliedReportingOverflow(by: 1000)
                guard !overflow, msat >= route.minMsat, msat <= route.maxMsat,
                    comment.utf16.count <= route.commentAllowed
                else { throw SendFailure.invalid }
                if rail == .ark, comment.isEmpty, let ark = route.arkAddress {
                    method = "ark"
                    target = ark
                }
            }
            // A BIP-321 invoice may carry an amount even when its URI does not.
            if method == "lightning", let fixed = PaymentEncoding.invoice(target)?.sats, fixed > 0,
                fixed != Decimal(sats)
            {
                throw SendFailure.invalid
            }
            var payment = SendPayment(
                destination: target, method: method, amount: sats, source: rail == .onchain ? source : nil, max: isMax,
                comment: comment)
            if parsed.destinationType == "lnurl", SendDestination.isLightningAddress(destination) {
                payment.repeatMetadata = try SendPayment.metadata(
                    destination: destination, comment: comment, amount: amount,
                    fiatCurrency: useFiat ? fiatCurrency : nil)
            }
            quote = try await session.quoteSend(payment)
            self.payment = payment
            showReview = true
        } catch {
            self.error =
                (error as? SendFailure)?.errorDescription
                ?? "Unable to estimate this payment. Check the destination and available funds."
            stage = .recipient
        }
    }
    func reset() {
        guard !controller.locked else { return }
        amount = ""
        destination = ""
        comment = ""
        useFiat = false
        parsed = SendDestination()
        route = nil
        progress = SendProgress()
        source = nil
        stage = .amount
        showReview = false
        quote = nil
        payment = nil
        error = nil
    }
    func toggleCurrency() {
        guard let btcPrice else { return }
        let sats = sats
        amount =
            useFiat ? String(sats) : FiatCurrency.satsToFiat(Int64(sats), btcPrice: btcPrice, currency: fiatCurrency)
        useFiat.toggle()
    }
}
