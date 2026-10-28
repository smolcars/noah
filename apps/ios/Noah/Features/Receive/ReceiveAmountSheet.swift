import SwiftUI

/// The amount being edited, kept by Receive outside both editors (the compact sheet and
/// the wide pane), so folding or unfolding mid-edit, or an on-chain arrival, keeps it.
@MainActor @Observable final class ReceiveAmountDraft {
    /// The request it edits; a new request starts a new draft.
    let request: ReceiveRequest
    /// Canonical: digits and at most one ".".
    var amount: String
    var description: String
    var useFiat = false
    var noteOpen: Bool

    init(request: ReceiveRequest) {
        self.request = request
        amount = request.amountSat.map(String.init) ?? ""
        description = request.description
        noteOpen = !request.description.isEmpty
    }
}

/// Add amount (send-receive.md): the same keypad as Send plus an "Add a note" chip (today's
/// invoice description). Done runs today's invoice creation; clearing the amount returns
/// to the Lightning address.
struct ReceiveAmountSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @Bindable var draft: ReceiveAmountDraft
    @Bindable var model: ReceiveModel
    let btcPrice: Double?
    /// Called when the sheet should close; true once an invoice for a new amount exists.
    let onDone: (Bool) -> Void
    @State private var haptics = OnboardingHaptics()
    @FocusState private var noteFocused: Bool

    private var request: ReceiveRequest { draft.request }
    private var amount: String { draft.amount }
    private var description: String { draft.description }
    private var useFiat: Bool { draft.useFiat }

    private var parsed: Int64? { Self.sats(amount, fiat: useFiat, btcPrice: btcPrice) }
    private var sats: Int64 { parsed ?? 0 }
    /// Only an emptied amount removes it; text that isn't a valid amount can't be saved.
    private var cleared: Bool { amount.isEmpty }
    private var valid: Bool {
        cleared || parsed.map { (try? ReceiveRequest.validate(amountSat: $0, description: description)) != nil } == true
    }
    private var noteTooLong: Bool { description.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count > 639 }

    private var display: (text: String, empty: Bool) {
        if useFiat {
            let formatter = NumberFormatter()
            formatter.numberStyle = .currency
            formatter.currencyCode = app.preferredCurrency
            formatter.locale = locale
            let shown = amount.replacingOccurrences(of: ".", with: locale.decimalSeparator ?? ".")
            return ((formatter.currencySymbol ?? "") + (amount.isEmpty ? "0" : shown), amount.isEmpty)
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
                currencyToggle
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
                    SendKeypad(amount: $draft.amount, decimal: useFiat, keyHeight: min(60, max(40, (proxy.size.height - 330) / 4 - 2))) { _ in
                        haptics.play(.selection)
                    }
                }
                doneButton
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

    private var currencyToggle: some View {
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
    }

    private var doneButton: some View {
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

    /// "Add a note": a chip that opens into today's description field.
    @ViewBuilder private var note: some View {
        if draft.noteOpen {
            TextField("Add a note", text: $draft.description, axis: .vertical)
                .noahText(NoahTokens.Typography.body)
                .lineLimit(1...3)
                .focused($noteFocused)
                .padding(.horizontal, 18).padding(.vertical, 12)
                .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .accessibilityIdentifier("receive-description-input")
        } else {
            Button("Add a note") {
                haptics.play(.light)
                draft.noteOpen = true
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
        guard valid, let sats = parsed else { return }
        if sats == request.amountSat && description.trimmingCharacters(in: .whitespacesAndNewlines) == request.description {
            onDone(false)
            return
        }
        Task { if await model.save(amountSat: sats, description: description) { onDone(true) } }
    }

    /// The typed amount in sats; nil when the text isn't an amount. The text stays canonical
    /// (digits and at most one "."); only the display uses the locale's separator.
    static func sats(_ amount: String, fiat: Bool, btcPrice: Double?) -> Int64? {
        guard !amount.isEmpty else { return 0 }
        guard fiat else { return Int64(amount) }
        guard let btcPrice, let value = Double(amount), value.isFinite else { return nil }
        return FiatCurrency.fiatToSats(value, btcPrice: btcPrice)
    }

    /// The amount in the other currency, canonical either way (satsToFiat always writes ".").
    /// Only empty stays empty (a clear); a zero stays an invalid "0", never a removal.
    static func switched(_ amount: String, fromFiat: Bool, btcPrice: Double, currency: String) -> String {
        guard !amount.isEmpty else { return "" }
        guard let sats = sats(amount, fiat: fromFiat, btcPrice: btcPrice), sats > 0 else { return "0" }
        return fromFiat ? String(sats) : FiatCurrency.satsToFiat(sats, btcPrice: btcPrice, currency: currency)
    }

    private func toggleCurrency() {
        guard let btcPrice else { return }
        haptics.play(.selection)
        draft.amount = Self.switched(amount, fromFiat: useFiat, btcPrice: btcPrice, currency: app.preferredCurrency)
        draft.useFiat.toggle()
    }
}
