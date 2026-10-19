# Native Send verification

Run shared fixture generation from the repository root:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-send.ts
```

The generator executes the RN implementations. `send-flow.json` covers every
combination of stage confirmations, one/multiple rails, unavailable rails and
zero/one/two funding sources. `send-utils.json` includes the RN test inputs plus
BOLT11 amountless, zero, sub-satoshi, fractional-satoshi and network cases. Altered
invoice headers are decoder-only fixtures; their signatures are not valid and
these fixtures must never be submitted as payments. Existing `fiat-currency.json`
is consumed by `ReceiveFixtureTests` for the shared fiat implementation.

Use a dedicated simulator and DerivedData (never another developer's device):

```sh
cd apps/ios
xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination "platform=iOS Simulator,id=$SEND_SIMULATOR" \
  -derivedDataPath build/DerivedData test \
  -skip-testing:NoahTests/UpgradeCompatibilityTests
```

160 tests passed on iOS 27 / Swift 6 on 2026-10-19. Upgrade tests are deliberately
excluded; existing opt-in server integration suites retain their own gates.
Pre-commit client lint, compiler lint, 148 RN unit tests, typecheck and Rust
formatting also pass.

## Live regtest acceptance (2026-10-19)

Created a wallet through onboarding on the dedicated `Noah Send Agent` simulator.
Used its Receive tab to copy fresh receiving addresses. Existing Docker services
were not restarted or recreated. Loaded the existing Bitcoin Core `dev-wallet`
(it was present but unloaded). All CLI payments used the repository's `just`
wrappers, with Bark's `--no-deps` behavior.

- Funded 100,000 sats over Ark. Receive displayed success and Send showed 100,000
  spendable sats.
- Sent 1,000 sats over Ark, amount-first, with a double-tap on confirm. Exactly one
  successful outgoing movement; zero fee.
- Paid a CLN BOLT11 invoice for 2,000 sats, recipient-first. CLN reported `paid`;
  exactly one successful Lightning movement, 83-sat fee.
- Sent 5,000 sats onchain from Ark. Successful offboard movement, 2,560-sat fee.
  Mined one block with `just generate 1`; Bitcoin Core confirmed receipt of
  0.00005 BTC (txid prefix `d6d1420927d2`).
- Funded the app's onchain wallet with 30,000 sats and one confirmation. MAX showed
  both funding buckets separately (89,357 Ark / 30,000 onchain). Selected onchain;
  review estimated 208 sats in fees. The actual drain sent 29,900 sats with a
  100-sat fee, displayed the actual amount, and left no onchain funds. Mined one
  block; Bitcoin Core confirmed 0.000299 BTC (txid prefix `b14261cfa930`).
- Submitted malformed checksum-less offer `lno1q` for 1,000 sats, forcing a native
  throw after confirmation. No new movement or balance change. Send displayed
  “Payment may have been sent”, disabled Back/confirm, and only offered a status
  check. Repeated checks and a full app relaunch kept that state; no resubmission.
- Final Ark movement net: 89,357 sats. Three outgoing Ark-backed movements plus
  the separate BDK onchain drain; no duplicate payment.
- The custom amount keypad and system recipient keyboard were exercised in
  separate stages. Keyboard Done dismisses input before navigation. Review and
  success controls were checked through the accessibility tree.

The dedicated simulator and `apps/ios/build` are removed after verification.
No screenshots, wallet data or runtime payment destinations are committed.

## Submission safety and limits

`SendPaymentController.shared` locks synchronously on the first confirm tap. It
revalidates the quote/balance and snapshots history, then atomically records the
attempt before submission. A sheet's cancellation cannot cancel its owned task;
all SDK operations use counted `WalletSession.withOpenWallet` calls. A failed
metadata write never converts a successful payment into a retryable failure.

Any submission throw or nonterminal Lightning result triggers sync and history
reconciliation. Only a matching **new outgoing** successful/failed/canceled
movement establishes a terminal result. Missing history is not proof of failure.
Unmatched outcomes remain locked, including after relaunch; there is deliberately
no “retry anyway” or clear-marker button. The unit tests cover timeout, duplicate
taps, caller cancellation, restart, corrupt/unwritable markers, and failure to
save a successful receipt.

Differences from RN / SDK limits:

- Raw LNURL-pay links are supported in addition to Lightning addresses. Whole
  satoshis are required at submission, amounts above Bitcoin's supply are
  rejected, and unsupported required URI parameters (including proof-of-payment
  requests) fail closed. Parser-library error wording differs.
- Native errors do not expose a safe “definitely not sent” classification or a
  durable client payment-attempt ID. An unmatched error (including an onchain
  broadcast without a returned txid) can therefore leave Send blocked pending
  wallet evidence. This is intentional; support/recovery UX is future work.
- `feeRates()` is sat/kwu. The conversion to sat/vB is tested, including fractional
  rates. FFI send/drain accept integer sat/vB, so fractional rates round **up**.
  Exact onchain transaction construction estimates are not exposed; the screen
  labels RN's 208-vbyte estimate as approximate and MAX reports the actual result
  when BDK provides it.
- Live external Lightning-address/LNURL and BOLT12 provider acceptance, camera
  hardware scanning, and network loss during a real submitted payment were not
  exercised. LNURL comments/routing and repeat metadata are implemented; metadata
  shape has a contract test. Timeout/cancellation behavior is fault-injected in
  unit tests, and a real FFI throw was tested above.
