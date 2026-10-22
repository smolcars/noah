import SwiftUI

/// Add amount (send-receive.md): the same keypad as Send plus an "Add a note" chip (today's
/// invoice description). Done runs today's invoice creation; clearing the amount returns
/// to the Lightning address.
struct ReceiveAmountSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    let request: ReceiveRequest
    @Bindable var model: ReceiveModel
    let btcPrice: Double?
    /// Called when the sheet should close; true once an invoice for a new amount exists.
    let onDone: (Bool) -> Void
    @State private var amount: String
    @State private var description: String
    @State private var useFiat = false
    @State private var noteOpen: Bool
    @State private var haptics = OnboardingHaptics()
    @FocusState private var noteFocused: Bool

    init(request: ReceiveRequest, model: ReceiveModel, btcPrice: Double?, onDone: @escaping (Bool) -> Void) {
        self.request = request
        self.model = model
        self.btcPrice = btcPrice
        self.onDone = onDone
        _amount = State(initialValue: request.amountSat.map(String.init) ?? "")
        _description = State(initialValue: request.description)
        _noteOpen = State(initialValue: !request.description.isEmpty)
    }

    private var sats: Int64 {
        let normalized = amount.replacingOccurrences(of: locale.decimalSeparator ?? ".", with: ".")
        if useFiat, let btcPrice { return FiatCurrency.fiatToSats(Double(normalized) ?? .nan, btcPrice: btcPrice) }
        return Int64(normalized) ?? 0
    }
    private var cleared: Bool { sats == 0 }
    private var valid: Bool { cleared || (try? ReceiveRequest.validate(amountSat: sats, description: description)) != nil }
    private var noteTooLong: Bool { description.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count > 639 }

    private var display: (text: String, empty: Bool) {
        if useFiat {
            let formatter = NumberFormatter()
            formatter.numberStyle = .currency
            formatter.currencyCode = app.preferredCurrency
            formatter.locale = locale
            return ((formatter.currencySymbol ?? "") + (amount.isEmpty ? "0" : amount), amount.isEmpty)
        }
        return (BitcoinAmount.format(sats: sats, unit: app.amountUnit, locale: locale), amount.isEmpty)
    }

    private var fiatLine: String {
        if useFiat { return BitcoinAmount.format(sats: sats, unit: app.amountUnit, locale: locale) }
        guard let btcPrice else { return L10n.text("{currency} rate unavailable", app.preferredCurrency, locale: locale.identifier) }
        return FiatCurrency.display(sats: sats, btcPrice: btcPrice, currency: app.preferredCurrency, locale: locale)
    }

    var body: some View {
        GeometryReader { proxy in
            VStack(spacing: 0) {
                Text(request.amountSat == nil ? "Add amount" : "Change amount")
                    .noahText(HomeText.listTitle).foregroundStyle(NoahTokens.Colors.foreground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                SendAmountDisplay(text: display.text, empty: display.empty, value: Double(sats), style: SendText.sheetAmount)
                    .frame(height: 58).padding(.top, 10)
                    .accessibilityIdentifier("receive-amount-input")
                Button(action: toggleCurrency) {
                    HStack(spacing: 8) {
                        Text(verbatim: fiatLine).noahText(NoahTokens.Typography.body)
                        if btcPrice != nil { Image(systemName: "arrow.up.arrow.down").font(.system(size: 14, weight: .medium)) }
                    }
                    .foregroundStyle(NoahTokens.Colors.muted)
                    .frame(minHeight: NoahTokens.Size.hitTarget)
                }
                .buttonStyle(.plain)
                .disabled(btcPrice == nil || model.isSaving)
                .accessibilityLabel(useFiat ? Text("Switch to sats") : Text(L10n.text("Switch to {value1}", app.preferredCurrency, locale: locale.identifier)))
                .accessibilityIdentifier("receive-currency-toggle")
                note.padding(.top, 4)
                if noteTooLong {
                    Text("Note must be 639 characters or fewer.").noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.failure)
                        .padding(.top, 6)
                }
                if let error = model.invoiceError {
                    Text(LocalizedStringKey(error)).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.failure)
                        .multilineTextAlignment(.center).padding(.top, 6)
                        .accessibilityIdentifier("receive-invoice-error")
                }
                Spacer(minLength: 8)
                if !noteFocused {
                    SendKeypad(amount: $amount, decimal: useFiat, keyHeight: min(60, max(40, (proxy.size.height - 330) / 4 - 2))) { _ in
                        haptics.play(.selection)
                    }
                }
                Button(action: done) {
                    ZStack {
                        Text("Done").opacity(model.isSaving ? 0 : 1)
                        if model.isSaving { ProgressView().tint(NoahTokens.Colors.background) }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(HomePillStyle(height: 52, font: NoahTokens.Typography.button))
                .disabled(!valid || model.isSaving)
                .accessibilityIdentifier("receive-amount-submit")
                .padding(.top, 12)
            }
        }
        .padding(.horizontal, 22).padding(.top, 24).padding(.bottom, 16)
        .disabled(model.isSaving)
        .interactiveDismissDisabled(model.isSaving)
        .onboardingHaptics(haptics)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { noteFocused = false }
            }
        }
    }

    /// "Add a note": a chip that opens into today's description field.
    @ViewBuilder private var note: some View {
        if noteOpen {
            TextField("Description", text: $description, axis: .vertical)
                .noahText(NoahTokens.Typography.body)
                .lineLimit(1...3)
                .focused($noteFocused)
                .padding(.horizontal, 18).padding(.vertical, 12)
                .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .accessibilityIdentifier("receive-description-input")
        } else {
            Button("Add a note") {
                haptics.play(.light)
                noteOpen = true
                noteFocused = true
            }
            .buttonStyle(SendChipStyle())
            .accessibilityIdentifier("receive-add-note")
        }
    }

    private func done() {
        noteFocused = false
        if cleared {
            if request.amountSat != nil { model.removeAmount() }
            onDone(false)
            return
        }
        if sats == request.amountSat && description.trimmingCharacters(in: .whitespacesAndNewlines) == request.description {
            onDone(false)
            return
        }
        Task { if await model.save(amountSat: sats, description: description) { onDone(true) } }
    }

    private func toggleCurrency() {
        guard let btcPrice else { return }
        haptics.play(.selection)
        let sats = sats
        amount = useFiat ? (sats == 0 ? "" : String(sats))
            : FiatCurrency.satsToFiat(sats, btcPrice: btcPrice, currency: app.preferredCurrency)
                .replacingOccurrences(of: ".", with: locale.decimalSeparator ?? ".")
        useFiat.toggle()
    }
}
