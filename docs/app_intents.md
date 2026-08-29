# iOS App Intents

## Architecture

Noah implements App Intents directly in the existing iOS application targets. The
[`@bacons/apple-targets`](https://github.com/EvanBacon/expo-apple-targets) config plugin is not
required: it automates creation and regeneration of native targets for Expo projects, while Noah
already commits and maintains its Xcode targets.

The shared Swift implementation is in
`client/ios/SharedAppIntents/NoahAppIntents.swift` and is compiled into the mainnet, signet, and
regtest app targets. `AppDelegate` refreshes the registered App Shortcut parameters at launch.

## Available actions

### Get Noah Balance

- Runs inside Noah's application process without bringing its UI to the foreground.
- Loads the Ark wallet natively when the process is cold, using the mnemonic already stored in the
  iOS Keychain and the same wallet data directory and network configuration as the React Native
  wallet.
- Runs Ark `sync` followed by `onchain_sync`, then reads `offchain_balance` and `onchain_balance`
  directly from `react-native-nitro-ark`'s C++ interface.
- Returns a freshly synchronized total rather than the widget's App Group cache. The total uses the
  same fields as `client/src/lib/balanceUtils.ts`; a claimable Lightning receive is not counted
  until it becomes part of Noah's normal balance calculation.
- Requires authentication so the balance is not disclosed while the invoking device is locked.

The native implementation is split between:

- `NoahArkBridge`, an Objective-C++ adapter around the generated `ark_cxx.h` API.
- `NoahNativeWalletService`, a Swift actor that serializes intent-side wallet operations and exposes
  `loadWallet`, `sync`, `onchainSync`, `offchainBalance`, `onchainBalance`, and `bolt11Invoice`.

The mnemonic is read only for a cold wallet load. It remains in Keychain under the existing
`com.noah.mnemonic.<variant>` service and is never copied to App Group storage or logged.

### Create Ark Address

- Takes no parameters.
- Loads the native Ark wallet if needed and calls `new_address`, matching Noah's existing React
  Native receive flow.
- Returns the new Ark address to Shortcuts and copies it to the system clipboard.
- Requires authentication and runs in Noah's application process without foregrounding its UI.

### Create Lightning Invoice

- Accepts an amount in sats and an optional description.
- Loads the native Ark wallet if needed and calls `bolt11_invoice` with the amount in satoshis,
  matching Noah's existing React Native integration and the current Bark runtime contract.
- Returns the BOLT11 payment request as the Shortcuts action's output. Siri gives a short
  confirmation instead of reading the full invoice.
- On iOS 26 and later, displays an interactive result with the amount, invoice, and a `Copy
  Invoice` button. Invoice creation stays in the background; tapping the copy button brings Noah
  to the foreground before writing and verifying the system clipboard.
- On earlier iOS versions, the action returns the invoice without the interactive copy button.
- Keeps the returned payment secret inside the native result; the App Intent never returns or logs
  it.
- Requires authentication.

## Testing

1. Build and launch the desired iOS variant at least once.
2. Create or restore a wallet, then leave Noah in the background or terminate it to exercise the
   native cold-load path.
3. In Shortcuts, create a shortcut and select Noah from the Apps list.
4. Change the wallet balance outside Noah, run `Get Noah Balance`, and confirm the result reflects
   the live wallet after synchronization rather than the previous widget value.
5. Run `Create Ark Address` and confirm that its returned and copied value is a valid address for
   the installed Noah network variant.
6. Run `Create Lightning Invoice`, inspect its output in Shortcuts, and decode it to confirm its
   amount and optional description. On iOS 26 or later, tap `Copy Invoice`, confirm Noah opens,
   and paste the invoice into another app.
7. Test the supplied Siri phrases, such as “What's my balance in Noah” and “Create an Ark address
   with Noah.” The system may use the variant's installed display name for signet and regtest.

Apple's App Intents testing guidance recommends first verifying actions in the Shortcuts app, then
testing their App Shortcut phrases with Siri. See
[Creating your first app intent](https://developer.apple.com/documentation/appintents/creating-your-first-app-intent).

### iOS 26.5 simulator limitation

Auto-generated App Shortcut tiles currently fail on iOS 26.5 through iOS 27 simulator runtimes
with `Couldn't find AppShortcutsProvider`, including in Apple's own App Intents sample. This is a
simulator regression rather than a Noah registration error.

To test the underlying intent on an affected simulator, create a normal shortcut with the `+`
button, search for `Get Noah Balance`, `Create Ark Address`, or `Create Lightning Invoice`, and add
the action manually.
Disable `Show When Run` if the simulator's result presentation remains stuck. Test the automatic
tiles and Siri phrases on a physical device or an older simulator runtime. See the
[Apple Developer Forums report](https://developer.apple.com/forums/tags/simulator).

## When a separate target would help

A dedicated App Intents extension is useful only if a future action must run independently of the
main app. Such an action needs native, extension-safe business logic; it cannot depend on Noah's
React Native runtime. `@bacons/apple-targets` could automate that target, but Noah can also create
and maintain it in Xcode in the same way as the existing widget extensions.
