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
    @State private var paneSheet = false
    @State private var window = OnboardingLayout(size: .zero, insets: EdgeInsets())
    @State private var haptics = OnboardingHaptics()
    @State private var tilt = 0
    @State private var arrival: ReceiveArrival?
    @State private var played: Set<String> = []
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
    private var shown: Shown { arrival != nil && arrival?.rail != .onchain ? (lastShown ?? current) : current }
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
            if wide && paneSheet, let request = model.request {
                HomePaneSheet(width: window.size.width / 2 - window.insets.trailing, onDismiss: { paneSheet = false }) {
                    ReceiveAmountSheet(request: request, model: model, btcPrice: btcPrice, onDone: amountDone)
                        .frame(height: min(600, window.size.height - 130))
                }
            }
        }
        .animation(NoahTokens.Motion.travel(reduceMotion), value: paneSheet)
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
                paneSheet = false
                arrival = nil
            }
        }
        .onDisappear { model.stop() }
        .onChange(of: current, initial: true) { _, value in if arrival == nil && value.payload != nil { lastShown = value } }
        .onChange(of: model.receivedSats) { _, sats in
            guard let sats else { return }
            sheet = nil
            paneSheet = false
            played = []
            arrival = ReceiveArrival(rail: model.receivedRail == .lightning ? .lightning : .ark, sats: sats, start: Date())
            Task { await app.refresh() }
        }
        .onChange(of: model.baseError) { _, error in if error != nil { sheet = nil; paneSheet = false } }
        .onChange(of: model.request?.amountSat) { old, new in
            guard arrival == nil, model.request != nil else { return }
            let next = ReceiveFormat.after(amountChange: new != nil, from: format)
            if next != format || (old == nil) != (new == nil) { show(next, haptic: false) }
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
        .sheet(item: Binding(get: { wide ? nil : sheet }, set: { if !wide { sheet = $0 } })) { sheet in
            switch sheet {
            case .amount:
                if let request = model.request {
                    ReceiveAmountSheet(request: request, model: model, btcPrice: btcPrice, onDone: amountDone)
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
        .navigationDestination(isPresented: $profile) { ProfileView() }
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

    private var phaseLine: some View {
        TimelineView(.animation(minimumInterval: 0.1, paused: arrival == nil || scenePhase != .active)) { timeline in
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
                .disabled(model.request == nil || model.isSaving || arrival != nil && arrival?.rail != .onchain)
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
        haptics.play(.light)
        if wide { paneSheet = true } else { sheet = .amount }
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

    private func copy(_ format: ReceiveFormat, _ value: String) {
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
        sheet = nil
        paneSheet = false
        if saved { haptics.play(.success) }
    }

    // MARK: Arrivals

    /// Plays the arrival's haptics once each, then flips home and, after an Ark or
    /// Lightning payment, regenerates the request as Receive does today.
    private func playArrival() async {
        guard let arrival else { return }
        for item in arrival.haptics {
            let wait = item.at - arrival.elapsed(Date())
            if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return } }
            if played.insert("\(arrival.id):\(item.key)").inserted { haptics.play(item.kind) }
        }
        guard arrival.rail != .onchain else { return }
        let wait = arrival.settledAt - arrival.elapsed(Date())
        if wait > 0 { do { try await Task.sleep(for: .seconds(wait)) } catch { return } }
        guard self.arrival?.id == arrival.id else { return }
        // Regenerate before clearing the arrival: clearing it ends this task.
        format = ReceiveFormat.after(amountChange: false, from: format)
        if isActive && fixture == nil { await model.start() }
        guard self.arrival?.id == arrival.id else { return }
        self.arrival = nil
    }

    private var chainKey: String? {
        guard isActive, scenePhase == .active, let address = model.request?.onchainAddress else { return nil }
        return address
    }

    /// Receive's on-chain watch: re-checks the on-chain wallet at most every 30 s while
    /// visible. A new unconfirmed incoming transaction (one paying this request's address
    /// first) plays the arrival; its block row then follows it to its confirmation.
    private func watchChain() async {
        guard let address = chainKey else { return }
        let read = fixture?.chain ?? { try await WalletSession.shared.receiveChainSnapshot(address: $0) }
        var known: Set<String>?
        while !Task.isCancelled {
            if let snapshot = try? await read(address), !Task.isCancelled {
                tip = snapshot.tip
                if let current = arrival, current.rail == .onchain, let txid = current.txid {
                    if current.confirmedHeight == nil,
                       let height = snapshot.incoming.first(where: { $0.txid == txid })?.confirmedHeight {
                        arrival?.confirmedHeight = height
                        arrival?.confirmedAt = Date()
                        haptics.play(.light)
                    }
                } else if let known, arrival == nil, let transaction = snapshot.arrival(excluding: known) {
                    played = []
                    arrival = ReceiveArrival(rail: .onchain, sats: Int64(clamping: transaction.toAddress > 0 ? transaction.toAddress : transaction.received),
                                             start: Date(), txid: transaction.txid)
                    Task { await app.refresh() }
                }
                known = (known ?? []).union(snapshot.incoming.map(\.txid))
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
            AppLog("receive").w("Fiat rate unavailable: \(SafeErrorDescription.forLogging(error))")
        }
    }
}

struct ReceiveQRCode: View {
    let uri: String

    var body: some View {
        if let image = Self.image(uri: uri) {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                .overlay {
                    Image("ReceiveLogo").resizable().scaledToFit()
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
        filter.correctionLevel = "H"
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output.transformed(by: .init(scaleX: 6, y: 6)),
                                                   from: output.extent.applying(.init(scaleX: 6, y: 6))) else { return nil }
        return UIImage(cgImage: image)
    }
}
