import AppIntents
import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private enum NoahAppIntentError: LocalizedError {
  case addressUnavailable
  case balanceUnavailable
  case copyUnavailable
  case invoiceDescriptionTooLong
  case invoiceUnavailable
  case invalidAmount

  var errorDescription: String? {
    switch self {
    case .addressUnavailable:
      return "Noah couldn't create an Ark address. Try again."
    case .balanceUnavailable:
      return "Noah couldn't refresh your balance. Check your connection and try again."
    case .copyUnavailable:
      return "Noah couldn't copy the invoice. Try again while Noah is open."
    case .invoiceDescriptionTooLong:
      return "The invoice description must be 639 characters or fewer."
    case .invoiceUnavailable:
      return "Noah couldn't create the Lightning invoice. Check your connection and try again."
    case .invalidAmount:
      return "The amount must be at least one sat."
    }
  }
}

private func createNoahLightningInvoice(
  amountSats: Int,
  invoiceDescription: String?
) async throws -> NoahArkBolt11Invoice {
  guard amountSats > 0 else {
    throw NoahAppIntentError.invalidAmount
  }

  let normalizedDescription = invoiceDescription?
    .trimmingCharacters(in: .whitespacesAndNewlines)
  let effectiveDescription = normalizedDescription?.isEmpty == false ? normalizedDescription : nil
  guard effectiveDescription?.utf16.count ?? 0 <= 639 else {
    throw NoahAppIntentError.invoiceDescriptionTooLong
  }

  do {
    return try await NoahNativeWalletService.shared.bolt11Invoice(
      amountSat: UInt64(amountSats),
      description: effectiveDescription
    )
  } catch {
    throw NoahAppIntentError.invoiceUnavailable
  }
}

struct CreateNoahArkAddressIntent: AppIntent {
  static let title: LocalizedStringResource = "Create Ark Address"
  static let description = IntentDescription(
    "Creates a new Ark address from Noah without opening the app."
  )
  static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

  func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
    let address: String
    do {
      address = try await NoahNativeWalletService.shared.newAddress()
    } catch {
      throw NoahAppIntentError.addressUnavailable
    }

    await MainActor.run {
      UIPasteboard.general.string = address
    }

    return .result(
      value: address,
      dialog: "Created a new Ark address and copied it to your clipboard."
    )
  }
}

struct GetNoahBalanceIntent: AppIntent {
  static let title: LocalizedStringResource = "Get Noah Balance"
  static let description = IntentDescription(
    "Synchronizes Noah and returns the current total balance."
  )
  static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

  func perform() async throws -> some IntentResult & ReturnsValue<Int> & ProvidesDialog {
    let nativeBalance: NoahNativeWalletBalance
    do {
      nativeBalance = try await NoahNativeWalletService.shared.refreshBalance()
    } catch {
      throw NoahAppIntentError.balanceUnavailable
    }

    guard nativeBalance.total <= UInt64(Int.max) else {
      throw NoahAppIntentError.balanceUnavailable
    }
    let balance = Int(nativeBalance.total)

    return .result(
      value: balance,
      dialog: "Your current Noah balance is \(balance) sats."
    )
  }
}

struct CreateNoahLightningInvoiceIntent: AppIntent {
  static let title: LocalizedStringResource = "Create Lightning Invoice"
  static let description = IntentDescription(
    "Creates a BOLT11 Lightning invoice from Noah without opening the app."
  )
  static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

  @Parameter(
    title: "Amount in Sats",
    description: "The number of satoshis to request.",
    requestValueDialog: "How many sats would you like to request?"
  )
  var amountSats: Int

  @Parameter(
    title: "Description",
    description: "An optional memo included in the invoice."
  )
  var invoiceDescription: String?

  static var parameterSummary: some ParameterSummary {
    Summary("Create a Lightning invoice for \(\.$amountSats) sats")
  }

  func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
    let invoice = try await createNoahLightningInvoice(
      amountSats: amountSats,
      invoiceDescription: invoiceDescription
    )

    return .result(
      value: invoice.paymentRequest,
      dialog: "Created a Lightning invoice for \(amountSats) sats."
    )
  }
}

@available(iOS 26.0, *)
struct CreateNoahLightningInvoiceWithSnippetIntent: AppIntent {
  static let title: LocalizedStringResource = "Create Lightning Invoice"
  static let description = IntentDescription(
    "Creates a BOLT11 Lightning invoice from Noah without opening the app."
  )
  static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

  @Parameter(
    title: "Amount in Sats",
    description: "The number of satoshis to request.",
    requestValueDialog: "How many sats would you like to request?"
  )
  var amountSats: Int

  @Parameter(
    title: "Description",
    description: "An optional memo included in the invoice."
  )
  var invoiceDescription: String?

  static var parameterSummary: some ParameterSummary {
    Summary("Create a Lightning invoice for \(\.$amountSats) sats")
  }

  func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog & ShowsSnippetIntent {
    let invoice = try await createNoahLightningInvoice(
      amountSats: amountSats,
      invoiceDescription: invoiceDescription
    )

    return .result(
      value: invoice.paymentRequest,
      dialog: IntentDialog(
        full: "Created a Lightning invoice for \(amountSats) sats. Tap Copy Invoice to open Noah and copy it.",
        supporting: "Your \(amountSats)-sat Lightning invoice is ready."
      ),
      snippetIntent: NoahLightningInvoiceSnippetIntent(
        invoice: invoice.paymentRequest,
        amountSats: amountSats
      )
    )
  }
}

@available(iOS 26.0, *)
struct NoahLightningInvoiceSnippetIntent: SnippetIntent {
  static let title: LocalizedStringResource = "Lightning Invoice"
  static let isDiscoverable = false

  @Parameter(title: "Invoice")
  var invoice: String

  @Parameter(title: "Amount in Sats")
  var amountSats: Int

  init() {}

  init(invoice: String, amountSats: Int) {
    self.invoice = invoice
    self.amountSats = amountSats
  }

  func perform() async throws -> some IntentResult & ShowsSnippetView {
    return .result(
      view: NoahLightningInvoiceSnippetView(
        invoice: invoice,
        amountSats: amountSats
      )
    )
  }
}

@available(iOS 26.0, *)
struct CopyNoahLightningInvoiceIntent: AppIntent {
  static let title: LocalizedStringResource = "Copy Invoice"
  static let description = IntentDescription("Copies a Lightning invoice to the clipboard.")
  static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
  static let isDiscoverable = false
  static let supportedModes: IntentModes = .foreground(.immediate)

  @Parameter(title: "Invoice")
  var invoice: String

  init() {}

  init(invoice: String) {
    self.invoice = invoice
  }

  @MainActor
  func perform() async throws -> some IntentResult & ProvidesDialog {
    let pasteboard = UIPasteboard.general
    pasteboard.setItems([
      [UTType.utf8PlainText.identifier: invoice]
    ])

    guard pasteboard.string == invoice else {
      throw NoahAppIntentError.copyUnavailable
    }

    return .result(dialog: "Copied the Lightning invoice to your clipboard.")
  }
}

@available(iOS 26.0, *)
private struct NoahLightningInvoiceSnippetView: View {
  let invoice: String
  let amountSats: Int

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      Label("Lightning Invoice", systemImage: "bolt.fill")
        .font(.headline)

      Text("\(amountSats.formatted()) sats")
        .font(.title2.bold())

      Text(invoice)
        .font(.caption.monospaced())
        .lineLimit(4)
        .truncationMode(.middle)

      Button(intent: CopyNoahLightningInvoiceIntent(invoice: invoice)) {
        Label("Copy Invoice", systemImage: "doc.on.doc")
      }
      .buttonStyle(.borderedProminent)
    }
    .padding()
  }
}

@available(iOS 17.4, *)
struct NoahAppShortcuts: AppShortcutsProvider {
  @AppShortcutsBuilder
  static var appShortcuts: [AppShortcut] {
    AppShortcut(
      intent: CreateNoahArkAddressIntent(),
      phrases: [
        "Create an Ark address with \(.applicationName)",
        "Get an Ark address from \(.applicationName)",
        "Generate an Ark address with \(.applicationName)",
      ],
      shortTitle: "Create Ark Address",
      systemImageName: "qrcode"
    )

    AppShortcut(
      intent: GetNoahBalanceIntent(),
      phrases: [
        "What's my balance in \(.applicationName)",
        "Check my \(.applicationName) balance",
        "How many sats are in \(.applicationName)",
      ],
      shortTitle: "Check Balance",
      systemImageName: "bitcoinsign.circle"
    )

    if #available(iOS 26.0, *) {
      AppShortcut(
        intent: CreateNoahLightningInvoiceWithSnippetIntent(),
        phrases: [
          "Create a Lightning invoice with \(.applicationName)",
          "Request sats with \(.applicationName)",
          "Generate an invoice with \(.applicationName)",
        ],
        shortTitle: "Create Lightning Invoice",
        systemImageName: "bolt.badge.plus"
      )
    }
  }
}
