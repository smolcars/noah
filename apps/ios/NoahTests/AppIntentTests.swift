import AppIntents
import Foundation
@testable import Noah
import Testing

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
        // Source contract catches a renamed type or parameter, including hidden snippets, and a dropped RN Siri phrase.
        // The fixture holds the contract lines of the RN app's NoahAppIntents.swift, frozen when `client/` was removed.
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        let rn = try String(contentsOf: repo.appending(path: "apps/fixtures/app-intents-contract.txt"), encoding: .utf8)
        let native = try ["NoahAppIntents.swift", "NoahAppShortcuts.swift"].map {
            try String(contentsOf: repo.appending(path: "apps/ios/Noah/Services/AppIntents/\($0)"), encoding: .utf8)
        }.joined(separator: "\n")
        func contract(_ text: String) -> [String] {
            text.components(separatedBy: .newlines).filter {
                $0.hasPrefix("struct ") || $0.hasPrefix("  var ") || $0.contains(".applicationName)") || $0.contains("authenticationPolicy:")
            }
        }
        func phrase(_ line: String) -> Bool { line.contains(".applicationName)") }
        #expect(contract(rn).filter { !phrase($0) } == contract(native).filter { !phrase($0) })
        let phrases = Set(contract(native).filter(phrase).map { $0.trimmingCharacters(in: .whitespaces) })
        #expect(Set(contract(rn).filter(phrase).map { $0.trimmingCharacters(in: .whitespaces) }).isSubset(of: phrases))
        #expect(!native.contains("SendPaymentController"))
    }

    @Test(arguments: [0, -1]) func invoiceRejectsInvalidAmountsBeforeWalletAccess(amount: Int) async {
        let wallet = NoahIntentWallet(newAddress: { Issue.record("Unexpected address access"); return "" },
                                      bolt11Invoice: { _, _ in Issue.record("Unexpected wallet access"); return .init(paymentRequest: "") },
                                      refreshBalance: { 0 })
        await NoahIntentWallet.$current.withValue(wallet) {
            let intent = CreateNoahLightningInvoiceIntent(); intent.amountSats = amount
            await #expect(throws: (any Error).self) { _ = try await intent.perform() }
        }
    }

    @Test func invoicePreservesParameterNamesTrimsMemoAndChecksUTF16Limit() async throws {
        let wallet = NoahIntentWallet(newAddress: { "unused" }, bolt11Invoice: { amount, memo in
            #expect(amount == 42); #expect(memo == "memo")
            return .init(paymentRequest: "ln-invoice")
        }, refreshBalance: { 0 })
        try await NoahIntentWallet.$current.withValue(wallet) {
            let intent = CreateNoahLightningInvoiceIntent(); intent.amountSats = 42; intent.invoiceDescription = "  memo\n"
            let result = try await intent.perform()
            #expect(result.value == "ln-invoice")
            if #available(iOS 26.0, *) {
                let snippet = CreateNoahLightningInvoiceWithSnippetIntent(); snippet.amountSats = 42; snippet.invoiceDescription = "memo"
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

    @Test func balanceGivesFiatItsSatsAndTheAppLanguage() async throws {
        let wallet = NoahIntentWallet(newAddress: { "" }, bolt11Invoice: { _, _ in .init(paymentRequest: "") }, refreshBalance: { 694_375 },
                                      fiat: { sats, locale in
                                          #expect(sats == 694_375); #expect(locale == LegacyLocale.resolved())
                                          return "$412.50"
                                      })
        let balance = try await NoahIntentWallet.$current.withValue(wallet) { try await GetNoahBalanceIntent().perform().value }
        #expect(balance == 694_375)
    }

    @Test func lightningAddressIsOptional() async throws {
        func wallet(_ address: String?) -> NoahIntentWallet {
            NoahIntentWallet(newAddress: { Issue.record("Unexpected wallet access"); return "" },
                             bolt11Invoice: { _, _ in Issue.record("Unexpected wallet access"); return .init(paymentRequest: "") },
                             refreshBalance: { Issue.record("Unexpected wallet access"); return 0 }, lightningAddress: { address })
        }
        let found = try await NoahIntentWallet.$current.withValue(wallet("sam@noahwallet.com")) {
            try await GetNoahLightningAddressIntent().perform().value
        }
        #expect(found == "sam@noahwallet.com")
        let none = try await NoahIntentWallet.$current.withValue(wallet(nil)) { try await GetNoahLightningAddressIntent().perform().value }
        #expect(none == String?.none)
    }

    @Test func walletFailuresSayWhatToDoNext() async {
        let wallet = NoahIntentWallet(newAddress: { throw AppServices.WalletSuspended() },
                                      bolt11Invoice: { _, _ in throw WalletSession.OpenError.busy },
                                      refreshBalance: { throw URLError(.notConnectedToInternet) },
                                      history: { throw AppServices.SeedUnavailable() },
                                      lightningAddress: { throw AppServices.WalletSuspended() })
        await NoahIntentWallet.$current.withValue(wallet) {
            await #expect(throws: AssistantFailure.suspended) { _ = try await CreateNoahArkAddressIntent().perform() }
            let invoice = CreateNoahLightningInvoiceIntent(); invoice.amountSats = 1
            await #expect(throws: AssistantFailure.busy) { _ = try await invoice.perform() }
            await #expect(throws: AssistantFailure.unreachable) { _ = try await GetNoahBalanceIntent().perform() }
            await #expect(throws: AssistantFailure.notReady) { _ = try await GetNoahLastPaymentIntent().perform() }
            await #expect(throws: AssistantFailure.suspended) { _ = try await GetNoahLightningAddressIntent().perform() }
        }
    }

    @Test func lastPaymentReadsHistoryOnce() async throws {
        let reads = Counter()
        let wallet = NoahIntentWallet(newAddress: { "" }, bolt11Invoice: { _, _ in .init(paymentRequest: "") }, refreshBalance: { 0 },
                                      history: { await reads.add(); return [] })
        try await NoahIntentWallet.$current.withValue(wallet) { _ = try await GetNoahLastPaymentIntent().perform() }
        #expect(await reads.value == 1)
    }

    @Test func openIntentsUseTheWidgetsRoutes() {
        for destination in [WidgetRoute.Destination.receive, .scan] {
            #expect(WidgetRoute.destination(WidgetRoute.url(destination, variant: .regtest), variant: .regtest) == destination)
        }
        #expect(WidgetRoute.url(.scan, variant: .mainnet).absoluteString == "com.noahwallet.mainnet://widget/scan")
    }
}

private actor Counter {
    private(set) var value = 0
    func add() { value += 1 }
}
