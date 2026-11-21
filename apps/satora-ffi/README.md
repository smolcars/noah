# satora-ffi

Swift and Kotlin bindings, made with [BoltFFI](https://github.com/boltffi/boltffi) 0.31.1,
for paying a Lightning invoice and receiving USDC on Arbitrum through Satora's swap
service. Wraps `satora-rust-sdk` and keeps to the flow the `satora-spike` CLI proved on
mainnet. Mainnet only: invoices are real.

It is its own Cargo workspace with its own `Cargo.lock`, so the SDK's dependency tree
stays out of the server's, and it has no dependency on bark-bolt-ffi.

## API

`SatoraClient`:

- `open(dataDir, mnemonic?)`: opens `dataDir/wallet.sqlite` (the SDK's mnemonic, in
  plaintext) and `dataDir/swaps.sqlite` (swap records). Without a mnemonic, the stored
  one is used or a new one is generated and stored. A supplied mnemonic is stored when
  none is, and refused when a different one is.
- `quote(sats)`: read-only price.
- `createSwap(sats, destination)`: returns the swap with its `bolt11Invoice` and `id`.
- `swap(id)` (stored, no network), `refreshSwap(id)`, `listSwaps()`.
- `claim(id)`: claims through a sponsored EIP-7702 UserOperation once the status is
  `serverfunded`, or resumes one already sent. Returns the transaction hash, or nothing
  when the claim is submitted but not yet mined; call again later.

Errors are `SatoraError` with a `SatoraErrorCode`. The mnemonic and keys are never
returned or logged.

## Build

```sh
just satora-ffi-test             # cargo test; network tests are #[ignore]d
just satora-ffi-build-ios        # apple/: SatoraFFI.xcframework, Package.swift, Swift sources
just satora-ffi-build-android    # android/: jniLibs and Kotlin
just satora-ffi-check-link path/to/BarkBoltFFI.xcframework
```

`scripts/build.sh` downloads the pinned, checksummed BoltFFI CLI into `.tools/` on first
use, builds into the git-ignored `dist/`, then copies the results into `apple/` and
`android/`, which are committed and which the apps build from (`apps/ios/project.yml`
references `apple/` as a local Swift package; `apps/android/app/build.gradle.kts` adds
`android/` to its sources). Slices: iOS arm64 device and arm64 simulator; Android
arm64-v8a and x86_64, stripped. Android needs `ANDROID_HOME` (or `ANDROID_NDK_HOME`) with
NDK 28.2.13676358.

The iOS build runs `scripts/prelink-ios.sh` on each slice. Both this library and bark's
bundle SQLite (different versions), ring and secp256k1; linked plainly, one copy would
silently replace the other. The script pre-links each slice into one object whose only
global symbols are the BoltFFI exports. BoltFFI's runtime helpers (`boltffi_free_buf`
and friends) have the same names in every BoltFFI library, so they are exported as
`satora_boltffi_*` and the generated header and Swift are rewritten to match. The object
then becomes `satora_ffi.framework`, a dynamic framework: arm64 compact unwind encodes at
most three personality routines and the app already uses three (bark's Rust, C++,
Objective-C), so a static library carrying a second Rust personality fails the app's link.
`check-bark-link.sh` links the device slice with bark's library in both orders and with
`-force_load` on bark, and fails on any duplicate symbol or unless bark's and the SDK's
SQLite builds are both present. It does not reproduce the personality limit; the app's
own build does.

## Bumping the SDK

1. Change `rev` for `lendaswap_sdk` in `Cargo.toml`.
2. `cargo update -p satora-rust-sdk`, then `cargo test` and
   `cargo test -- --ignored quote_mainnet` (a read-only mainnet quote).
3. Rebuild both platforms and rerun the link check.
