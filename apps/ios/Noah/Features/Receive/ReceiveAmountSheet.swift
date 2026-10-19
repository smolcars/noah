import SwiftUI

struct ReceiveAmountSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    let request: ReceiveRequest
    @Bindable var model: ReceiveModel
    let btcPrice: Double?
    @State private var amount: String
    @State private var description: String
    @State private var useFiat = false
    @FocusState private var focus: Field?
    private enum Field { case amount, description }

    init(request: ReceiveRequest, model: ReceiveModel, btcPrice: Double?) {
        self.request = request
        self.model = model
        self.btcPrice = btcPrice
        _amount = State(initialValue: request.amountSat.map(String.init) ?? "")
        _description = State(initialValue: request.description)
    }

    private var sats: Int64 {
        let normalized = amount.replacingOccurrences(of: locale.decimalSeparator ?? ".", with: ".")
        if useFiat, let btcPrice { return FiatCurrency.fiatToSats(Double(normalized) ?? .nan, btcPrice: btcPrice) }
        return Int64(normalized) ?? 0
    }
    private var valid: Bool { (try? ReceiveRequest.validate(amountSat: sats, description: description)) != nil }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    HStack(alignment: .firstTextBaseline) {
                        if !useFiat && app.amountUnit == .bip177 { Text("₿").font(NoahFont.largeTitle) }
                        TextField("Amount", text: $amount)
                            .keyboardType(useFiat ? .decimalPad : .numberPad)
                            .font(NoahFont.inter(44, .bold, relativeTo: .largeTitle))
                            .focused($focus, equals: .amount)
                            .accessibilityIdentifier("receive-amount-input")
                        Text(useFiat ? app.preferredCurrency : app.amountUnit == .sats ? "sats" : "")
                            .font(NoahFont.headline)
                    }
                    Button(action: toggleCurrency) {
                        HStack {
                            if useFiat {
                                Text(BitcoinAmount.format(sats: sats, unit: app.amountUnit, locale: locale))
                            } else if let btcPrice {
                                Text(FiatCurrency.display(sats: sats, btcPrice: btcPrice, currency: app.preferredCurrency, locale: locale))
                            } else {
                                Text("\(app.preferredCurrency) rate unavailable")
                            }
                            Image(systemName: "arrow.up.arrow.down")
                        }
                    }
                    .foregroundStyle(btcPrice == nil ? NoahColor.mutedForeground : NoahColor.success)
                    .disabled(btcPrice == nil || model.isSaving)
                    .accessibilityLabel(useFiat ? "Switch to sats" : "Switch to \(app.preferredCurrency)")
                    .accessibilityIdentifier("receive-currency-toggle")
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Add a note").font(NoahFont.headline)
                        TextField("Description", text: $description, axis: .vertical)
                            .lineLimit(2...5).focused($focus, equals: .description)
                            .textFieldStyle(.roundedBorder)
                            .accessibilityIdentifier("receive-description-input")
                        if description.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count > 639 {
                            Text("Note must be 639 characters or fewer.").foregroundStyle(NoahColor.destructive)
                        }
                    }
                    if let error = model.invoiceError {
                        Text(error).foregroundStyle(NoahColor.destructive).accessibilityIdentifier("receive-invoice-error")
                    }
                }
                .padding(24)
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 8) {
                    NoahButton(title: request.amountSat == nil ? "Next" : "Update request", isLoading: model.isSaving) {
                        focus = nil
                        Task { if await model.save(amountSat: sats, description: description) { dismiss() } }
                    }
                    .disabled(!valid).accessibilityIdentifier("receive-amount-submit")
                    if request.amountSat != nil {
                        NoahButton(title: "Remove amount", kind: .secondary) { model.removeAmount(); dismiss() }
                            .disabled(model.isSaving).accessibilityIdentifier("receive-amount-remove")
                    }
                }
                .padding().background(NoahColor.background)
            }
            .navigationTitle(request.amountSat == nil ? "Add amount" : "Edit amount")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(model.isSaving)
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focus = nil }
                }
            }
            .disabled(model.isSaving)
            .background(NoahColor.background)
        }
        .interactiveDismissDisabled(model.isSaving)
    }

    private func toggleCurrency() {
        guard let btcPrice else { return }
        let sats = sats
        amount = useFiat ? String(sats) : FiatCurrency.satsToFiat(sats, btcPrice: btcPrice, currency: app.preferredCurrency)
            .replacingOccurrences(of: ".", with: locale.decimalSeparator ?? ".")
        useFiat.toggle()
    }
}
