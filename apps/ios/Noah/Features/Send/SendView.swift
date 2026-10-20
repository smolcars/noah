import SwiftUI

struct SendView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(AppModel.self) private var app
    @State private var model = SendModel()
    @State private var scanner = false
    @FocusState private var focus: Field?
    private enum Field { case recipient, note }
    var body: some View {
        Group {
            if model.controller.locked && !model.showReview {
                SendOutcomeView(controller: model.controller) {
                    model.reset()
                    Task {
                        await app.refresh()
                        await model.load()
                    }
                }
            } else {
                composer
            }
        }
        .navigationTitle("Send").background(NoahColor.background.ignoresSafeArea())
        .task {
            model.fiatCurrency = app.preferredCurrency
            await model.load()
            if let response = try? await AppServices.shared.client.fiatPrices(.init(body: .json(.init()))).ok.body.json
            {
                model.btcPrice = response.rates.additionalProperties[app.preferredCurrency]
            }
        }
        .onChange(of: model.stage) { _, _ in focus = nil }
        .onChange(of: model.controller.state) { _, state in if state != .ready { focus = nil } }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active && !model.controller.locked { model.showReview = false; model.discardReview() }
        }
        .onDisappear {
            if !model.controller.locked { model.showReview = false; model.discardReview() }
        }
        .sheet(isPresented: $model.showReview, onDismiss: {
            if !model.controller.locked {
                model.discardReview()
                if model.stage == .review { model.stage = model.isMax ? .source : .recipient }
            }
        }) {
            if let payment = model.payment, let quote = model.quote {
                SendConfirmation(
                    payment: payment, quote: quote, controller: model.controller,
                    cancel: {
                        model.showReview = false
                        model.discardReview()
                        model.stage = model.isMax ? .source : .recipient
                    }
                ) {
                    model.showReview = false
                    model.reset()
                    Task {
                        await app.refresh()
                        await model.load()
                    }
                }
            }
        }
        .fullScreenCover(isPresented: $scanner) {
            SendScanner { value in
                scanner = false
                Task { await model.importRecipient(value) }
            }
        }
    }
    private var composer: some View {
        ScrollView {
            VStack(spacing: 24) {
                if model.stage == .amount { amountComposer }
                if model.stage == .recipient { recipientComposer }
                if model.stage == .method {
                    Text("Choose payment method").font(NoahFont.title)
                    ForEach(model.rails, id: \.self) { rail in
                        choice(
                            rail.label, selected: model.progress.selectedRail == rail,
                            enabled: model.availability[rail.rawValue] == true
                        ) {
                            model.progress.selectedRail = rail
                            model.progress.railConfirmed = true
                        }
                    }
                }
                if model.stage == .source {
                    Text("Choose balance").font(NoahFont.title)
                    ForEach(SendSource.allCases, id: \.self) { source in
                        choice(
                            "\(source.label) · \(source == .offchain ? model.arkBalance : model.onchainBalance) sats",
                            selected: model.source == source, enabled: model.sources.contains(source)
                        ) { model.source = source }
                    }
                }
                if let error = model.error {
                    Text(error).foregroundStyle(NoahColor.destructive).accessibilityIdentifier("send-error")
                }
            }.padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) {
            NoahButton(title: "Continue", isLoading: model.busy || model.resolvingRecipient) {
                focus = nil
                Task { await model.continueStage() }
            }.accessibilityIdentifier("send-continue").padding().background(NoahColor.background)
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if model.stage != .amount {
                    Button("Start over") {
                        focus = nil
                        model.reset()
                    }.disabled(model.busy)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Scan", systemImage: "qrcode.viewfinder") {
                    focus = nil
                    scanner = true
                }.disabled(model.busy)
            }
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { focus = nil }
            }
        }
        .disabled(model.busy || model.controller.locked)
    }
    private var amountComposer: some View {
        VStack(spacing: 24) {
            Text("How much?").font(NoahFont.title)
            Text(model.amount.isEmpty ? "0" : model.amount)
                .font(NoahFont.inter(52, .bold, relativeTo: .largeTitle)).lineLimit(1).minimumScaleFactor(0.35)
                .accessibilityIdentifier("send-amount")
            Button(model.useFiat ? app.preferredCurrency : "sats") { model.toggleCurrency() }
                .disabled(model.btcPrice == nil).accessibilityLabel("Switch amount currency")
            Text("\(model.arkBalance) sats available in Ark").foregroundStyle(NoahColor.mutedForeground)
            if !model.parsed.isAmountEditable { Text("Amount set by payment request") }
            if focus == nil {
                AmountKeypad(amount: $model.amount, decimal: model.useFiat).disabled(!model.parsed.isAmountEditable)
            }
            HStack {
                Button("MAX") { model.maxSend() }.disabled(
                    !model.parsed.isAmountEditable
                        || (!model.destination.isEmpty
                            && !SendFlow.canMax(type: model.parsed.destinationType, request: model.parsed.bip321))
                        || (model.arkBalance == 0 && model.onchainTotal == 0))
                Spacer()
                Button("Choose recipient first") { model.recipientFirst() }
            }
            PasteButton(payloadType: String.self) { values in
                if let value = values.first { Task { await model.importRecipient(value) } }
            }
        }
    }
    private var recipientComposer: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Who are you paying?").font(NoahFont.title)
            TextField("Address, invoice, or payment request", text: $model.destination, axis: .vertical)
                .textInputAutocapitalization(.never).autocorrectionDisabled().lineLimit(2...5)
                .textFieldStyle(.roundedBorder).focused($focus, equals: .recipient).accessibilityIdentifier(
                    "send-recipient"
                )
                .task(id: model.destination) {
                    async let suggestions: Void = loadSuggestions()
                    await model.previewRecipient()
                    await suggestions
                }
            HStack {
                PasteButton(payloadType: String.self) { values in
                    if let value = values.first {
                        focus = nil
                        Task { await model.importRecipient(value) }
                    }
                }
                Button("Scan QR") {
                    focus = nil
                    scanner = true
                }
            }
            if let route = model.matchingRoute {
                Text("Recipient verified").foregroundStyle(NoahColor.success)
                if route.commentAllowed > 0 {
                    TextField("Add a note", text: $model.comment, axis: .vertical)
                        .textFieldStyle(.roundedBorder).focused($focus, equals: .note)
                    Text("\(model.comment.utf16.count)/\(route.commentAllowed)").font(.caption)
                    if !model.comment.isEmpty && route.arkAddress != nil {
                        Text("Adding a note uses Lightning.").font(.caption)
                    }
                }
            }
            ForEach(model.suggestions, id: \.self) { suggestion in
                Button(suggestion) { model.destination = suggestion }.lineLimit(1)
            }
        }
    }
    private func choice(_ title: String, selected: Bool, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                Spacer()
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
            }
            .padding(20).frame(maxWidth: .infinity).glassCompat(
                in: RoundedRectangle(cornerRadius: 20), interactive: true)
        }.disabled(!enabled)
    }
    private func loadSuggestions() async {
        model.suggestions = []
        guard
            let query = SendRecipientService.suggestionQuery(
                model.destination, domain: LightningIdentity.domain(for: .current))
        else { return }
        do {
            try await Task.sleep(for: .milliseconds(200))
            let result = try await AppServices.shared.client.lnAddressSuggestions(
                .init(body: .json(.init(query: query)))
            ).ok.body.json
            guard !Task.isCancelled else { return }
            let own = SettingsStorage.shared.persistedState(PersistedServerState.self, key: PersistedServerState.key)?
                .lightningAddress?.lowercased()
            model.suggestions = result.suggestions.filter { $0.lowercased() != own }
        } catch { /* Suggestions are optional; never log the recipient. */  }
    }
}

struct AmountKeypad: View {
    @Binding var amount: String
    var decimal = false
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 8) {
            ForEach(["1", "2", "3", "4", "5", "6", "7", "8", "9", decimal ? "." : "", "0", "⌫"], id: \.self) { key in
                Button {
                    if key == "⌫" {
                        if !amount.isEmpty { amount.removeLast() }
                    } else if key == "." {
                        if !amount.contains(".") { amount += amount.isEmpty ? "0." : "." }
                    } else if !key.isEmpty && amount.count < 16 {
                        amount = amount == "0" ? key : amount + key
                    }
                } label: {
                    Text(key).font(NoahFont.inter(28, .medium, relativeTo: .title)).frame(
                        maxWidth: .infinity, minHeight: 56)
                }
                .disabled(key.isEmpty).accessibilityLabel(key == "⌫" ? "Delete digit" : key)
                .accessibilityIdentifier("send-key-\(key)")
            }
        }
    }
}

struct SendConfirmation: View {
    var confirm: (() -> Void)? = nil
    let payment: SendPayment
    let quote: SendQuote
    @Bindable var controller: SendPaymentController
    let cancel: () -> Void
    let done: () -> Void
    var body: some View {
        NavigationStack {
            Group {
                if controller.locked {
                    SendOutcomeView(controller: controller, done: done)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 24) {
                            Text(quote.recipientDescription).font(NoahFont.largeTitle)
                            Text(payment.destination).font(NoahFont.callout).textSelection(.enabled)
                            LabeledContent(
                                "Method",
                                value: payment.method == "onchain"
                                    ? (payment.source?.label ?? "On-chain")
                                    : payment.method == "ark" ? "Ark" : "Lightning")
                            LabeledContent(quote.approximate || quote.recipientIsEstimated ? "Estimated fee" : "Fee", value: "\(quote.fee) sats")
                            LabeledContent(quote.recipientIsEstimated ? "Total (reviewed coins)" : quote.approximate ? "Estimated total" : "Total", value: "\(quote.total) sats")
                            if let prepared = quote.prepared {
                                LabeledContent("Transaction size", value: "\(prepared.vsize) vbytes")
                                LabeledContent("Inputs", value: "\(prepared.inputCount)")
                                Text("This signed transaction expires after two minutes. Review again for a fresh fee rate.").font(NoahFont.callout)
                            }
                            if quote.recipientIsEstimated {
                                Text(SendQuote.recipientEstimateExplanation).font(NoahFont.callout)
                            }
                            if quote.approximate {
                                Text(SendQuote.feeEstimateExplanation)
                                    .font(NoahFont.callout)
                            }
                            if !payment.comment.isEmpty { Text(payment.comment) }
                            if let message = controller.message { Text(message).foregroundStyle(NoahColor.destructive) }
                        }.padding(24)
                    }.safeAreaInset(edge: .bottom) {
                        NoahButton(title: "Confirm send") {
                            if let confirm { confirm() } else { controller.submit(payment, quote: quote) }
                        }
                            .accessibilityIdentifier("send-confirm").padding()
                    }
                }
            }
            .navigationTitle("Review payment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Back", action: cancel).disabled(controller.locked)
                }
            }
        }
        .interactiveDismissDisabled(controller.locked)
        .task(id: quote.prepared?.id) {
            guard let prepared = quote.prepared else { return }
            let remaining = max(0, SendPreparedTransaction.reviewLifetime - Date().timeIntervalSince(prepared.createdAt))
            do { try await Task.sleep(for: .seconds(remaining)) } catch { return }
            if !controller.locked { cancel() }
        }
    }
}

struct SendOutcomeView: View {
    @Bindable var controller: SendPaymentController
    let done: () -> Void
    @State private var confirmOverride = false
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                switch controller.state {
                case .submitting:
                    ProgressView()
                    Text("Sending payment…").font(NoahFont.title)
                    Text("Keep Noah open while your payment completes.")
                case .uncertain:
                    Image(systemName: "clock.badge.exclamationmark").font(.largeTitle)
                    Text("Payment may have been sent").font(NoahFont.title)
                    Text(
                        "Noah has not confirmed the final outcome. Do not pay again. Checking status will never submit another payment."
                    )
                    if let progress = controller.attempt?.existingLightning {
                        Text(progress.message)
                        Text("Fee for the existing payment: \(progress.fee.formatted()) sats")
                        if let reviewed = controller.attempt?.payment.amount, reviewed != progress.amount {
                            Text("This differs from the \(reviewed.formatted()) sats you reviewed.")
                        }
                    }
                    if let receipt = controller.attempt?.receipt {
                        Text(receipt.conflictDescription ?? "Noah can’t find this transaction right now. Its confirmation may no longer be trackable in-app. Check a block explorer.")
                        if let status = receipt.chainStatus { Text(status.message) }
                        transactionDetails(receipt)
                    }
                    NoahButton(title: "Check payment status", isLoading: controller.checking) {
                        Task { await controller.checkStatus() }
                    }
                    if let candidates = controller.attempt?.candidates {
                        if candidates.isEmpty { Text("No matching payment is visible in History yet.") }
                        ForEach(candidates) { candidate in
                            VStack {
                                Text(candidate.status == "successful"
                                     ? "Appears to have gone through — check History" : "Possible related payment in History")
                                Text("#\(candidate.id) · \(candidate.status) · \(candidate.amount) sats")
                                Text(candidate.createdAt).font(.caption)
                            }
                        }
                    }
                    ForEach(controller.attempt?.possibleTxids ?? [], id: \.self) { txid in
                        Text("This transaction may be your payment: \(txid)")
                            .textSelection(.enabled)
                    }
                case .succeeded(let receipt):
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 64)).foregroundStyle(NoahColor.success)
                    Text(receipt.isSubmitted ? "Submitted" : receipt.status == .confirmed ? "Payment confirmed" : "Payment sent")
                        .font(NoahFont.title).accessibilityIdentifier("send-success")
                    if receipt.broadcastRetryPending == true {
                        Text("Submitted, awaiting broadcast. Noah will keep retrying the broadcast. Do not send this payment again.")
                    } else if receipt.isSubmitted {
                        Text(receipt.chainStatus?.message ?? "Submitted. Refresh to check network status. Do not send this payment again.")
                    }
                    if receipt.status == .confirmed, let status = receipt.chainStatus { Text(status.message) }
                    if receipt.txid != nil {
                        NoahButton(title: "Refresh payment status", isLoading: controller.checking) {
                            Task { await controller.checkStatus() }
                        }
                    }
                    Text(receipt.amountDescription).font(
                        NoahFont.largeTitle)
                    if let fee = receipt.fee {
                        Text(controller.attempt?.payment.method == "onchain" ? "Actual fee: \(fee) sats" : "Fee: \(fee) sats")
                    } else if receipt.amountIsPending == true {
                        Text("Actual fee pending")
                    } else if let quote = controller.attempt?.quote, quote.approximate {
                        Text("Estimated fee: \(quote.fee) sats")
                        Text("Estimated total: \(quote.total) sats")
                        Text("The actual fee is not yet available. \(SendQuote.feeEstimateExplanation)")
                            .font(NoahFont.callout)
                    }
                    transactionDetails(receipt)
                    if receipt.broadcastRetryPending != true {
                        NoahButton(title: "Done") {
                            controller.acknowledge()
                            if !controller.locked { done() }
                        }
                    }
                case .alreadyPaid(let receipt):
                    Text("Invoice already paid").font(NoahFont.title)
                    Text("This invoice was already paid: \(receipt.amount.formatted()) sats")
                    if let fee = receipt.fee { Text("Fee: \(fee) sats") }
                    Text("No new payment was made for the amount you reviewed.")
                    NoahButton(title: "Done") {
                        controller.acknowledge()
                        if !controller.locked { done() }
                    }
                case .failed:
                    Text("Payment did not complete").font(NoahFont.title)
                    Text(controller.attempt?.failureMessage ?? (controller.attempt?.payment.source == .onchain
                         ? "Submission stopped before a transaction could be recorded. Review the amount and fee before trying again."
                         : "Wallet history confirms this payment failed or was canceled."))
                    NoahButton(title: "Start a new payment") {
                        controller.acknowledge()
                        if !controller.locked { done() }
                    }
                case .storageBlocked:
                    Text("Payment status unavailable").font(NoahFont.title)
                    Text(
                        "Noah could not read or save the payment attempt. Sending is disabled until its status can be recovered."
                    )
                    NoahButton(title: "Retry") { Task { await controller.checkStatus() } }
                case .ready: EmptyView()
                }
                if controller.canOverride {
                    Button("I've checked my history, start a new payment", role: .destructive) { confirmOverride = true }
                        .accessibilityIdentifier("send-override")
                }
                if let message = controller.message { Text(message).font(NoahFont.callout) }
            }.padding(24).multilineTextAlignment(.center).frame(maxWidth: .infinity)
        }
        .alert(SendOverrideAudit.titleText, isPresented: $confirmOverride) {
            Button("Keep waiting", role: .cancel) {}
            Button(SendOverrideAudit.confirmationText, role: .destructive) {
                controller.startNewPaymentAfterHistoryCheck(confirmed: true)
                if !controller.locked { done() }
            }
        } message: {
            Text(controller.overrideWarning)
        }
    }
    @ViewBuilder private func transactionDetails(_ receipt: SendReceipt) -> some View {
        if let txid = receipt.txid {
            Text(txid).font(.caption).textSelection(.enabled)
            // Regtest has no public explorer. Use the same network explorers as History.
            if AppVariant.current != .regtest,
               let base = URL(string: AppVariant.current == .mainnet
                              ? "https://mempool.space/tx/" : "https://mempool.space/signet/tx/") {
                Link(destination: base.appendingPathComponent(txid)) {
                    Label("View in block explorer", systemImage: "arrow.up.right.square")
                }
            }
        }
    }

}
