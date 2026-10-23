import Foundation

/// The five ways to pay you (send-receive.md, "Ways to pay you"). The card's QR, Copy and
/// Share all carry exactly the chosen format's payload.
enum ReceiveFormat: String, CaseIterable, Identifiable, Sendable {
    case lightningAddress, request, invoice, ark, bitcoin

    var id: String { rawValue }

    /// Source text, localized by the views.
    var name: String {
        switch self {
        case .lightningAddress: "Lightning address"
        case .request: "Payment request"
        case .invoice: "Lightning invoice"
        case .ark: "Ark address"
        case .bitcoin: "Bitcoin address"
        }
    }

    /// What the QR, Copy and Share carry; nil while the format is unavailable (no address
    /// yet, no request yet, or an invoice before an amount).
    func payload(request: ReceiveRequest?, address: String?) -> String? {
        switch self {
        case .lightningAddress: return address.flatMap { $0.isEmpty ? nil : $0 }
        case .request: return try? request?.uri()
        case .invoice: return request?.lightningInvoice
        case .ark: return request?.arkAddress
        case .bitcoin: return request?.onchainAddress
        }
    }

    /// The card's caption under the name.
    func caption(hasAmount: Bool) -> String {
        switch self {
        case .lightningAddress, .invoice: "Scan with any Lightning wallet"
        case .request: hasAmount ? "Scan with any bitcoin or Lightning wallet" : "Scan with any bitcoin wallet"
        case .ark: "Scan with an Ark wallet"
        case .bitcoin: "Scan with any bitcoin wallet"
        }
    }

    /// The list's one-line subtitle; `amount` is the formatted request amount, if any.
    func subtitle(amount: String?, locale: String = LegacyLocale.resolved()) -> String {
        switch self {
        case .lightningAddress: return L10n.text("From any Lightning wallet", locale: locale)
        case .request:
            return amount.map { L10n.text("Bitcoin, Ark and Lightning for {amount}", $0, locale: locale) }
                ?? L10n.text("Bitcoin and Ark in one code. Add an amount for Lightning", locale: locale)
        case .invoice:
            return amount.map { L10n.text("For {amount} only", $0, locale: locale) } ?? L10n.text("Invoices need an amount", locale: locale)
        case .ark: return L10n.text("Instant and free from Ark wallets", locale: locale)
        case .bitcoin: return L10n.text("From any bitcoin wallet, about 20 min", locale: locale)
        }
    }

    /// The inverted amount band grows under the request and the invoice once an amount exists.
    func showsBand(hasAmount: Bool) -> Bool { hasAmount && (self == .request || self == .invoice) }

    /// The format to show after you change the amount: an amount shows the unified request;
    /// removing it returns to the Lightning address, whatever was showing.
    static func after(amountChange hasAmount: Bool) -> Self { hasAmount ? .request : .lightningAddress }

    /// The format to keep for a fresh request (on returning to Receive, or after a payment):
    /// without an amount, the request and invoice return to the Lightning address; the
    /// addresses stay.
    static func reconciled(_ current: Self, hasAmount: Bool) -> Self {
        !hasAmount && (current == .request || current == .invoice) ? .lightningAddress : current
    }
}
