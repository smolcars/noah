import Foundation

/// The five ways to pay you (send-receive.md, "Ways to pay you"). Copy and Share carry the chosen
/// format's payload, and the card's QR carries it too, except that the Lightning address's QR is its
/// LNURL-pay code.
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

    /// What Copy and Share carry; nil while the format is unavailable (no address yet, no
    /// request yet, or an invoice before an amount).
    func payload(request: ReceiveRequest?, address: String?) -> String? {
        switch self {
        case .lightningAddress: return address.flatMap { $0.isEmpty ? nil : $0 }
        case .request: return request?.uri(lightningAddress: address)
        case .invoice: return request?.lightningInvoice
        case .ark: return request?.arkAddress
        case .bitcoin: return request?.onchainAddress
        }
    }

    /// What the card's QR carries: the payload, except that the Lightning address is LUD-01's
    /// `LIGHTNING:LNURL1…`, which any LNURL wallet or phone camera reads (`user@domain` isn't a QR format).
    func qr(request: ReceiveRequest?, address: String?) -> String? {
        guard self == .lightningAddress else { return payload(request: request, address: address) }
        return payload(request: request, address: address).map { LightningIdentity.lnurlQR(address: $0) ?? $0 }
    }

    /// The card's caption under the name.
    var caption: String {
        switch self {
        case .lightningAddress, .invoice: "Scan with any Lightning wallet"
        case .request: "Scan with any bitcoin or Lightning wallet"
        case .ark: "Scan with an Ark wallet"
        case .bitcoin: "Scan with any bitcoin wallet"
        }
    }

    /// The list's one-line subtitle; `amount` is the formatted request amount, if any.
    func subtitle(amount: String?, locale: String = LegacyLocale.resolved()) -> String {
        switch self {
        case .lightningAddress: return L10n.text("From any Lightning wallet", locale: locale)
        case .request: return L10n.text("Bitcoin, Ark and Lightning in one code", locale: locale)
        case .invoice:
            return amount.map { L10n.text("For {amount} only", $0, locale: locale) } ?? L10n.text("Invoices need an amount", locale: locale)
        case .ark: return L10n.text("Instant and free from Ark wallets", locale: locale)
        case .bitcoin: return L10n.text("From any bitcoin wallet, about 20 min", locale: locale)
        }
    }

    /// The inverted amount band grows under the invoice once an amount exists: an amount is the
    /// invoice's alone.
    func showsBand(hasAmount: Bool) -> Bool { hasAmount && self == .invoice }

    /// What Receive shows first, and returns to: the payment request, every way to pay in one code.
    static let initial: Self = .request

    /// The format to show after you change the amount: an amount shows the invoice it made;
    /// removing it returns to the payment request, whatever was showing.
    static func after(amountChange hasAmount: Bool) -> Self { hasAmount ? .invoice : .initial }

    /// The format to keep for a fresh request (on returning to Receive, or after a payment):
    /// without an amount, the invoice returns to the payment request; the others stay.
    static func reconciled(_ current: Self, hasAmount: Bool) -> Self {
        !hasAmount && current == .invoice ? .initial : current
    }
}
