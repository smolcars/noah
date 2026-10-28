import CoreImage.CIFilterBuiltins
import SwiftUI

/// Receive (docs/native-rewrite/design/send-receive.md): your Lightning address as the
/// default QR on a white card; Add amount runs today's invoice creation and shows the
/// unified payment request with an amount band; every format is one tap away. Payments
/// arriving on this screen play their rail's arrival and flip the card. Wide windows (the
/// iPhone Duo open or half-open) put the card in the leading pane and the formats in the
/// trailing one.
struct ReceiveView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.sendReceiveFixture) private var fixture
    let isActive: Bool
    @State private var model: ReceiveModel
    @State private var btcPrice: Double?
    @State private var format: ReceiveFormat = .lightningAddress
    @State private var sheet: Sheet?
    /// The amount editor is open: a sheet when compact, the trailing pane when wide. One
    /// intent and one draft for both, so folding or unfolding mid-edit keeps them.
    @State private var editing = false
    @State private var draft: ReceiveAmountDraft?
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var haptics = OnboardingHaptics()
    @State private var tilt = 0
    @State private var arrivals = ReceiveArrivalQueue()
    @State private var played: Set<String> = []
    /// On-chain transactions already announced, so a restarted watch doesn't announce them again.
    @State private var announced: Set<String> = []
    /// The inputs of every deposit announced while Receive is open, and of their replacements,
    /// so a replacement of any of them (not only the one showing) is never another deposit.
    @State private var announcedSpends: Set<SendTransaction.Input> = []
    /// The compact sheet an on-chain arrival set aside, to bring back once the card is home.
    @State private var interrupted: Sheet?
    /// What the card showed when a payment arrived; the request is used up by then.
    @State private var lastShown: Shown?
    @State private var tip: UInt32?
    @State private var toast: HomeToast?
    @State private var profile = false
    private enum Sheet: String, Identifiable { case amount, formats; var id: String { rawValue } }

    init(isActive: Bool, model: ReceiveModel? = nil) {
        self.isActive = isActive
        _model = State(initialValue: model ?? ReceiveModel())
    }

    private struct Shown: Equatable {
        var payload: String?
        var title: String
        var caption: String
        var bandAmount: String?
        var bandFiat: String?
        var unavailable: Bool
    }

    private var wide: Bool { window.wide }
    private var arrival: ReceiveArrival? { arrivals.current }
    /// An Ark or Lightning arrival is showing: the request it paid is used up.
    private var paidArrival: Bool { arrival.map { $0.rail != .onchain } ?? false }
    private var address: String? { app.server.state.lightningAddress }
    private var hasAmount: Bool { model.request?.amountSat != nil }
    private var amountText: String? {
        model.request?.amountSat.map { BitcoinAmount.format(sats: $0, unit: app.amountUnit, locale: locale) }
    }
    private var payload: String? { format.payload(request: model.request, address: address) }

    private var current: Shown {
        let sats = model.request?.amountSat
        return Shown(
            payload: payload,
            title: format == .lightningAddress ? (address ?? "") : L10n.text(format.name, locale: locale.identifier),
            caption: format.caption(hasAmount: hasAmount),
            bandAmount: format.showsBand(hasAmount: hasAmount) ? amountText : nil,
            bandFiat: format.showsBand(hasAmount: hasAmount) ? sats.flatMap { sats in
                btcPrice.map { FiatCurrency.display(sats: sats, btcPrice: $0, currency: app.preferredCurrency, locale: locale) }
            } : nil,
            unavailable: format == .lightningAddress && address == nil)
    }
    private var shown: Shown { paidArrival ? (lastShown ?? current) : current }
    private var qrSide: CGFloat { wide ? 228 : min(226, max(168, window.size.height - 470)) }

    var body: some View {
        ZStack(alignment: .top) {
            NoahTokens.Colors.background.ignoresSafeArea()
            if let error = model.baseError, arrival == nil {
                errorView(error)
            } else {
                OnboardingPanes(wide: wide, column: .leadingFits, sideInsets: (window.insets.leading, window.insets.trailing)) {
                    leading
                    trailing
                }
            }
            if let toast {
                HomeToastView(text: toast.text)
                    .padding(.top, wide ? 22 : 56)
                    .transition(.homeToast(reduceMotion))
                    .id(toast.id)
            }
            if wide && editing, let draft {
                HomePaneSheet(width: window.size.width / 2 - window.insets.trailing, onDismiss: cancelAmount) {
                    ReceiveAmountSheet(draft: draft, model: model, btcPrice: btcPrice, onDone: amountDone)
                        .frame(height: min(600, window.size.height - 130))
                }
            }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: wide && editing)
        .animation(NoahTokens.Motion.travel(reduceMotion), value: toast)
        .toolbar(.hidden, for: .navigationBar)
        .onboardingWindow($window)
        .onboardingHaptics(haptics)
        .task(id: "\(isActive):\(app.walletSessionRevision)") {
            if isActive {
                async let rate: Void = loadRate()
                await model.start()
                await rate
            } else {
                model.stop()
                sheet = nil
                editing = false
                draft = nil
                arrivals.clear()
                interrupted = nil
            }
        }
        .onDisappear { model.stop() }
        .onChange(of: current, initial: true) { _, value in if !paidArrival && value.payload != nil { lastShown = value } }
        .onChange(of: model.receivedSats) { _, sats in
            guard let sats else { return }
            announce(ReceiveArrival(rail: model.receivedRail == .lightning ? .lightning : .ark, sats: sats, start: Date()))
        }
        .onChange(of: model.baseError) { _, error in if error != nil { sheet = nil; editing = false } }
        .onChange(of: model.request) { old, new in
            // A draft belongs to its request.
            if let draft, draft.request != new { self.draft = nil; editing = false }
            // After an Ark or Lightning payment, the arrival regenerates the request itself.
            guard !paidArrival, let new else { return }
            if let old, old.arkAddress == new.arkAddress, old.onchainAddress == new.onchainAddress {
                // The same request with a new amount, or with it removed.
                if old.amountSat != new.amountSat { show(ReceiveFormat.after(amountChange: new.amountSat != nil), haptic: false) }
            } else {
                // A fresh request (on returning to Receive): keep only a format it can show.
                format = ReceiveFormat.reconciled(format, hasAmount: new.amountSat != nil)
            }
        }
        .task(id: arrival?.id) { await playArrival() }
        .task {
            // Screenshot fixtures open a given format or sheet.
            guard let fixture else { return }
            try? await Task.sleep(for: .milliseconds(300))
            if let format = fixture.receiveFormat { self.format = format }
            switch fixture.receiveSheet {
            case "formats": sheet = .formats
            case "amount": openAmount()
            default: break
            }
        }
        .task(id: chainKey) { await watchChain() }
        .sheet(item: Binding(get: { wide ? nil : editing ? .amount : sheet }, set: { next in
            // Folding away a sheet isn't closing it; the pane takes over.
            guard !wide, next == nil else { return }
            if editing { cancelAmount() }
            sheet = nil
        })) { sheet in
            switch sheet {
            case .amount:
                if let draft {
                    ReceiveAmountSheet(draft: draft, model: model, btcPrice: btcPrice, onDone: amountDone)
                        .presentationDetents([.large])
                }
            case .formats:
                ReceiveCopySheet(request: model.request, address: address, selected: format, amount: amountText,
                                 onSelect: { picked in
                                     if select(picked) { Task { try? await Task.sleep(for: .milliseconds(250)); self.sheet = nil } }
                                 }, onCopy: copy)
                    .presentationDetents([.fraction(0.66), .large])
            }
        }
        .navigationDestination(isPresented: $profile) { SettingsHost(page: .profile) }
    }

    // MARK: Leading: the card

    private var leading: some View {
        VStack(spacing: 0) {
            if !wide {
                HStack {
                    Spacer()
                    SendIconButton(systemName: "doc.on.doc", label: "Show payment details", id: "receive-copy-button") {
                        haptics.play(.light)
                        sheet = .formats
                    }
                }
                .frame(height: NoahTokens.Size.hitTarget)
                .padding(.top, 4)
            } else {
                Spacer(minLength: 0)
            }
            card
                .frame(width: wide ? 310 : 300)
                .padding(.top, wide ? 0 : 12)
            phaseLine.padding(.top, 14)
            if let arrival, arrival.rail == .onchain {
                ReceiveBlockRow(arrival: arrival, tip: tip)
                    .frame(width: wide ? 310 : 300, height: 58)
                    .padding(.top, 8)
                    .transition(.opacity)
            }
            if !wide {
                pills.frame(width: 300).padding(.top, 18)
            } else {
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, wide ? 96 : 12)
        .frame(maxWidth: .infinity, maxHeight: wide ? .infinity : nil)
    }

    private var card: some View {
        let shown = shown
        return ReceiveCard(payload: shown.payload, title: shown.title, caption: shown.caption,
                           band: shown.bandAmount.map { ($0, shown.bandFiat) }, qrSide: qrSide,
                           listening: model.request != nil && scenePhase == .active, arrival: arrival,
                           unit: app.amountUnit, tilt: tilt, unavailable: shown.unavailable) {
            profile = true
        }
        .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy), value: shown.bandAmount)
    }

    /// Redraws at the phase's boundaries only, never on a running clock.
    private var phaseLine: some View {
        let now = Date()
        let boundaries = arrival.map { arrival in
            arrival.phaseBoundaries.map { arrival.start.addingTimeInterval($0 + 0.01) }.filter { $0 > now }.sorted()
        } ?? []
        return TimelineView(.explicit([now] + boundaries)) { timeline in
            let phase: (text: String, done: Bool) = arrival.map { $0.phase(at: $0.elapsed(timeline.date), tip: tip, locale: locale) } ?? ("", false)
            Text(verbatim: phase.text)
                .noahText(NoahTokens.Typography.control)
                .foregroundStyle(phase.done ? NoahTokens.Colors.foreground : NoahTokens.Colors.muted)
                .frame(maxWidth: .infinity, minHeight: 20)
                .id(phase.text)
                .transition(.asymmetric(insertion: reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 6)), removal: .identity))
                .animation(NoahTokens.Motion.travel(reduceMotion), value: phase.text)
                .accessibilityHidden(phase.text.isEmpty)
                .accessibilityIdentifier("receive-phase")
        }
    }

    private var pills: some View {
        GlassGroup(spacing: 10) {
            HStack(spacing: 10) {
                Button {
                    openAmount()
                } label: {
                    Text(hasAmount ? "Change amount" : "Add amount").minimumScaleFactor(0.75).frame(maxWidth: .infinity)
                }
                .buttonStyle(HomePillStyle(prominent: false, height: wide ? 52 : 48, font: wide ? NoahTokens.Typography.button : SendText.pill))
                .disabled(model.request == nil || model.isSaving || paidArrival)
                .accessibilityIdentifier("receive-amount-button")
                if let payload = shown.payload {
                    ShareLink(item: payload) { Text("Share").frame(maxWidth: .infinity) }
                        .buttonStyle(HomePillStyle(height: wide ? 52 : 48, font: wide ? NoahTokens.Typography.button : SendText.pill))
                        .simultaneousGesture(TapGesture().onEnded { haptics.play(.light) })
                        .accessibilityIdentifier("receive-share-button")
                } else {
                    Button {} label: { Text("Share").frame(maxWidth: .infinity) }
                        .buttonStyle(HomePillStyle(height: wide ? 52 : 48, font: wide ? NoahTokens.Typography.button : SendText.pill))
                        .disabled(true)
                }
            }
        }
    }

    // MARK: Trailing: the formats (wide)

    @ViewBuilder private var trailing: some View {
        if wide {
            VStack(alignment: .leading, spacing: 0) {
                SendSectionTitle(title: "Show").padding(.bottom, 4)
                ScrollView {
                    ReceiveFormatList(request: model.request, address: address, selected: format, amount: amountText,
                                      onSelect: { _ = select($0) }, onCopy: copy)
                }
                .scrollBounceBehavior(.basedOnSize)
                Spacer(minLength: 16)
                pills
            }
            .padding(.top, 40).padding(.leading, 40).padding(.trailing, 34).padding(.bottom, 24)
            .frame(maxWidth: OnboardingLayout.paneContentWidth + 74)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        } else {
            Color.clear.frame(height: 0)
        }
    }

    private func errorView(_ error: String) -> some View {
        VStack(alignment: .leading, spacing: NoahTokens.Space.s) {
            Image(systemName: "exclamationmark.circle").font(.system(size: 36)).foregroundStyle(NoahTokens.Colors.failure)
                .padding(.bottom, NoahTokens.Space.s).accessibilityHidden(true)
            Text(LocalizedStringKey(model.baseErrorTitle)).noahText(NoahTokens.Typography.title).foregroundStyle(NoahTokens.Colors.foreground)
            Text(LocalizedStringKey(error)).noahText(NoahTokens.Typography.body).foregroundStyle(NoahTokens.Colors.muted)
            Button { Task { await model.start() } } label: { Text("Retry").frame(maxWidth: .infinity) }
                .buttonStyle(HomePillStyle(height: 52, font: NoahTokens.Typography.button))
                .padding(.top, NoahTokens.Space.l)
                .accessibilityIdentifier("receive-retry")
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: OnboardingLayout.paneContentWidth + 48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: Actions

    private func openAmount() {
        guard let request = model.request else { return }
        haptics.play(.light)
        // The same request resumes its draft (one an on-chain arrival set aside).
        if draft?.request != request { draft = ReceiveAmountDraft(request: request) }
        sheet = nil
        editing = true
    }

    /// Shows a format on the card; an invoice before an amount opens the amount sheet instead.
    @discardableResult private func select(_ picked: ReceiveFormat) -> Bool {
        if picked == .invoice && !hasAmount {
            sheet = nil
            Task {
                try? await Task.sleep(for: .milliseconds(200))
                openAmount()
            }
            return false
        }
        show(picked, haptic: true)
        return true
    }

    private func show(_ next: ReceiveFormat, haptic: Bool) {
        guard next != format else { return }
        if haptic { haptics.play(.selection) }
        format = next
        tilt += 1
    }

    private func copy(_: ReceiveFormat, _ value: String) {
        UIPasteboard.general.string = value
        haptics.play(.success)
        UIAccessibility.post(notification: .announcement, argument: L10n.text("Copied", locale: locale.identifier))
        toast = HomeToast(text: "Copied")
        let current = toast
        Task {
            try? await Task.sleep(for: .seconds(1.7))
            if toast == current { toast = nil }
        }
    }

    private func amountDone(_ saved: Bool) {
        editing = false
        draft = nil
        if saved { haptics.play(.success) }
    }

    /// Swiped or tapped away: the edit is dropped. Not while the invoice is being created:
    /// the wide pane's backdrop and escape gesture can't close it then, like the sheet.
    private func cancelAmount() {
        guard !model.isSaving else { return }
        editing = false
        draft = nil
    }

    // MARK: Arrivals

    /// Plays an arrival now, or once the one playing has flipped home.
    private func announce(_ next: ReceiveArrival) {
        arrivals.announce(next)
        Task { await app.refresh() }
    }

    /// Plays the arrival's haptics once each, then flips home and, after an Ark or
    /// Lightning payment, regenerates the request as Receive does today. Then the next
    /// arrival plays, or a sheet an on-chain arrival set aside comes back.
    private func playArrival() async {
        guard let arrival else { return }
        stepAside(for: arrival)
        for item in arrival.haptics {
            let wait = item.at - arrival.elapsed(Date())
            if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return } }
            if played.insert("\(arrival.id):\(item.key)").inserted { haptics.play(item.kind) }
        }
        let wait = arrival.settledAt - arrival.elapsed(Date())
        if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return } }
        guard self.arrival?.id == arrival.id else { return }
        if arrival.rail != .onchain {
            // Regenerate before moving on: changing the arrival ends this task.
            format = ReceiveFormat.reconciled(format, hasAmount: false)
            if isActive && fixture == nil { await model.start() }
            guard self.arrival?.id == arrival.id else { return }
        }
        if !arrivals.finish() { resumeSheet() }
    }

    /// The compact sheet an on-chain arrival set aside comes back, with its draft.
    private func resumeSheet() {
        guard let resume = interrupted else { return }
        interrupted = nil
        guard !editing, sheet == nil else { return }
        if resume == .formats { sheet = .formats } else if draft != nil { editing = true }
    }

    /// Compact sheets would hide the card, so they step aside for an arrival. One set aside
    /// for an on-chain arrival comes back with its draft; an Ark or Lightning payment uses
    /// up the request, and the draft with it. Wide windows show the card beside the pane.
    private func stepAside(for arrival: ReceiveArrival) {
        if arrival.rail == .onchain {
            guard !wide, editing || sheet != nil else { return }
            interrupted = editing ? .amount : sheet
        } else {
            interrupted = nil
            draft = nil
        }
        editing = false
        sheet = nil
    }

    private var chainKey: String? {
        guard isActive, scenePhase == .active, let address = model.request?.onchainAddress else { return nil }
        return address
    }

    /// Receive's on-chain watch: re-checks the on-chain wallet at most every 30 s while
    /// visible. Each new unconfirmed incoming transaction (those paying this request's
    /// address first) plays an arrival in turn; the block row follows the one showing to
    /// its confirmation, or to the replacement that took its place.
    private func watchChain() async {
        guard let address = chainKey else { return }
        let read: (String, Bool) async throws -> ReceiveChainSnapshot
        if let chain = fixture?.chain { read = { address, _ in try await chain(address) } }
        else { read = { try await WalletSession.shared.receiveChainSnapshot(address: $0, baseline: $1) } }
        // swiftlint:disable:next discouraged_optional_collection - nil until the first chain read
        var known: Set<String>?
        while !Task.isCancelled {
            if let snapshot = try? await read(address, known == nil), !Task.isCancelled {
                tip = snapshot.tip
                let followed = arrivals.follow(snapshot)
                if followed.confirmed { haptics.play(.light) }
                if followed.ended && arrival == nil { resumeSheet() }
                // A replacement is the same deposit, not another arrival.
                announced.formUnion(followed.txids)
                let look = snapshot.look(known: known, announced: announced, spent: announcedSpends)
                announcedSpends = look.spent
                for transaction in look.arrivals {
                    announced.insert(transaction.txid)
                    announce(ReceiveArrival(rail: .onchain, sats: transaction.arrivingSats, start: Date(),
                                            txid: transaction.txid, inputs: transaction.inputs))
                }
                known = look.known
            }
            do { try await Task.sleep(for: .seconds(fixture?.chain == nil ? 30 : 2)) } catch { return }
        }
    }

    private func loadRate() async {
        btcPrice = nil
        do {
            let response = try await AppServices.shared.client.fiatPrices(.init(body: .json(.init())))
            let rate = try response.ok.body.json.rates.additionalProperties[app.preferredCurrency]
            if let rate, rate.isFinite, rate > 0 { btcPrice = rate }
        } catch {
            guard !Task.isCancelled else { return }
            AppLog("receive").w(#"Fiat rate unavailable: \#(SafeErrorDescription.forLogging(error))"#)
        }
    }
}

struct ReceiveQRCode: View {
    let uri: String

    var body: some View {
        if let image = Self.image(uri: uri) {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                .overlay {
                    Image(.receiveLogo).resizable().scaledToFit()
                        .frame(width: 36, height: 36).padding(4)
                        .background(.white, in: RoundedRectangle(cornerRadius: 10))
                }
                .accessibilityHidden(true)
        } else {
            Text("QR code unavailable. Use payment details to copy your request.")
                .foregroundStyle(.black).padding()
        }
    }

    static func image(uri: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(uri.utf8)
        filter.correctionLevel = #"H"#
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output.transformed(by: .init(scaleX: 6, y: 6)),
                                                    from: output.extent.applying(.init(scaleX: 6, y: 6))) else { return nil }
        return UIImage(cgImage: image)
    }
}
