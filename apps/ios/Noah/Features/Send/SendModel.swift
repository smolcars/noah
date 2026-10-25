import Foundation
import Observation

@MainActor @Observable final class SendModel {
    static let amountRange: ClosedRange<UInt64> = 1...2_100_000_000_000_000
    static let zeroAmountError = "Enter an amount greater than zero."
    var amount = "" {
        // Only Next asks for an amount; a valid one answers it. Other errors (an invalid paste) stay.
        didSet { if errorSource == Self.zeroAmountError, Self.amountRange.contains(sats) { errorSource = nil } }
    }
    var destination = "" {
        didSet {
            guard destination != oldValue else { return }
            recipientRevision += 1
            if parsed.amount != nil { amount = ""; progress.amountConfirmed = false }
            parsed = SendDestination()
            route = nil
            comment = ""
            suggestions = []
            error = nil
            resolvingRecipient = false
            progress.recipientConfirmed = false
            progress.railConfirmed = false
            progress.selectedRail = nil
            progress.rails = []
            progress.selectedRailAvailable = nil
            if !isMax { progress.sourceConfirmed = false; source = nil }
            discardReview()
            payment = nil
            showReview = false
        }
    }
    private var recipientRevision = 0
    private var reviewGeneration = 0
    var comment = ""
    var useFiat = false
    var btcPrice: Double?
    var fiatCurrency = "USD"
    var stage: SendStage = .amount
    var progress = SendProgress()
    var parsed = SendDestination()
    var route: SendRecipientRoute?
    var source: SendSource?
    var pendingLightningHashes: Set<String> = []
    /// When Send last checked a payment's status by itself, on entry or every 30 s while a
    /// receipt shows. Both share it, so leaving and returning to Send doesn't add a check;
    /// "Check payment status" stays immediate.
    private(set) var lastAutomaticCheck: Date?
    var arkBalance: UInt64 = 0
    var onchainBalance: UInt64 = 0
    var onchainTotal: UInt64 = 0
    var suggestions: [String] = []
    var destinationError: SendDestinationError?
    private var errorSource: String?
    var error: String? {
        get { destinationError?.message(locale: "en") ?? errorSource }
        set { destinationError = nil; errorSource = newValue }
    }
    var busy = false
    var resolvingRecipient = false
    var quote: SendQuote?
    var payment: SendPayment?
    var showReview = false
    /// Send's camera, a cover over every step.
    var scanning = false
    let controller: SendPaymentController
    private let session: WalletSession
    private let resolveRecipient: (String) async throws -> SendRecipientRoute
    private let quotePayment: (SendPayment) async throws -> SendQuote
    private let discardQuote: (SendQuote) async -> Void
    /// The amount a BOLT12 offer fixes, or nil; the quote checks it again.
    private let offerAmount: (String) async throws -> UInt64?
    init(session: WalletSession = .shared, controller: SendPaymentController = .shared,
         resolveRecipient: ((String) async throws -> SendRecipientRoute)? = nil,
         quotePayment: ((SendPayment) async throws -> SendQuote)? = nil,
         discardQuote: ((SendQuote) async -> Void)? = nil,
         offerAmount: ((String) async throws -> UInt64?)? = nil) {
        self.session = session
        self.controller = controller
        self.resolveRecipient = resolveRecipient ?? { try await SendRecipientService.resolve($0, session: session) }
        self.quotePayment = quotePayment ?? { try await session.quoteSend($0) }
        self.discardQuote = discardQuote ?? { await session.discardSend($0) }
        self.offerAmount = offerAmount ?? { try await session.sendOfferAmount($0) }
    }
    var isMax: Bool { progress.entry == .max }
    var sats: UInt64 {
        if isMax { return SendFlow.maxBalance(source: source, onchain: onchainBalance, offchain: arkBalance) }
        if useFiat, let btcPrice {
            return UInt64(max(0, FiatCurrency.fiatToSats(Double(amount) ?? .nan, btcPrice: btcPrice)))
        }
        return UInt64(amount) ?? 0
    }
    var matchingRoute: SendRecipientRoute? {
        guard parsed.destinationType == "lnurl", let route,
              route.destination == SendDestination.cleaned(destination).lowercased() else { return nil }
        return route
    }
    var rails: [SendRail] {
        if parsed.destinationType == "lnurl", matchingRoute?.arkAddress != nil, comment.isEmpty { return [.ark, .lightning] }
        return SendFlow.rails(type: parsed.destinationType, request: parsed.bip321)
    }
    var availability: [String: Bool] {
        let invoice = parsed.bip321?["lightningInvoice"] ?? destination
        let pending = PaymentEncoding.invoice(invoice)?.hash.map { pendingLightningHashes.contains($0) } ?? false
        return [
            "ark": sats > 0 && arkBalance >= sats, "lightning": sats > 0 && (arkBalance >= sats || pending || parsed.destinationType == "lnurl"),
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
            onchainTotal = summary?.balance.onchain.total ?? 0
        } catch { self.error = "Unable to read available balances." }
        pendingLightningHashes = (try? await session.pendingSendLightningHashes()) ?? []
        if untilAutomaticCheck() == .zero { await automaticStatusCheck() }
    }

    /// How long until the next automatic status check is due.
    func untilAutomaticCheck(at now: Date = Date()) -> Duration {
        guard let last = lastAutomaticCheck else { return .zero }
        let remaining = SendReceiptRefresh.interval - now.timeIntervalSince(last)
        return remaining > 0 ? .seconds(remaining) : .zero
    }

    /// The entry check on the shared clock: now when it's due, otherwise once the 30 s window
    /// passes, so a skipped check is late, never lost (an uncertain Lightning or Ark payment
    /// has no periodic check to make it up).
    func entryStatusCheck(now: () -> Date = Date.init,
                          sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) async {
        let wait = untilAutomaticCheck(at: now())
        if wait > .zero { do { try await sleep(wait) } catch { return } }
        // The periodic check may have run meanwhile.
        guard untilAutomaticCheck(at: now()) == .zero else { return }
        await automaticStatusCheck(at: now())
    }

    /// Today's status check, run automatically; recorded when there's an attempt to check.
    func automaticStatusCheck(at now: Date = Date()) async {
        if controller.attempt != nil || controller.state == .storageBlocked { lastAutomaticCheck = now }
        await controller.checkStatus()
    }
    func maxSend() {
        guard !controller.locked else { return }
        discardReview()
        progress = SendProgress(entry: .max)
        error = nil
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
    /// Amount's Next: an empty amount with no recipient yet goes on to To, recipient-first (send-receive.md).
    func nextFromAmount() async {
        if amount.isEmpty && !progress.recipientConfirmed { recipientFirst() } else { await continueStage() }
    }
    /// A scanned or pasted recipient. From Amount it's checked there: a request with its own
    /// amount fills both and goes on to Method or Review, one without keeps the keypad's turn
    /// (with any amount typed) and shows the recipient, and one that isn't a destination keeps
    /// today's error on Amount.
    func importRecipient(_ value: String) async {
        destination = value
        let fromAmount = stage == .amount
        if fromAmount { progress.entry = .recipientFirst } else { stage = .recipient }
        await acceptRecipient(keepingKeypad: fromAmount)
    }
    func previewRecipient() async {
        let candidate = destination
        let revision = recipientRevision
        guard SendDestination.parse(candidate).destinationType == "lnurl" else {
            resolvingRecipient = false
            return
        }
        resolvingRecipient = true
        defer { if revision == recipientRevision { resolvingRecipient = false } }
        let resolved = try? await resolveRecipient(SendDestination.cleaned(candidate).lowercased())
        guard !Task.isCancelled, revision == recipientRevision else { return }
        route = resolved
    }
    /// `keepingKeypad`: a recipient without its own amount leaves the amount unconfirmed, even
    /// one confirmed by an earlier Next, so Amount comes next with whatever is typed.
    func acceptRecipient(keepingKeypad: Bool = false) async {
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
            error = "Enter a valid Bitcoin, Lightning, or Ark destination."
            destinationError = parsed.error
            return
        }
        if isMax && (!SendFlow.canMax(type: type, request: parsed.bip321) || parsed.amount != nil) {
            error = "MAX requires an on-chain address without a fixed amount."
            return
        }
        if type == "offer" {
            let revision = recipientRevision
            do {
                let fixed = try await offerAmount(SendDestination.cleaned(destination))
                guard revision == recipientRevision else { return }
                // An offer that fixes its amount behaves like an invoice with one.
                if let fixed { parsed.amount = Decimal(fixed); parsed.isAmountEditable = false }
            } catch {
                guard revision == recipientRevision else { return }
                self.error = "Unable to resolve this recipient. Please check the address."
                return
            }
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
        if type == "lnurl", matchingRoute == nil {
            let revision = recipientRevision
            let candidate = SendDestination.cleaned(destination).lowercased()
            do {
                let resolved = try await resolveRecipient(candidate)
                guard revision == recipientRevision else { return }
                route = resolved
                guard matchingRoute != nil else { throw SendFailure.invalid }
            } catch {
                guard revision == recipientRevision else { return }
                self.error = "Unable to resolve this recipient. Please check the address."
                return
            }
        }
        progress.recipientConfirmed = true
        if keepingKeypad { progress.amountConfirmed = parsed.amount != nil } else if sats > 0 { progress.amountConfirmed = true }
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
            guard Self.amountRange.contains(sats) else {
                error = Self.zeroAmountError
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
        discardReview()
        busy = true
        let generation = reviewGeneration
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
            if method == "lnurl" {
                guard let route = matchingRoute else { throw SendFailure.invalid }
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
            try SendMaxValidation.available(payment)
            let reviewed = try await quotePayment(payment)
            guard !Task.isCancelled, generation == reviewGeneration else {
                await discardQuote(reviewed)
                return
            }
            quote = reviewed
            self.payment = payment
            showReview = true
        } catch {
            guard !Task.isCancelled, generation == reviewGeneration else { return }
            self.error =
                (error as? SendFailure)?.errorDescription
                ?? "Unable to prepare this payment. Check the destination and available funds."
            stage = .recipient
        }
    }
    func discardReview() {
        reviewGeneration += 1
        if let quote { Task { await discardQuote(quote) } }
        quote = nil
    }
    func reset() {
        guard !controller.locked else { return }
        discardReview()
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
    /// Consume only when editable; the normal recipient step validates and chooses rails again.
    func consumeRepeatPayment(_ pending: inout RepeatPayment?, currency: String) {
        guard let repeatPayment = pending, !controller.locked, !busy else { return }
        pending = nil
        reset()
        destination = repeatPayment.destination
        let prefill = repeatPayment.prefill(preferredCurrency: currency)
        amount = prefill.amountInput
        useFiat = prefill.amountMode == "FIAT"
        fiatCurrency = currency
        comment = repeatPayment.comment
        progress = SendProgress(entry: .recipientFirst)
        stage = .recipient
    }

    func toggleCurrency() {
        guard let btcPrice else { return }
        let sats = sats
        amount =
            useFiat ? String(sats) : FiatCurrency.satsToFiat(Int64(sats), btcPrice: btcPrice, currency: fiatCurrency)
        useFiat.toggle()
    }
}
