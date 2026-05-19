# Unilateral Exits Plan

## Goal

Add an emergency-only wallet flow for unilateral Ark exits. The flow must let a user start exits, track each VTXO through the exit state machine, progress pending exits, and claim claimable exits to an on-chain Bitcoin address.

This is not a normal offboarding path. The UI must direct users to offboarding when the Ark server is cooperative.

## Native API Surface

Current `react-native-nitro-ark` exit APIs:

- `startExitForEntireWallet(): Promise<void>`
- `startExitForVtxos(vtxoIds: string[]): Promise<void>`
- `syncExit(): Promise<void>`
- `syncNoProgress(): Promise<void>`
- `progressExits(feeRateSatPerKvb?: number): Promise<ExitProgressStatusResult[]>`
- `getExitVtxos(): Promise<ExitVtxoResult[]>`
- `listClaimable(): Promise<ExitVtxoResult[]>`
- `getExitStatus(vtxoId, includeHistory?, includeTransactions?)`
- `hasPendingExits(): Promise<boolean>`
- `pendingExitTotal(): Promise<number>`
- `allClaimableAtHeight(): Promise<number | undefined>`
- `drainExits(vtxoIds, destinationAddress, feeRateSatPerKvb?): Promise<string>`

New APIs required for final in-app claiming:

- `extractTransaction(psbt: string): Promise<string>`
- `broadcastTransaction(txHex: string): Promise<string>`

`react-native-nitro-ark@0.0.109` exposes structured exit state metadata on
`ExitProgressStatusResult`, `ExitVtxoResult`, and `ExitStatusResult`:

- `state_details.tip_height`
- `state_details.confirmed_block`
- `state_details.claimable_height`
- `state_details.claimable_since`
- `state_details.last_scanned_block`
- `state_details.claim_txid`
- `state_details.txid`
- `state_details.block`
- `history_details`

This lets Noah display per-VTXO claimable heights, claim broadcast txids,
claimed block heights, and per-exit sync tips.

Final claim sequence:

1. `drainExits(vtxoIds, destinationAddress, feeRateSatPerKvb?)`
2. `extractTransaction(psbt)`
3. `broadcastTransaction(txHex)`
4. `syncExit()` and invalidate wallet queries

`progressExits()` already handles intermediate exit transaction creation/broadcasting through Bark's exit transaction manager. The app should not manually broadcast intermediate exit packages. The Noah wrapper mirrors Bark CLI/REST by calling `syncNoProgress()` before `progressExits()`.

## UX Model

Visual thesis: restrained emergency operations screen with neutral wallet surfaces, amber warning/waiting states, and green only for claimable or completed funds.

Primary screen: `Emergency Exit`.

Entry points:

- Settings -> Wallet -> Emergency Exit
- Later: VTXO detail -> start emergency exit for this VTXO

Top screen copy:

- Clear emergency disclaimer.
- Link/action back to normal `Offboard Ark`.
- Summary: tracked exits, pending amount, claimable amount, all-claimable block if known.
- Block status: current chain height, all-claimable height, per-exit synced tip height, and blocks remaining.

Timeline states:

- `Start`: Exit registered.
- `Processing`: Exit transactions are being prepared, broadcast, or confirmed.
- `AwaitingDelta`: Exit transaction is confirmed and waiting for the timelock.
- `Claimable`: Funds can be swept to an on-chain address.
- `ClaimInProgress`: Claim transaction has been broadcast and is waiting for confirmation.
- `Claimed`: Claim transaction confirmed.

Per-VTXO rows should show:

- Amount.
- Truncated VTXO id.
- Current state.
- Last known transaction id when available.
- State history when available.
- State-specific block information from `state_details`, including claimable height, claimable-since block, claim txid, and claimed block.

Actions:

- No tracked exits: `Start wallet exit`.
- Pending exits: `Progress exits` and `Sync status`.
- Claimable exits: destination address input and `Claim claimable exits`.
- Mixed claimable and pending exits: allow claimable sweep while still allowing progress for pending exits.

## Implementation Plan

### Client API Layer

Add `client/src/lib/exitApi.ts`:

- Wrap Nitro calls with `neverthrow`.
- Expose a single `claimExits()` helper that composes `drainExits`, `extractTransaction`, and `broadcastTransaction`.
- Keep direct Nitro usage out of screens.
- Import `extractTransaction` and `broadcastTransaction` directly from `react-native-nitro-ark`.

### Hooks

Add `client/src/hooks/useUnilateralExit.ts`:

- `useExitOverview()`; use `syncExit()` so claim confirmations can transition from `ClaimInProgress` to `Claimed`.
- `useStartWalletExit()`
- `useStartVtxoExit()`
- `useProgressExits()`
- `useSyncExits()`
- `useClaimExits()`

Query invalidation after mutations:

- `exit-overview`
- `balance`
- `vtxos`
- `getBlockHeight`

### Screen

Add `client/src/screens/UnilateralExitScreen.tsx`:

- Header/back navigation.
- Emergency notice.
- Summary strip.
- Block status panel.
- VTXO timeline list.
- Compact per-VTXO phase rail instead of long inline history text.
- Claim form.
- Confirmation dialogs for start/progress/claim.

Add `client/src/screens/ExitVtxoDetailScreen.tsx`:

- Opens from a VTXO card on the emergency exit screen.
- Shows the selected VTXO amount, current state, block status, current state details, and a collapsed vertical timeline.
- Collapses repeated states like `Processing x4` while preserving block/tip details and explorer links for txids.

### Navigation

Add `UnilateralExit` to `SettingsStackParamList`:

```ts
UnilateralExit: { vtxoIds?: string[] } | undefined;
```

Add Settings row:

- Title: `Emergency Exit`
- Description: `Recover funds if the Ark server is unavailable.`

## TODO

- [x] Document engineering plan.
- [x] Add `exitApi` wrappers.
- [x] Add unilateral exit hooks.
- [x] Add Settings route and screen.
- [x] Add VTXO detail entry for single-VTXO exits.
- [x] Replace temporary Nitro type cast after `react-native-nitro-ark` publishes the new methods.
- [x] Consume structured exit state details from Nitro.
- [x] Add compact list timeline and single-VTXO exit timeline screen.
- [ ] Add focused tests for exit state derivation after the UI model stabilizes.
- [x] Run `bun client check`.
