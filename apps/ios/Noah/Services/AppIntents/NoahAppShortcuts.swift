import AppIntents

/// What Siri listens for. Siri only runs a shortcut when the sentence is close to one of these, so each one lists
/// the ways people ask, with the app's name in several places; balance phrases that avoid "balance" dodge Apple
/// Wallet. Phrases can't hold amounts (only entities and enums), so Request asks for one and Pay takes only a
/// recipient. At most 10 shortcuts. Translations: `Resources/AppShortcuts.xcstrings`, kept in step by
/// `AppShortcutPhraseTests`.
@available(iOS 17.4, *)
struct NoahAppShortcuts: AppShortcutsProvider {
    @AppShortcutsBuilder
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: GetNoahBalanceIntent(),
            phrases: [
                "How many sats do I have in \(.applicationName)",
                "What's my balance in \(.applicationName)",
                "What is my balance in \(.applicationName)",
                "Check my \(.applicationName) balance",
                "How many sats are in \(.applicationName)",
                "How much bitcoin do I have in \(.applicationName)",
                "How much bitcoin is in \(.applicationName)",
                "How much money do I have in \(.applicationName)",
                "How much do I have in \(.applicationName)",
                "What's in my \(.applicationName) wallet",
                "What is in my \(.applicationName) wallet",
                "How much is in my \(.applicationName) wallet",
                "What's my \(.applicationName) balance",
                "What is my \(.applicationName) balance",
                "Show my \(.applicationName) balance",
                "Get my balance from \(.applicationName)",
                "Check my sats in \(.applicationName)",
                "Check my bitcoin in \(.applicationName)",
                "Ask \(.applicationName) how many sats I have",
                "Ask \(.applicationName) for my balance",
                "Use \(.applicationName) to check my balance",
                "\(.applicationName) balance",
            ],
            shortTitle: "Check Balance",
            systemImageName: "bitcoinsign.circle")

        if #available(iOS 26.0, *) {
            AppShortcut(
                intent: CreateNoahLightningInvoiceWithSnippetIntent(),
                phrases: [
                    "Create a Lightning invoice with \(.applicationName)",
                    "Request sats with \(.applicationName)",
                    "Request some sats with \(.applicationName)",
                    "Use \(.applicationName) to request some sats",
                    "Use \(.applicationName) to request sats",
                    "Request bitcoin with \(.applicationName)",
                    "Request money with \(.applicationName)",
                    "Request a payment with \(.applicationName)",
                    "Generate an invoice with \(.applicationName)",
                    "Make a Lightning invoice with \(.applicationName)",
                    "Create a payment request with \(.applicationName)",
                    "Receive sats with \(.applicationName)",
                    "Receive bitcoin with \(.applicationName)",
                    "Get paid with \(.applicationName)",
                    "I want to get paid with \(.applicationName)",
                    "Use \(.applicationName) to get paid",
                    "Make an invoice in \(.applicationName)",
                    "Make me an invoice in \(.applicationName)",
                    "Make me an invoice with \(.applicationName)",
                    "Create an invoice in \(.applicationName)",
                    "Create an invoice with \(.applicationName)",
                    "Ask \(.applicationName) for an invoice",
                    "Ask \(.applicationName) for a Lightning invoice",
                    "Give me a Lightning invoice from \(.applicationName)",
                ],
                shortTitle: "Create Lightning Invoice",
                systemImageName: "bolt")

            AppShortcut(
                intent: CreateNoahArkAddressWithSnippetIntent(),
                phrases: [
                    "Create an Ark address with \(.applicationName)",
                    "Get an Ark address from \(.applicationName)",
                    "Generate an Ark address with \(.applicationName)",
                    "Make an Ark address with \(.applicationName)",
                    "Show me an Ark address from \(.applicationName)",
                    "Receive with Ark in \(.applicationName)",
                    "Give me an Ark address from \(.applicationName)",
                    "Give me a bitcoin address from \(.applicationName)",
                    "Get a bitcoin address from \(.applicationName)",
                    "Create a bitcoin address with \(.applicationName)",
                    "Give me an address from \(.applicationName)",
                    "Get a new address from \(.applicationName)",
                    "Make a new address in \(.applicationName)",
                    "New Ark address in \(.applicationName)",
                    "Ask \(.applicationName) for an address",
                    "Ask \(.applicationName) for a bitcoin address",
                    "Use \(.applicationName) to get an address",
                ],
                shortTitle: "Create Ark Address",
                systemImageName: "qrcode")
        }

        AppShortcut(
            intent: GetNoahLightningAddressIntent(),
            phrases: [
                "What's my Lightning address in \(.applicationName)",
                "What is my Lightning address in \(.applicationName)",
                "What's my \(.applicationName) Lightning address",
                "What is my \(.applicationName) Lightning address",
                "What's my \(.applicationName) address",
                "What is my \(.applicationName) address",
                "What's my \(.applicationName) username",
                "What is my \(.applicationName) username",
                "Show my Lightning address in \(.applicationName)",
                "Show my \(.applicationName) Lightning address",
                "Get my Lightning address from \(.applicationName)",
                "Tell me my \(.applicationName) Lightning address",
                "Ask \(.applicationName) for my Lightning address",
                "Use \(.applicationName) to get my Lightning address",
                "How can people pay me in \(.applicationName)",
            ],
            shortTitle: "Lightning Address",
            systemImageName: "at")

        AppShortcut(
            intent: GetNoahLastPaymentIntent(),
            phrases: [
                "What was my last payment in \(.applicationName)",
                "What was my last \(.applicationName) payment",
                "Show my last payment in \(.applicationName)",
                "What's my latest payment in \(.applicationName)",
                "What is my latest payment in \(.applicationName)",
                "What was my last transaction in \(.applicationName)",
                "What's my latest transaction in \(.applicationName)",
                "What is my latest transaction in \(.applicationName)",
                "Did I get paid in \(.applicationName)",
                "Did I receive anything in \(.applicationName)",
                "Any new payments in \(.applicationName)",
                "Check my recent activity in \(.applicationName)",
                "Ask \(.applicationName) about my last payment",
                "Use \(.applicationName) to check my last payment",
                "Last payment in \(.applicationName)",
            ],
            shortTitle: "Last Payment",
            systemImageName: "clock.arrow.circlepath")

        AppShortcut(
            intent: OpenNoahReceiveIntent(),
            phrases: [
                "Open Receive in \(.applicationName)",
                "Show Receive in \(.applicationName)",
                "Take me to Receive in \(.applicationName)",
                "Open \(.applicationName) to receive",
                "Open \(.applicationName) to get paid",
                "Open the receive screen in \(.applicationName)",
                "Receive in \(.applicationName)",
            ],
            shortTitle: "Receive",
            systemImageName: "arrow.down.left")

        AppShortcut(
            intent: OpenNoahScanIntent(),
            phrases: [
                "Scan a QR code with \(.applicationName)",
                "Scan a QR code in \(.applicationName)",
                "Scan with \(.applicationName)",
                "Scan to pay with \(.applicationName)",
                "Scan a Lightning invoice with \(.applicationName)",
                "Scan a bitcoin QR code with \(.applicationName)",
                "Open the scanner in \(.applicationName)",
                "Open the \(.applicationName) scanner",
                "Open the camera in \(.applicationName)",
                "Use \(.applicationName) to scan a QR code",
            ],
            shortTitle: "Scan QR Code",
            systemImageName: "qrcode.viewfinder")

        // The recipient's names come from `NoahRecipientQuery.suggestedEntities`; without one Siri asks who.
        if #available(iOS 18.4, *) {
            AppShortcut(
                intent: PayNoahRecipientIntent(),
                phrases: [
                    "Pay \(\.$recipient) with \(.applicationName)",
                    "Pay \(\.$recipient) in \(.applicationName)",
                    "Send sats to \(\.$recipient) in \(.applicationName)",
                    "Send sats to \(\.$recipient) with \(.applicationName)",
                    "Send money to \(\.$recipient) with \(.applicationName)",
                    "Send bitcoin to \(\.$recipient) with \(.applicationName)",
                    "Use \(.applicationName) to pay \(\.$recipient)",
                    "Pay someone with \(.applicationName)",
                    "Send money with \(.applicationName)",
                    "Send sats with \(.applicationName)",
                    "Send bitcoin with \(.applicationName)",
                    "Make a payment with \(.applicationName)",
                ],
                shortTitle: "Pay Someone",
                systemImageName: "arrow.up.right")
        }
    }
}

/// Phrases Settings → Siri shows, as keys of `AppShortcuts.xcstrings`; Siri's tip shows the balance's first phrase.
enum SiriExamplePhrase {
    static let invoice = "Request sats with ${applicationName}"
    static let lightningAddress = "What's my Lightning address in ${applicationName}"
    static let lastPayment = "What was my last payment in ${applicationName}"
    static let scan = "Scan a QR code with ${applicationName}"
    static let pay = "Pay someone with ${applicationName}"
    static let all = [invoice, lightningAddress, lastPayment, scan, pay]
}
