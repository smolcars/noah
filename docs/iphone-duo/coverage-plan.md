# Whole-app adaptive layout pass

Planned October 7, 2026, before implementation. Builds on PR #354's measured-width layouts; existing wallet actions, authentication, and confirmation flows remain the source of truth.

## Implementation order

Before each group: read the screens and their callers, identify the smallest layout change, then record verification. Reuse existing content rather than creating separate phone and foldable screens. Preserve component identity during resizing. Use a single readable column where a second pane adds no useful context.

| Order | Screens | Minimal intended change | Verification |
| --- | --- | --- | --- |
| 1 | Settings | Two-column grouped overview; preference forms use a readable width within the existing native navigator. | Back navigation, selected page and scroll state through a fold; onboarding configuration still works. |
| 1 | Currency, Bitcoin Unit, Esplora, Backup Settings, Ark Info, Feedback, Export Database, UnifiedPush | Opt-in readable width, wrapping, scrolling and keyboard clearance. Reuse current controls and navigation. | Selection/save/cancel, long content, short window, large text, existing access gates. |
| 2 | Unilateral Exit | Existing summary/block status/timeline beside existing VTXO/start/claim controls; stack in compact windows. | Empty, waiting, claimable, claiming and error states; preserve selections, claim address, quotes and confirmations across folds. |
| 2 | Exit VTXO Detail | Status and block information beside the existing timeline. | Current state, long identifiers and block progress remain readable. |
| 2 | VTXOs, VTXO Detail | Inventory beside reused selected-item details where practical; preserve refresh batch selection separately. | Filters, item selection, refresh confirmation, detail navigation and fold continuity. |
| 2 | Board to Ark | Amount form beside balance/fee context, using the current board action. | Amount/max, fee state, keyboard, review and result. |
| 3 | Recurring Payments, Recurring Payment Editor | Readable schedule cards; editor beside an existing-data schedule summary. | Draft survives resize, controls remain reachable, platform availability and confirmations unchanged. |
| 3 | Profile, Lightning Address, Email Verification, QR Hub | Readable identity forms; QR beside identity/copy controls with pane-based sizing. | Draft/focus preservation, keyboard, validation, QR and navigation. |
| 3 | BTC Map | Reuse place details in a side pane on wide windows and the existing sheet on narrow windows. | Preserve selected place, camera position, search and dismissal. |
| 4 | Onboarding, Beta Warning, Mnemonic, Restore Wallet, Push Notifications Required, Battery Optimization | Readable widths and reachable actions in short windows; preserve seed authentication and privacy. | Fresh onboarding, restore input, scrolling and permission navigation. Never include seed words in evidence. |
| 4 | Noah Story, Logs, Debug, Receive Success, Transaction Detail | Readability/overflow pass; reuse existing presentation where already adequate. | Long text/IDs, lists, action reachability and back behavior. |
| 5 | Home, Send, Receive, Transactions, all shared sheets/dialogs | Regression pass over the existing adaptive implementation. | Draft/selection continuity, amount sheets, keyboard, copy/share, confirmations and primary navigation. |

## Test matrix

- Normal iPhone: iPhone 18 Pro, iOS 27.0; portrait and landscape where supported.
- Foldable iPhone: iPhone Duo, iOS 27.1; closed/open, rotation, repeated folds with active drafts and selected details.
- Android: Pixel 9 Pro, Android 16; ordinary phone UI regression checks, including keyboard and hardware back.
- For each group: inspect light/dark, short content height and large text. Capture representative screenshots on all three devices; keep an unedited dark-mode Duo recording for the final PR.
- Run `just check`, focused regression tests, and the existing adaptive smoke flow. Exercise funded regtest exit/claim behavior when the native stack permits it; distinguish UI fixture evidence from real wallet tests.
- Partial-fold interior regions and physical hardware verification remain explicit separate checks. Do not equate width adaptation with complete hinge/occlusion support.

## Safety and scope

No new layout dependency, payment path, wallet reset, or navigation replacement. Existing native project edits are preserved. The pre-pass tracked/untracked changes are backed up under `/tmp/noah-foldable-pass-baseline`. Keep CI's Docker project isolated; do not run destructive regtest bootstrap against an initialized wallet.

## Results

All screen groups above have layout changes or were reviewed as already suitable. The final Settings design keeps the existing native navigator. Shared measured-width columns and an opt-in readable width are the only added layout building blocks; there is no separate foldable screen tree or new layout dependency.

### Build and automated checks

- `nix develop --command just check`: lint, 128 unit tests / 348 assertions, and TypeScript passed after the final header fix.
- Xcode 27.1 `Noah-Regtest` simulator build and `just android-regtest` passed with Reanimated 4.7.1; the rebuilt binaries were installed on all three devices without resetting wallets.
- `adaptive-settings-checks.yml` passed in native Maestro on the normal iPhone: currency/unit selection, Profile draft, Backup scrolling, empty emergency exit, VTXOs, Board amount `12345`, recurring-payment draft/action reachability, and return to Home. The same screen sequence also passed through the iOS compatibility runner.
- `adaptive-rotation-checks.yml` passed three consecutive Profile draft → landscape → portrait → Back repetitions on the normal iPhone.
- Android's 49-step Settings flow and 32-step main-wallet flow passed through agent-device's Maestro compatibility runner. The latter covers Send amount 500, Receive note/keyboard and amount 500, sheet dismissal, and History. Native Android Maestro's driver could not connect to UiAutomation; these are compatibility-runner results.
- `scripts/test_expo_swift_interface.py` and `scripts/test_regtest_isolation.py` passed.

### Device checks

| Screen group | Observed results |
| --- | --- |
| Settings/preferences | Normal iPhone and Android flow passed; Duo grouped overview and preference navigation checked. Large-text Settings wraps on all three devices; Duo switches back to one column at accessibility text sizes. |
| Exit/VTXOs | Real funded Duo VTXO selection preserved closed/open; full exit and claim completed below. Empty exit and inventory checked on both ordinary phones. Large-text exit checked on all three; the normal-iPhone check found and fixed the title pushing Refresh offscreen. |
| Board/recurring editor | Draft and keyboard/action reachability checked on all three. Duo Profile, boarding amount and schedule label survived closed/open transitions. No recurring payment was saved or executed. |
| Profile/QR/map | Profile drafts verified on all three. Duo QR checked closed/open. The same selected BTC Map merchant and camera survived closed → open → closed; Android map selection/dismissal also checked. Android Ark Info and Feedback draft checked without submitting feedback. |
| Core tabs/sheets | Android core flow passed. Normal-iPhone Send and Receive amount/note/sheet behavior manually completed after a compatibility-runner text matcher rejected a visibly correct note; History checked. Earlier Duo core-tab fold checks remain documented in the initial report. |
| Onboarding/support pages | Fresh ordinary-iPhone onboarding exercised without capturing seeds. Remaining gated restore, seed, permission, email verification and success states received layout/code review, not an exhaustive authenticated end-to-end replay. |

Normal iPhone was checked in light and dark mode, Duo in dark mode, and Android in light and dark mode. Representative accessibility checks used iOS `accessibility-medium` and Android font scale 1.5; both were restored to their default sizes. These are targeted regression checks, not a claim that every state on every screen has full accessibility certification.

### Real regtest exit

The Duo wallet received 100,000 sats through Ark and 100,000 sats on chain for fees. Its five exit transactions were broadcast and confirmed at heights 181–185. Mining the required 144-block delay reached height 329; the native UI then became claimable.

The claim address survived closed → open, and the review confirmation survived open → closed. The real quote was 100,000 sats gross, 1,175 sats fee, and 98,825 sats received. Bitcoin Core confirmed claim transaction `ffabd25c153a5958930c9d5922692369e0f091c21d7ef95ddb63cb5004f1d023` at height 330 and reported `0.00098825 BTC` received at the fresh local destination. The UI displayed Claimed in block 330. Screenshots preserve this state; after subsequent synchronization, the active-exit screen was empty.

### Native rotation fix

Repeated normal-iPhone rotation and Back navigation exposed Reanimated 4.7.0's native `shadowIndex is out of range` abort. Upstream [4.7.1](https://swmansion.com/changelog/reanimated-4-7-1/) fixes the matching [view-flattening mutation bug](https://github.com/software-mansion/react-native-reanimated/pull/10676). The patch release, rebuilt iOS/Android binaries, and three consecutive native rotation checks replace the earlier failing result. No local Reanimated patch or navigation workaround remains.

### Evidence and limits

See [expanded-pass media](expanded-pass/README.md) for real regtest screenshots and a 38.98-second unedited dark-mode simulator recording. Earlier sample-data captures remain separately labeled in the parent media README.

Cloud-backup success is not covered: the local server lacks AWS backup configuration. No feedback/email, backup deletion, wallet deletion, or recurring payment was submitted. Physical devices, VoiceOver/TalkBack, Android foldable hardware, and partial-fold hinge occlusion are not covered. Android uses the same available-width/font-scale layout logic; it does not yet consume Android hinge geometry. Xcode successfully changed Duo displays, but its CoreDevice tool could not report a hinge-angle sample, so the evidence establishes display resizing and state continuity only.

CI runs in an isolated Compose project with separate volumes. It still shares fixed localhost ports with local regtest on the Mac; stop local regtest before running the CI wallet job. The latest remote CI result is separate from the local results above.
