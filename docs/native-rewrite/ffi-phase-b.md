# bark-bolt-ffi 0.6.0, phase B: four convenience APIs

**Status:** research, 2026-10-23. Docs only; no app code changed.
**Base:** `feat/native-rewrite` at `e2f296f3`. bark-bolt-ffi 0.6.0 (tag `b5aed0bb`, Bark 0.8.0 `959a489f`).
**Answers:** the `TODO.md` item "evaluate `parsePaymentRequest`, `sendPayment`, `estimatePaymentFee(s)` and `bip321Uri`".

**Path prefixes:**
- `A/`: `apps/android/app/src/main/java/com/noahwallet`
- `I/`: `apps/ios/Noah`
- `F/`: bark-bolt-ffi 0.6.0 source (the iOS SPM checkout)
- `B/`: bark at `959a489f` (`~/.cargo/git/checkouts/bark-f3369c4cebbcdc63/959a489`)

## Decision

| API | Decision | Why |
| --- | --- | --- |
| `parsePaymentRequest` | **Don't adopt** | It refuses URIs that RN accepts (`amount=0`, 35 of the 108 amount vectors, one bad optional parameter, `bitcoin:tb1…` and `bitcoin:bcrt1…` on the test networks), replaces ten typed errors with one generic message, and can't accept an Ark address without a live Ark server. |
| `sendPayment` | **Don't adopt** | It makes the same calls as today for Ark, invoices, offers and Ark-funded on-chain sends, so there's nothing to gain. For LNURL and Lightning addresses it fetches a new invoice inside the pay call, which breaks the reviewed-invoice binding, the hash journal and the Noah-user Ark shortcut. |
| `estimatePaymentFee` / `estimatePaymentFees` | **Don't adopt** | The single-method call gives the same numbers from the same three estimators, so switching saves nothing. The list call fails entirely when any one option fails, while today only that rail drops out. Neither covers on-chain-wallet sends or Max. |
| `bip321Uri` | **Don't adopt** | It derives new addresses on every call (today's pair survives amount edits) and its invoice carries no description, so the note is lost. On signet and regtest the URI shape differs too. |

None is adopted, so there is no migration. Open question 1 below is the one place where part of the SDK would help: it would fix a money gap that exists today.

## How this was checked

- **No JVM run.** The Android AAR ships only Android `.so` files, and `BarkSdkLinkTest.kt` already notes that "Android JNI cannot run in a host JVM unit test".
- **The real 0.6.0 binary instead.** A throwaway SwiftPM harness (appendix) linked the macOS slice of the XCFramework iOS pins (checksum `72c95272…`). It opened one offline wallet per network, with no Ark server and a stub Esplora that answers only the genesis hash. Each input went through `parsePaymentRequest` and a copy of today's iOS `SendDestination`.
- **Android matches iOS.** A throwaway JVM test (deleted) ran Android's `SendDestination` over the same inputs: 368 of 368 rows matched.
- **Inputs (368 parse rows):**
  - `send-utils`: 31 inputs × 3 networks;
  - `send-amount-safety`: 108 vectors;
  - the 5 `receive-request` URIs;
  - 54 edge inputs × 3 networks.

  Also 33 `bip321Uri` calls. The other fixtures (`send-flow`, `onchain-send`, `lightning-invoice`) contain no destinations.
- **Limits:**
  - Without an Ark server, Ark-address checks and `bip321Uri`'s Ark address and invoice come from source.
  - `sendPayment` and the fee estimates are from source only.

## Parity: `parsePaymentRequest` vs today's `SendDestination`

"Sees" means a different screen or error; "Pays" means a different amount could be paid. "Test nets" means signet and regtest only. Inputs are shortened. `1A1z…` is `1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa`.

| # | Input (network) | Today | SDK | Changes |
| --- | --- | --- | --- | --- |
| 1 | `bitcoin:1A1z…?amount=0` (send-utils 17) | On-chain request, no amount, keypad open | Refused: "Invalid payment request: No valid payment option found" | Sees |
| 2 | `bitcoin:1A1z…?amount=1.234567895` | 123,456,790 sats (RN `Math.round`) | Whole URI refused | Sees |
| 2a | Same for `0.000000005`, `1e-8`, `1E-8`, `1e+1`, `+1`, `1.0000000000000000000000000000000`, `0000000000000001.0…`, `0.000000145` | Accepted with RN's amount | Refused: no exponents, `+`, extra zeros or more than 8 decimals | Sees (35 of 108 vectors) |
| 3 | `bitcoin:1A1z…?amount=21000001` | "Invalid amount format" | Accepted: 2,100,000,100,000,000 sats | Sees (can't be funded) |
| 4 | `bitcoin:1A1z…?lightning=garbage` (also `ark=garbage`, `lno=lno1garbage`) | On-chain rail usable; the bad parameter is dropped | Whole URI refused | Sees |
| 5 | `bitcoin:1A1z…?req-ark=ark1…` / `?req-amount=0.001` | "Unknown required parameter: req-ark" | Accepted (Ark option; amount 100,000) | Sees |
| 6 | `bitcoin:1A1z…?amount=0.001&ark=tark1…` (mainnet) | Whole URI refused: network mismatch | On-chain usable; the Ark option is flagged | Sees |
| 7 | `bitcoin:1A1z…?amount=0.001&lightning=lnbc15u…` (1,500-sat invoice) | Both rails at 100,000 sats; Lightning then fails at quote | Invoice flagged `amountMismatch(1500, 100000)`; defaults to on-chain | Sees |
| 8 | `bitcoin:tb1qw508…?amount=0.0001` (signet); `bitcoin:bcrt1q5mq…?ark=tark1…` (regtest: today's own Receive URI) | Accepted | Refused: BIP-321's address path must be mainnet (`B/bip321/src/lib.rs:479-485`) | Sees (test nets; a Noah-to-Noah QR fails) |
| 8a | `bitcoin:?bcrt=bcrt1…` (regtest), `bitcoin:?tbs=tb1…` (signet) | Accepted | Refused | Sees (test nets) |
| 9 | `bitcoin:?tb=bcrt1q5mq…` (regtest) | "Address network mismatch for tb parameter" | Accepted | Sees (regtest) |
| 10 | `  bitcoin:1A1z…?amount=0.001` (leading spaces) | Not a destination | Accepted (input trimmed) | Sees |
| 11 | `bitcoin:1A1z…?label=%ZZ` | "Invalid BIP-321 URI" | Accepted; label is `%ZZ` | Sees |
| 12 | BOLT12 offer `lno1pqps7sjq…` that fixes 1,000 sats (send-utils 8–11) | Offer with no amount; keypad open, and the typed amount is paid | `amountSats: 1000`, `requiresAmount: false` | **Pays**: today a user can pay more or less than the offer asks (bark gives the user amount precedence, `B/lib/src/lightning.rs:346-361`) |
| 13 | The same mainnet offer on signet or regtest (send-utils 8–12) | Accepted | `networkMismatch`; no usable option | Sees (test nets) |
| 14 | `lnbc10p1…` (1 msat, send-utils 3) | "Invoice amount is less than 1 satoshi." | Accepted as 1 sat (rounded up, `B/bark/src/payment_request.rs:396`) | Sees, Pays |
| 14a | `lnbc15n1…` (1.5 sat, send-utils 4); `lnbc01p…` (send-utils 1) | Fixed 1.5 (unquotable); no amount | 2 sats; `amountSats: 0` with `requiresAmount` | Sees |
| 15 | `ark1pwh9…` (send-utils 19), Ark server unreachable | Ark recipient accepted; checked at quote | No usable option: `other("You should be connected to Ark server…")`. The server check runs before the network check, so a wrong-network address gets the same error | Sees (offline or server down) |
| 16 | `alice@example.photography`, `alice+tip@example.com`, `alice@localhost`, `alice@exämple.com`, `₿alice@example.com` | Not a destination (RN regex, 2–4 letter TLD) | `lightning-address` | Sees |
| 17 | `lnurl1…` encoding `http://example.com/pay`, or `http://abc.onion/pay` | Refused: HTTPS only | Accepted | Sees (bark would fetch it over cleartext) |
| 18 | `lnurlp://example.com/.well-known/lnurlp/alice` (LUD-17) | Refused | `lnurl` | Sees |
| 19 | `lnurl1…` encoding an LNURL-auth URL (`tag=login`) | Accepted as LNURL; fails later at fetch | Refused | Sees (the error comes earlier) |
| 20 | `mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn` (base58 testnet, regtest) | Network mismatch | Accepted | Sees (regtest) |
| 21 | Every refusal above | One of ten typed, translated errors (`SendDestinationError`), e.g. "Multiple amount parameters not allowed" or "Network mismatch: expected regtest, got mainnet" | One code (`invalidPaymentRequest`), almost always "…No valid payment option found"; per-option errors only when the URI itself parses | Sees |
| 22 | `bitcoin:1A1z…?label=Luke-Jr&message=…` | Label and message dropped | Returned | None (no UI shows them) |
| 23 | `bitcoin:?lightning=X&lightning=X`; `0014751e…` (hex script) | One invoice (the last); not a destination | Two options; an `output-script` option flagged `unsupported` | None |

**Unchanged:**
- prefixes, case and whitespace;
- `+`-split offers;
- duplicate or negative amounts and unknown `req-*` parameters;
- wrong-network invoices and addresses (refused either way);
- the other 73 amount vectors;
- the default rail (Ark, then Lightning, then on-chain).

**Async parsing.** Today's parser is synchronous and pure. Eight Android and nine iOS files call it, on every keystroke too. The SDK's parser needs an open wallet session.

## Parity: `bip321Uri` vs today's `ReceiveRequest.uri()`

| # | Case | Today | SDK | Changes |
| --- | --- | --- | --- | --- |
| R1 | Two calls, no amount (mainnet) | One address pair per Receive visit, kept across amount edits (`I/Services/Wallet/WalletSession.swift:509`, `A/services/wallet/WalletSession.kt:637`) | `bitcoin:bc1p5cyx…`, then `bitcoin:bc1p4qhj…`: new on-chain and Ark addresses every call | Sees: the QR changes on every amount edit, and there are more addresses to watch |
| R2 | 500 sats with a note | Invoice carries the note (`receiveInvoice`) | Invoice has no description (`bolt11_invoice(amount, None, None)`, `B/bark/src/payment_request.rs:368`) | Sees: the payer never sees the note |
| R3 | 500 sats, signet / regtest | `bitcoin:tb1p…?amount=0.000005&lightning=…&ark=…` | `bitcoin:?amount=0.000005&tb=tb1p…`; on regtest `tb=bcrt1p…`, which today's parser (and RN's) refuses (parity row 9) | Sees (test nets) |
| R4 | 1, 500, 100,000,000 and 2.1×10¹⁵ sats (mainnet) | `bitcoin:<addr>?amount=…&lightning=…&ark=…` | Same layout and amount text | None |
| R5 | 0 sats | Refused by `ReceiveRequest.validate` | "amount cannot be zero" | None |

The Ark address and the invoice need the Ark server in both.

## `sendPayment` and `estimatePaymentFee(s)` behind today's flow

`sendPayment` only dispatches (`F/src/core/wallet.rs:1016-1050`, `B/bark/src/payment_request.rs:777-832`). It could sit only at the final call of `A/send/SdkSendWallet.kt:116` / `I/Services/Wallet/WalletSession+Send.swift:120`.

| Check | Today | Behind `sendPayment` |
| --- | --- | --- |
| Quote ↔ submit binding | `prepare` re-quotes (`fresh == quote`, `SdkSendWallet.kt:110`, `WalletSession+Send.swift:113`). LNURL and Lightning addresses pay the reviewed `resolvedInvoice`. On-chain-wallet sends broadcast the reviewed PSBT; Max offboards the reviewed VTXO ids. | Ark, invoices and offers: unchanged. LNURL and addresses: bark fetches a **new** invoice at submit, so the paid invoice is never the reviewed one. On-chain-wallet sends and Max can't be expressed: the Bitcoin method always pays from Ark. |
| Movement-id identity | Ark returns the movement id; Ark-funded on-chain sends return `OffboardResult {txid, movementId}` | The same ids: the FFI routes Bitcoin to its own `send_onchain` to keep the id (`F/src/core/wallet.rs:1024-1032`). Nothing gained. |
| Send locks | Journal marker, then baseline; `pendingLightningSends` / `lightningSendState` by hash before paying; prepared-send claim and consume | Kept for invoices and offers. LNURL and addresses: no hash before submit, so no "already in progress" check and no checkpoint before paying, and the `async-payments.md` watch can't bind its attempt. |
| Reused hashes | A retry is bound by `attemptID`, hash and the checkpoint that follows it (`async-payments.md`, "Reused hashes") | Invoices: unchanged. LNURL and addresses: the hash first appears in the returned status, so a crash in between leaves an unbound movement. |
| Recurring fee cap | `RecurringFeePolicy` checks the quote fee against the ArkInfo schedule before submitting (`recurring-fee-cap.json`) | Unchanged. Neither path has a max-fee parameter, so the cap stays a pre-submit check. |
| Offline and timeouts | Noah's LNURL client: HTTPS only, no redirects, 20 s timeout, 64 KiB cap, exact-amount, network and expiry checks (`A/send/SendRecipientService.kt:23-58`) | bark checks the amount and only warns on a description-hash mismatch. Its lnurl-rs client has no timeout and follows redirects (`B/bark/src/lightning/mod.rs:61-81`), and the fetch runs inside the wallet call. |
| Noah-user Ark shortcut | The LNURL request sends `ark=<server key>` and can pay a Noah user over Ark (`SendRecipientService.kt:24,30`) | Lost |
| Amount for fixed invoices | Passes `nil` for invoices that carry an amount (`SdkSendWallet.kt:133`) | Same rule needed: bark accepts up to twice an invoice's amount when one is passed (`F/src/ffi/wallet.rs:629-635`) |

**Fees:**
- **`estimatePaymentFee`:**
  - **Same numbers.** It calls the three estimators today's quotes call (`B/bark/src/payment_request.rs:714-730`), so fees and recurring caps don't change. Adopting it would remove only a three-way `when`/`switch`.
  - **Gaps.** It skips the address network check, and it can't estimate on-chain-wallet sends or Max.
- **`estimatePaymentFees`:**
  - **All or nothing.** One failing option fails the whole list (`B/bark/src/payment_request.rs:747`); today's Method cards drop only that rail (`I/Services/Wallet/WalletSession+SendReceive.swift:20`).
  - **Coupling.** It needs a `PaymentRequest` from `parsePaymentRequest`.

## Smallest change

None: no API is adopted, so no app code changes and all tests stay as they are. Should you pick option 1b below:

| | Android | iOS |
| --- | --- | --- |
| Recipient step | `send/SendModel.kt`: when `SendDestination` returns `offer`, call `wallet.parsePaymentRequest(offer)` once, in the async step that already resolves LNURLs. Refuse when `defaultOption == null`; fill and lock the amount when `amountSats != null`. | `Features/Send/SendModel.swift`, the same |
| Quote guard | `send/SdkSendWallet.kt` `quoteResolved` (and the `SendWallet` interface for fakes): for a fixed offer, require `payment.amount == amountSats` | `Services/Wallet/WalletSession+Send.swift` `quote(_:handles:)` |
| Deleted | Nothing | Nothing |

**Tests that must keep passing:**
- `SendFixtureTest` / `SendFixtureTests` and `SendAmountTest` / `SendAmountTests`: the parser is unchanged;
- Android `SendPasteTest`, `SendRecipientTest`, `SendLightningTest`, `SendPreparedTest`, `SendSafetyTest`, `RecurringFixtureTest`;
- iOS `SendRecipientSafetyTests`, `SendLightningSafetyTests`, `SendPreparedTests`, `SendSafetyTests`, `RecurringFeePolicyTests`.

**New tests:** a fixed-amount offer locks the keypad and rejects another amount at quote; a mainnet offer is refused on regtest.

**Risks:**
- **Fiat-priced offers.** Upstream panics on them, and the FFI turns the panic into `invalidPaymentRequest` (`F/src/core/wallet.rs:948-954`). Today they pass the parser and reach bark with the typed amount, which bark uses without reading the offer amount (source only, not run); under 1b they would be refused, unless that error is treated as "no fixed amount".
- **Open wallet needed.** The check needs an open wallet but no Ark server.

## Open questions

1. **BOLT12 offers that fix an amount** (parity row 12). Today, like RN, Noah pays whatever amount is typed.
   - (a) Keep RN behaviour.
   - (b) Use the offer's own amount and chain through `parsePaymentRequest`, for offers only (the change above).
   - (c) The same with a small BOLT12 decoder in `PaymentEncoding`: no wallet call, more code.
2. **Inputs the SDK accepts and RN refuses:**
   - LUD-17 `lnurlp://`;
   - `req-ark` / `req-amount`;
   - Lightning addresses outside RN's regex: long TLDs, `+` tags;
   - early refusal of LNURL-auth links.

   Choices:
   - (a) Keep RN's rules.
   - (b) Port the chosen ones into `SendDestination` on both platforms, with a native-only fixture file. The RN-generated fixtures can't be hand-edited.
3. **Upstream asks.** Each would remove one blocker above.
   - (i) BIP-321 path addresses on test networks, typed parse errors, and Ark checks that work offline;
   - (ii) LNURL and address sends that resolve, then pay, returning the invoice before paying;
   - (iii) `bip321Uri` taking existing addresses and an invoice description;
   - (iv) per-option results from `estimatePaymentFees`.

   Choices:
   - (a) Add them to `ffi-handoff-prompt.md` and re-run this review when they land.
   - (b) Close phase B as "not adopted".
4. **The `TODO.md` item.**
   - (a) Tick it as evaluated, linking this doc.
   - (b) Leave it open until question 3 is settled.

## Appendix: reproduce

Run from the repo root; everything is written under `/tmp`. `D` is Noah's DerivedData `SourcePackages` directory. Put the scripts below in `$S` and the Swift files in `$H/Sources/Harness`.

```sh
H=/tmp/noah-ffi-phase-b; S=/tmp/noah-ffi-phase-b-scripts; mkdir -p $H/Frameworks $H/Sources/{BarkBoltFFIFFI,BarkBoltFFI,Harness} $S
C=$D/checkouts/bark-bolt-ffi
cp -R $C/sdk/swift/FFI/. $H/Sources/BarkBoltFFIFFI/; cp -R $C/sdk/swift/Sources/BoltFFI/. $H/Sources/BarkBoltFFI/
ln -s $D/artifacts/bark-bolt-ffi/BarkBoltFFIFFIBinary/BarkBoltFFI.xcframework $H/Frameworks/
cp apps/ios/Noah/Features/Send/{SendDestination,PaymentEncoding}.swift apps/ios/Noah/Features/Receive/ReceiveRequest.swift $H/Sources/Harness/
python3 -I $S/esplora_stub.py &                                    # genesis hash only
python3 -I $S/make_inputs.py apps/fixtures $H/inputs.json          # 198 inputs
(cd $H && swift build && ./.build/debug/Harness inputs.json results.json /tmp/noah-ffi-phase-b-data)
```

<details><summary><code>Package.swift</code> and <code>Stubs.swift</code></summary>

```swift
// swift-tools-version:5.9
import PackageDescription
let package = Package(
    name: "Harness",
    platforms: [.macOS(.v13)],
    targets: [
        .binaryTarget(name: "BarkBoltFFIFFIBinary", path: "Frameworks/BarkBoltFFI.xcframework"),
        .target(name: "BarkBoltFFIFFI", dependencies: ["BarkBoltFFIFFIBinary"], path: "Sources/BarkBoltFFIFFI", publicHeadersPath: "include"),
        .target(name: "BarkBoltFFI", dependencies: ["BarkBoltFFIFFI"], path: "Sources/BarkBoltFFI"),
        .executableTarget(name: "Harness", dependencies: ["BarkBoltFFI"], path: "Sources/Harness",
                          swiftSettings: [.unsafeFlags(["-swift-version", "5"])]),
    ]
)
```

```swift
import Foundation
// Stand-ins for the app types SendDestination.swift references (messages only).
enum AppVariant: String { case mainnet; static var current: AppVariant { .mainnet } }
enum LegacyLocale { static func resolved() -> String { "en" } }
enum L10n {
    static func text(_ source: String, _ args: String..., locale: String) -> String {
        var out = source
        for arg in args { if let r = out.range(of: #"\{\w+\}"#, options: .regularExpression) { out.replaceSubrange(r, with: arg) } }
        return out
    }
}
```
</details>

<details><summary><code>main.swift</code></summary>

```swift
import BarkBoltFFI
import Foundation

struct Input: Decodable { let id: String; let input: String; let networks: [String] }
let args = CommandLine.arguments
let inputs = try JSONDecoder().decode([Input].self, from: Data(contentsOf: URL(fileURLWithPath: args[1])))
let dataRoot = args[3]
// BIP-39 test vector; offline throwaway wallets only.
let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
let networks: [String: Network] = ["bitcoin": .bitcoin, "signet": .signet, "regtest": .regtest]
let todayName = ["bitcoin": "mainnet", "signet": "signet", "regtest": "regtest"]

func open(_ name: String) async throws -> Wallet {
    let dir = "\(dataRoot)/\(name)"
    try? FileManager.default.removeItem(atPath: dir)
    try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
    // No Ark server on port 9; the stub Esplora answers only the genesis hash.
    let port = ["bitcoin": 19001, "signet": 19002, "regtest": 19003][name]!
    let config = Config(serverAddress: "http://127.0.0.1:9", esploraAddress: "http://127.0.0.1:\(port)")
    let onchain = try await OnchainWallet.default(network: networks[name]!, mnemonic: mnemonic, config: config, datadir: dir)
    return try await Wallet.openWithOnchain(network: networks[name]!, mnemonicOrSeed: mnemonic, config: config,
        args: WalletOpenArgs(runDaemon: false, datadir: dir, createIfNotExists: true, createWithoutServer: true, skipRecovery: true),
        onchain: onchain)
}
func short(_ s: String) -> String { s.count > 24 ? String(s.prefix(14)) + "…" + String(s.suffix(6)) : s }
func describe(_ e: PaymentMethodParsingError) -> String {
    switch e {
    case .networkMismatch: "networkMismatch"
    case .invalidArkAddress(let a): "invalidArkAddress(\(a))"
    case .amountMismatch(let x, let g): "amountMismatch(expected:\(x),got:\(g))"
    case .invalidAmount: "invalidAmount"
    case .unsupported: "unsupported"
    }
}
func sdk(_ wallet: Wallet, _ input: String) async -> [String: Any] {
    do {
        let r = try await wallet.parsePaymentRequest(input: input)
        return ["ok": true, "amountSats": r.amountSats.map { NSNumber(value: $0) } ?? NSNull(),
                "label": r.label ?? NSNull(), "message": r.message.map(short) ?? NSNull(),
                "options": r.options.map { o in ["type": o.method.methodType, "value": short(o.method.value),
                    "errors": o.errors.map(describe), "requiresAmount": o.requiresAmount, "supportsComment": o.supportsComment] },
                "default": r.defaultOption?.method.methodType ?? NSNull()]
    } catch let e as BarkError {
        return ["ok": false, "code": "\(e.code)", "message": e.message]
    } catch { return ["ok": false, "code": "unknown", "message": "\(error)"] }
}
func today(_ input: String, _ network: String) -> [String: Any] {
    let d = SendDestination.parse(input, network: network)
    return ["ok": d.destinationType != nil, "type": d.destinationType ?? NSNull(),
            "amount": d.amount.map { "\($0)" } ?? NSNull(), "editable": d.isAmountEditable,
            "error": d.error.map { $0.message(locale: "en") } ?? NSNull(),
            "bip321": d.bip321.map { $0.keys.sorted() } ?? NSNull()]
}

var results: [[String: Any]] = []
var wallets: [String: Wallet] = [:]
for name in ["bitcoin", "signet", "regtest"] {
    do { wallets[name] = try await open(name) } catch { print("open \(name) failed: \(error)") }
}
for item in inputs {
    for name in item.networks {
        guard let wallet = wallets[name] else { continue }
        results.append(["id": item.id, "network": name, "input": short(item.input),
                        "sdk": await sdk(wallet, item.input), "today": today(item.input, todayName[name]!)])
    }
}
// bip321Uri: address placement, amount format, address churn.
var receive: [[String: Any]] = []
for name in ["bitcoin", "signet", "regtest"] {
    guard let wallet = wallets[name] else { continue }
    let variants: [(String, Bip321UriOptions)] = [
        ("onchain only, no amount", Bip321UriOptions(ark: false, onchain: true, lightning: false)),
        ("onchain only, no amount (2nd call)", Bip321UriOptions(ark: false, onchain: true, lightning: false)),
        ("onchain only, 500 sats", Bip321UriOptions(amountSats: 500, ark: false, onchain: true, lightning: false)),
        ("onchain only, 1 sat", Bip321UriOptions(amountSats: 1, ark: false, onchain: true, lightning: false)),
        ("onchain only, 1 BTC", Bip321UriOptions(amountSats: 100_000_000, ark: false, onchain: true, lightning: false)),
        ("onchain only, 21M BTC", Bip321UriOptions(amountSats: 2_100_000_000_000_000, ark: false, onchain: true, lightning: false)),
        ("onchain only, 0 sats", Bip321UriOptions(amountSats: 0, ark: false, onchain: true, lightning: false)),
        ("onchain only, label+message", Bip321UriOptions(label: "Coffee & cake", message: "Thanks! ☕️", ark: false, onchain: true, lightning: false)),
        ("defaults (ark + onchain + lightning)", Bip321UriOptions()),
        ("ark only", Bip321UriOptions(ark: true, onchain: false, lightning: false)),
        ("500 sats with lightning", Bip321UriOptions(amountSats: 500, ark: false, onchain: false, lightning: true)),
    ]
    for (label, options) in variants {
        do {
            let r = try await wallet.bip321Uri(options: options)
            var todayURI: Any = NSNull()
            if let address = r.onchainAddress {
                // Today's builder needs an invoice with an amount and always appends ark=; placeholders mark them.
                var req = ReceiveRequest(arkAddress: "ARK", onchainAddress: address)
                if let amount = options.amountSats { req.amountSat = Int64(clamping: amount); req.lightningInvoice = "INVOICE" }
                todayURI = (try? req.uri()) ?? "error"
            }
            receive.append(["network": name, "case": label, "uri": r.uri, "onchain": r.onchainAddress ?? NSNull(), "today": todayURI])
        } catch let e as BarkError {
            receive.append(["network": name, "case": label, "error": "\(e.code): \(e.message)"])
        } catch { receive.append(["network": name, "case": label, "error": "\(error)"]) }
    }
}
let out = try JSONSerialization.data(withJSONObject: ["parse": results, "bip321": receive], options: [.prettyPrinted, .sortedKeys])
try out.write(to: URL(fileURLWithPath: args[2]))
print("wrote \(results.count) parse rows, \(receive.count) bip321 rows")
```
</details>

<details><summary><code>esplora_stub.py</code></summary>

```python
# Answers only /block-height/0 with the network's genesis hash; everything else is 503.
import http.server, sys, threading
GENESIS = {19001: "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
           19002: "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6",
           19003: "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"}
def handler(port):
    class H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body, code = (GENESIS[port], 200) if self.path == "/block-height/0" else ("unavailable", 503)
            self.send_response(code); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body.encode())
        def log_message(self, *a): pass
    return H
servers = [http.server.ThreadingHTTPServer(("127.0.0.1", p), handler(p)) for p in GENESIS]
for s in servers[1:]: threading.Thread(target=s.serve_forever, daemon=True).start()
servers[0].serve_forever()
```
</details>

<details><summary><code>make_inputs.py</code> (fixture inputs plus the edge cases in the tables)</summary>

```python
import json, sys, urllib.parse
fx = sys.argv[1]; out = sys.argv[2]
CH = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
def polymod(v):
    g=[0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3]; c=1
    for x in v:
        b=c>>25; c=((c&0x1ffffff)<<5)^x
        for i in range(5): c ^= g[i] if (b>>i)&1 else 0
    return c
def bech32(hrp, data8):
    acc=bits=0; w=[]
    for b in data8:
        acc=(acc<<8)|b; bits+=8
        while bits>=5: bits-=5; w.append((acc>>bits)&31)
    if bits: w.append((acc<<(5-bits))&31)
    v=[ord(x)>>5 for x in hrp]+[0]+[ord(x)&31 for x in hrp]
    pm=polymod(v+w+[0]*6)^1
    return hrp+"1"+"".join(CH[d] for d in w+[(pm>>5*(5-i))&31 for i in range(6)])
ALL = ["bitcoin","signet","regtest"]
inputs=[]
def add(i, s, nets=ALL): inputs.append({"id": i, "input": s, "networks": nets})
for c in json.load(open(f"{fx}/send-utils.json"))["cases"]: add("send-utils/"+c["name"], c["input"]["destination"])
for c in json.load(open(f"{fx}/send-amount-safety.json"))["cases"]:
    enc = urllib.parse.quote(c["input"]["amount"], safe="")
    add("amount/"+c["name"], f"bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount={enc}", ["bitcoin"])
for c in json.load(open(f"{fx}/receive-request.json"))["cases"]:
    if "uri" in c["expected"]: add("receive-roundtrip/"+c["name"], c["expected"]["uri"], ["bitcoin"])
# Signet invoice from bark's own tests (B/bark/src/payment_request.rs:930).
INV_SIG = "lntbs100u1p5j0x82sp5d0rwfh7tgrrlwsegy9rx3tzpt36cqwjqza5x4wvcjxjzscfaf6jspp5d8q7354dg3p8h0kywhqq5dq984r8f5en98hf9ln85ug0w8fx6hhsdqqcqzpc9qyysgqyk54v7tpzprxll7e0jyvtxcpgwttzk84wqsfjsqvcdtq47zt2wssxsmtjhz8dka62mdnf9jafhu3l4cpyfnsx449v4wstrwzzql2w5qqs8uh7p"
INV_MAIN = "lnbc15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsq4gj5hs"
ARK = "ark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqjl5wpy"
TARK = "tark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqkm2rpd"
BCRT = "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"
TB = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
BC = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
B58 = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
B58T = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"
extra = {
 "signet invoice": INV_SIG, "signet invoice LIGHTNING: upper": "LIGHTNING:"+INV_SIG.upper(),
 "bip321 tb path + amount": f"bitcoin:{TB}?amount=0.0001",
 "bip321 bcrt path + ark (today's regtest receive shape)": f"bitcoin:{BCRT}?ark={TARK}",
 "bip321 bcrt path + amount + lightning(signet) + ark": f"bitcoin:{BCRT}?amount=0.0001&lightning={INV_SIG}&ark={TARK}",
 "bip321 tb= regtest addr": f"bitcoin:?tb={BCRT}", "bip321 bcrt= key": f"bitcoin:?bcrt={BCRT}", "bip321 tbs= key": f"bitcoin:?tbs={TB}",
 "bip321 bc= tb addr": f"bitcoin:?bc={TB}",
 "tark address": TARK, "ark address": ARK, "bcrt address": BCRT, "tb address": TB, "bc address": BC, "base58 testnet address": B58T,
 "bip321 amount matches invoice": f"bitcoin:?amount=0.0001&lightning={INV_SIG}",
 "bip321 amount differs from invoice": f"bitcoin:?amount=0.0002&lightning={INV_SIG}",
 "bip321 mainnet amount differs from invoice": f"bitcoin:{B58}?amount=0.001&lightning={INV_MAIN}",
 "bip321 req-ark": f"bitcoin:{B58}?req-ark={ARK}", "bip321 req-amount": f"bitcoin:{B58}?req-amount=0.001",
 "bip321 bad lightning param": f"bitcoin:{B58}?lightning=garbage", "bip321 bad ark param": f"bitcoin:{B58}?ark=garbage",
 "bip321 bad lno param": f"bitcoin:{B58}?lno=lno1garbage",
 "bip321 mainnet addr + tark": f"bitcoin:{B58}?amount=0.001&ark={TARK}",
 "bip321 label message": f"bitcoin:{B58}?label=Luke-Jr&message=Donation%20for%20project%20xyz",
 "bip321 two invoices": f"bitcoin:?lightning={INV_SIG}&lightning={INV_SIG}",
 "bip321 bad percent": f"bitcoin:{B58}?label=%ZZ", "bip321 pop": f"bitcoin:{B58}?pop=callback%3a",
 "bip321 unknown param": f"bitcoin:{B58}?foo=bar", "bip321 sp optional": f"bitcoin:{B58}?sp=sp1qq",
 "bip321 req-sp": f"bitcoin:{B58}?req-sp=sp1qq",
 "bip321 uppercase QR": f"BITCOIN:{BC.upper()}?AMOUNT=0.001",
 "bip321 leading space": f"  bitcoin:{B58}?amount=0.001",
 "bip321 no params": f"bitcoin:{B58}", "bip321 empty": "bitcoin:", "bip321 query only": "bitcoin:?",
 "bip321 amount 9 decimals": f"bitcoin:{B58}?amount=0.000000015",
 "bip321 amount exponent": f"bitcoin:{B58}?amount=1e-5",
 "lightning: bitcoin uri": f"lightning:bitcoin:{B58}",
 "lightning address long TLD": "alice@example.photography", "lightning address plus": "alice+tip@example.com",
 "lightning address no TLD": "alice@localhost", "lightning address IDN": "alice@exämple.com",
 "lightning address unicode user": "₿alice@example.com",
 "lnurl LUD-01 upper": "LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS",
 "lnurl http": bech32("lnurl", b"http://example.com/pay"), "lnurl onion http": bech32("lnurl", b"http://abc.onion/pay"),
 "lnurl auth": bech32("lnurl", b"https://example.com/login?tag=login&k1=00&action=login"),
 "lightning:lnurl": "lightning:"+bech32("lnurl", b"https://example.com/pay"),
 "lnurlp scheme": "lnurlp://example.com/.well-known/lnurlp/alice", "lnurlp onion": "lnurlp://abc.onion/pay",
 "hex output script": "0014751e76e8199196d454941c45d1b3a323f1433bd6",
 "offer upper lightning:": "LIGHTNING:LNO1PQPS7SJQPGTYZM3QV4UXZMTSD3JJQER9WD3HY6TSW35K7MSJZFPY7NZ5YQCNYGRFDEJ82UM5WF5K2UCKYYPWA3EYT44H6TXTXQUQH7LZ5DJGE4AFGFJN7K4RGRKUAG0JSD5XVXG",
 "https url": "https://example.com/pay",
}
for k, v in extra.items(): add("extra/"+k, v)
json.dump(inputs, open(out, "w"), indent=1)
print(len(inputs), "inputs")
```
</details>

<details><summary>Android baseline: throwaway JVM test (deleted after the run)</summary>

Placed at `apps/android/app/src/test/java/com/noahwallet/send/` and run with `./gradlew --offline :app:testRegtestDebugUnitTest --tests com.noahwallet.send.FfiPhaseBThrowawayTest`. The output matched the iOS copy on all 368 rows.

```kotlin
package com.noahwallet.send

import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

class FfiPhaseBThrowawayTest {
    @Test fun dump() {
        val inputs = Json.parseToJsonElement(File("/tmp/noah-ffi-phase-b/inputs.json").readText()).jsonArray
        val names = mapOf("bitcoin" to "mainnet", "signet" to "signet", "regtest" to "regtest")
        val out = buildJsonArray {
            for (item in inputs) {
                val o = item.jsonObject; val input = o.getValue("input").jsonPrimitive.content
                for (n in o.getValue("networks").jsonArray) {
                    val net = n.jsonPrimitive.content; val d = SendDestination.parse(input, names.getValue(net))
                    add(buildJsonObject {
                        put("id", o.getValue("id")); put("network", net); put("ok", d.destinationType != null)
                        put("type", d.destinationType); put("amount", d.amount?.stripTrailingZeros()?.toPlainString())
                        put("editable", d.isAmountEditable); put("error", d.error?.message)
                        put("bip321", d.bip321?.keys?.sorted()?.joinToString(","))
                    })
                }
            }
        }
        File("/tmp/noah-ffi-phase-b/android-today.json").writeText(out.toString())
    }
}
```
</details>
