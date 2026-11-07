import AppIntents // swiftlint:disable:this file_name - mirrors client/ios/SharedAppIntents/NoahAppIntents.swift
import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

private enum NoahAppIntentError: LocalizedError {
  case addressCopyUnavailable
  case copyUnavailable
  case invoiceDescriptionTooLong
  case invalidAmount

  var errorDescription: String? {
    switch self {
    case .addressCopyUnavailable:
      return L10n.text("Noah couldn't copy the Ark address. Try again while Noah is open.")
    case .copyUnavailable:
      return L10n.text("Noah couldn't copy the invoice. Try again while Noah is open.")
    case .invoiceDescriptionTooLong:
      return L10n.text("The invoice description must be 639 characters or fewer.")
    case .invalidAmount:
      return L10n.text("Ask for at least 1 sat.")
    }
  }
}

private func createNoahLightningInvoice(
  amountSats: Int,
  invoiceDescription: String?
) async throws -> NoahIntentWallet.Invoice {
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
    return try await NoahIntentWallet.current.bolt11Invoice(
      UInt64(amountSats),
      effectiveDescription
    )
  } catch {
    throw AssistantFailure(error)
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
      address = try await NoahIntentWallet.current.newAddress()
    } catch {
      throw AssistantFailure(error)
    }

    return .result(
      value: address,
      dialog: "\(AssistantReply.arkAddress())"
    )
  }
}

@available(iOS 26.0, *)
struct CreateNoahArkAddressWithSnippetIntent: AppIntent {
  static let title: LocalizedStringResource = "Create Ark Address"
  static let description = IntentDescription(
    "Creates a new Ark address from Noah without opening the app."
  )
  static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

  func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog & ShowsSnippetIntent {
    let address: String
    do {
      address = try await NoahIntentWallet.current.newAddress()
    } catch {
      throw AssistantFailure(error)
    }

    return .result(
      value: address,
      dialog: "\(AssistantReply.arkAddress())",
      snippetIntent: NoahArkAddressSnippetIntent(address: address)
    )
  }
}

@available(iOS 26.0, *)
struct NoahArkAddressSnippetIntent: SnippetIntent {
  static let title: LocalizedStringResource = "Ark Address"
  static let isDiscoverable = false

  @Parameter(title: "Address")
  var address: String

  init() { /* AppIntent requires an empty init. */ }

  init(address: String) {
    self.address = address
  }

  func perform() async throws -> some IntentResult & ShowsSnippetView { // swiftlint:disable:this async_without_await unneeded_throws_rethrows - RN copy
    return .result(view: NoahArkAddressSnippetView(address: address)) // swiftlint:disable:this implicit_return - RN copy
  }
}

@available(iOS 26.0, *)
struct CopyNoahArkAddressIntent: AppIntent {
  static let title: LocalizedStringResource = "Copy Address"
  static let description = IntentDescription("Copies an Ark address to the clipboard.")
  static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication
  static let isDiscoverable = false
  static let supportedModes: IntentModes = .foreground(.immediate)

  @Parameter(title: "Address")
  var address: String

  init() { /* AppIntent requires an empty init. */ }

  init(address: String) {
    self.address = address
  }

  @MainActor
  func perform() async throws -> some IntentResult & ProvidesDialog { // swiftlint:disable:this async_without_await - AppIntent.perform, as in the RN copy
    let pasteboard = UIPasteboard.general
    pasteboard.setItems([
      [UTType.utf8PlainText.identifier: address]
    ])

    guard pasteboard.string == address else {
      throw NoahAppIntentError.addressCopyUnavailable
    }

    let reply = L10n.text("Copied the Ark address to your clipboard.")
    return .result(dialog: "\(reply)")
  }
}

@available(iOS 26.0, *)
private struct NoahArkAddressSnippetView: View {
  let address: String

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      Label("Ark Address", systemImage: "qrcode")
        .font(.headline)

      Text(address)
        .font(.caption.monospaced())
        .lineLimit(4)
        .truncationMode(.middle)

      Button(intent: CopyNoahArkAddressIntent(address: address)) {
        Label("Copy Address", systemImage: "doc.on.doc")
      }
      .buttonStyle(.borderedProminent)
    }
    .padding()
  }
}

struct GetNoahBalanceIntent: AppIntent {
  static let title: LocalizedStringResource = "Get Noah Balance"
  static let description = IntentDescription(
    "Synchronizes Noah and returns the current total balance."
  )
  static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

  func perform() async throws -> some IntentResult & ReturnsValue<Int> & ProvidesDialog {
    let wallet = NoahIntentWallet.current
    let nativeBalance: UInt64
    do {
      nativeBalance = try await wallet.refreshBalance()
    } catch {
      throw AssistantFailure(error)
    }

    guard nativeBalance <= UInt64(Int.max) else {
      throw AssistantFailure.unreachable
    }
    let balance = Int(nativeBalance)
    let locale = LegacyLocale.resolved()

    return .result(
      value: balance,
      dialog: "\(AssistantReply.balance(sats: nativeBalance, fiat: wallet.fiat(nativeBalance, locale), locale: locale))"
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
      dialog: "\(AssistantReply.request(sats: Int64(amountSats)))"
    )
  }
}

@available(iOS 26.0, *) // swiftlint:disable:next type_name - the RN copy's persisted intent identifier
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
      dialog: "\(AssistantReply.request(sats: Int64(amountSats)))",
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

  init() { /* AppIntent requires an empty init. */ }

  init(invoice: String, amountSats: Int) {
    self.invoice = invoice
    self.amountSats = amountSats
  }

  func perform() async throws -> some IntentResult & ShowsSnippetView { // swiftlint:disable:this async_without_await unneeded_throws_rethrows - RN copy
    return .result( // swiftlint:disable:this implicit_return - RN copy
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

  init() { /* AppIntent requires an empty init. */ }

  init(invoice: String) {
    self.invoice = invoice
  }

  @MainActor
  func perform() async throws -> some IntentResult & ProvidesDialog { // swiftlint:disable:this async_without_await - AppIntent.perform, as in the RN copy
    let pasteboard = UIPasteboard.general
    pasteboard.setItems([
      [UTType.utf8PlainText.identifier: invoice]
    ])

    guard pasteboard.string == invoice else {
      throw NoahAppIntentError.copyUnavailable
    }

    let reply = L10n.text("Copied the Lightning invoice to your clipboard.")
    return .result(dialog: "\(reply)")
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

      Text("\(amountSats.formatted(.number.locale(Locale(identifier: LegacyLocale.resolved())))) sats")
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
