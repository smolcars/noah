import AppIntents
import Foundation
import Testing
import UIKit
@testable import Noah

@MainActor struct AppIntentTests {
    @Test func identifiersMatchRNTypesAndParameters() throws {
        #expect(CreateNoahArkAddressIntent.persistentIdentifier == "CreateNoahArkAddressIntent")
        #expect(GetNoahBalanceIntent.persistentIdentifier == "GetNoahBalanceIntent")
        #expect(CreateNoahLightningInvoiceIntent.persistentIdentifier == "CreateNoahLightningInvoiceIntent")
        if #available(iOS 26.0, *) {
            #expect(CreateNoahArkAddressWithSnippetIntent.persistentIdentifier == "CreateNoahArkAddressWithSnippetIntent")
            #expect(NoahArkAddressSnippetIntent.persistentIdentifier == "NoahArkAddressSnippetIntent")
            #expect(CopyNoahArkAddressIntent.persistentIdentifier == "CopyNoahArkAddressIntent")
            #expect(CreateNoahLightningInvoiceWithSnippetIntent.persistentIdentifier == "CreateNoahLightningInvoiceWithSnippetIntent")
            #expect(NoahLightningInvoiceSnippetIntent.persistentIdentifier == "NoahLightningInvoiceSnippetIntent")
            #expect(CopyNoahLightningInvoiceIntent.persistentIdentifier == "CopyNoahLightningInvoiceIntent")
        }
        // Source contract catches a renamed parameter or Siri phrase, including hidden snippets.
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let rn = try String(contentsOf: repo.appending(path: "client/ios/SharedAppIntents/NoahAppIntents.swift"), encoding: .utf8)
        let native = try String(contentsOf: repo.appending(path: "apps/ios/Noah/Services/AppIntents/NoahAppIntents.swift"), encoding: .utf8)
        func contract(_ text: String) -> [String] {
            text.components(separatedBy: .newlines).filter {
                $0.hasPrefix("struct ") || $0.hasPrefix("  var ") || $0.contains(".applicationName)") || $0.contains("authenticationPolicy:")
            }
        }
        #expect(contract(rn) == contract(native))
        #expect(!native.contains("SendPaymentController"))
    }

    @Test func balanceAndAddressUseAdapterAndReturnValues() async throws {
        let wallet = NoahIntentWallet(newAddress: { "ark-address" }, bolt11Invoice: { _, _ in .init(paymentRequest: "invoice") }, refreshBalance: { 12_345 })
        try await NoahIntentWallet.$current.withValue(wallet) {
            let balance = try await GetNoahBalanceIntent().perform()
            #expect(balance.value == 12_345)
            let address = try await CreateNoahArkAddressIntent().perform()
            #expect(address.value == "ark-address")
            if #available(iOS 26.0, *) {
                let snippet = try await CreateNoahArkAddressWithSnippetIntent().perform()
                #expect(snippet.value == "ark-address")
            }
        }
    }

    @Test(arguments: [0, -1]) func invoiceRejectsInvalidAmountsBeforeWalletAccess(amount: Int) async {
        let wallet = NoahIntentWallet(newAddress: { Issue.record("Unexpected address access"); return "" },
                                     bolt11Invoice: { _, _ in Issue.record("Unexpected wallet access"); return .init(paymentRequest: "") }, refreshBalance: { 0 })
        await NoahIntentWallet.$current.withValue(wallet) {
            var intent = CreateNoahLightningInvoiceIntent(); intent.amountSats = amount
            await #expect(throws: (any Error).self) { _ = try await intent.perform() }
        }
    }

    @Test func invoicePreservesParameterNamesTrimsMemoAndChecksUTF16Limit() async throws {
        let wallet = NoahIntentWallet(newAddress: { "unused" }, bolt11Invoice: { amount, memo in
            #expect(amount == 42); #expect(memo == "memo")
            return .init(paymentRequest: "ln-invoice")
        }, refreshBalance: { 0 })
        try await NoahIntentWallet.$current.withValue(wallet) {
            var intent = CreateNoahLightningInvoiceIntent(); intent.amountSats = 42; intent.invoiceDescription = "  memo\n"
            let result = try await intent.perform()
            #expect(result.value == "ln-invoice")
            if #available(iOS 26.0, *) {
                var snippet = CreateNoahLightningInvoiceWithSnippetIntent(); snippet.amountSats = 42; snippet.invoiceDescription = "memo"
                #expect(try await snippet.perform().value == "ln-invoice")
            }
            intent.invoiceDescription = String(repeating: "🪙", count: 320)
            await #expect(throws: (any Error).self) { _ = try await intent.perform() }
        }
    }

    @Test func balanceRejectsOverflowAndHidesUnderlyingErrors() async {
        let wallet = NoahIntentWallet(newAddress: { throw CocoaError(.fileReadUnknown) },
                                     bolt11Invoice: { _, _ in throw CocoaError(.fileReadUnknown) }, refreshBalance: { .max })
        await NoahIntentWallet.$current.withValue(wallet) {
            await #expect(throws: (any Error).self) { _ = try await GetNoahBalanceIntent().perform() }
            await #expect(throws: (any Error).self) { _ = try await CreateNoahArkAddressIntent().perform() }
        }
    }

    @Test func copyIntentsKeepForegroundModeAndParameters() async throws {
        if #available(iOS 26.0, *) {
            _ = try await CopyNoahArkAddressIntent(address: "test-ark").perform()
            #expect(UIPasteboard.general.string == "test-ark")
            _ = try await CopyNoahLightningInvoiceIntent(invoice: "test-invoice").perform()
            #expect(UIPasteboard.general.string == "test-invoice")
            UIPasteboard.general.items = []
            #expect(!CopyNoahArkAddressIntent.isDiscoverable)
            #expect(!CopyNoahLightningInvoiceIntent.isDiscoverable)
        }
    }
}
