# bark 0.8.0: issues found while building Noah's native wallet

From the Noah team, 2026-10-24. Noah is an Ark wallet built on bark through our UniFFI wrapper, [bark-bolt-ffi](https://github.com/smolcars/bark-bolt-ffi).

**What we checked:**
- every item below against bark `959a489f` (`lib-0.8.0`), the version we ship;
- bark master at `2ed8223a8` (2026-10-23). Master has no changes under `bark/`, `lib/` or `bitcoin-ext/` since 0.8.0, so everything below still applies there.

Line numbers are at `959a489f`. We've left out problems that turned out to be in our own wrapper; those are ours to fix.

## Summary

| # | Issue | Kind | How we know | Open MR |
| --- | --- | --- | --- | --- |
| 1 | An exit that's short of on-chain funds stalls, and no error says so | Regression (`175b2fa0f`) | Code | None |
| 2 | A Lightning send that succeeds after revocation has started stays stuck, and with HTLC exit allowed it's exited and recorded as failed | Bug | Unit test (attached) | None; !2663 marks it out of scope |
| 3 | A board writes its movement before its checkpoint | Bug | Code | None (!2551 keeps the order) |
| 4 | "Invoice has already been paid" is an untyped error with no amount, fee or movement | API gap | Code | None |
| 5 | No caller-supplied operation id for Ark sends, on-chain sends, `offboard_all` and refresh | Feature request | Code | None |

Our two open MRs, !2663 and !2664, are covered under [Our open MRs](#our-open-mrs), with tests that show they work.

**What we'd like from you:**
- Confirm items 1–4, and tell us whether you'd like MRs from us for any of them.
- Your view on the API for item 5.
- A review of !2663 and !2664.

---

## 1. An exit that's short of on-chain funds stalls, and no error says so

**What happens.** `progress_exits_with_cpfp` catches `CpfpError::InsufficientConfirmedFunds`, logs a warning and moves on (`bark/src/exit/bdk.rs:69-74`):

```rust
let tx = match onchain.make_signed_p2a_cpfp(&req.exit_tx, fees).await {
	Ok(tx) => tx,
	Err(CpfpError::InsufficientConfirmedFunds { needed, available }) => {
		warn!("Insufficient funds for exit CPFP: needed {} available {}", needed, available);
		continue;
	},
	Err(e) => return Err(e.into()),
};
```

`needed` and `available` are dropped there. Nothing else reports the shortfall:
- The final `progress_exits` builds each status from `ev.progress(..)`, which never sees the CPFP failure, so the VTXO's `ExitProgressStatus.error` is `None` (`bark/src/exit/mod.rs:730-781`).
- The transaction stays in `ExitTxStatus::AwaitingCpfpBroadcast`, which has no fields (`bark/src/exit/models/states.rs:16-34`), and `ExitCpfpRequest` has no fee field (`bark/src/exit/models/package.rs:13-21`).
- `progress_exits`, `sync`, `get_exit_status`, `list_all` and `exits_needing_cpfp` say nothing about funds.
- The daemon calls `progress_exits_with_cpfp` on every sync and logs only `Err` (`bark/src/daemon/mod.rs:357-366`).

**It's a regression.** `ExitError::InsufficientConfirmedFunds { needed, available }` still exists (`bark/src/exit/models/error.rs:84-88`), but nothing in 0.8.0 constructs it. It's only matched:
- `bark/src/exit/mod.rs:755`;
- `bark-json/src/exit/error.rs:236`;
- `testing/src/exit.rs:151-155`, the test helper, which still expects it.

Before `175b2fa0f` ("bark: refactor unilateral exit to be wallet-agnostic", 2026-04-25, first released in `bark-0.2.1`), CPFP ran inside progress, and this error was reported on the VTXO's status.

**Impact.** An app has no way to tell the user that the exit needs on-chain funds, or how much. Every progress call returns `Ok` with no error while the exit sits unfunded, possibly until the VTXO expires. Today we have to infer it from time spent in `AwaitingCpfpBroadcast` plus the confirmed balance.

**Suggested fix:**
1. In the `InsufficientConfirmedFunds` arm, record `req.vtxo_id → (needed, available)`.
2. After the final `progress_exits`, set `status.error = Some(ExitError::InsufficientConfirmedFunds { needed, available })` on those VTXOs.

That restores the contract `testing/src/exit.rs` already expects.

**Test** (`testing/tests/bark_sdk/exit.rs`):
1. Start an exit with an empty on-chain wallet.
2. Call `progress_exits_with_cpfp`, and assert that error with `needed > available`.
3. Fund the wallet, confirm the funds, and progress again. Assert that the transaction moves on to `AwaitingConfirmation`.

---

## 2. A Lightning send that succeeds after revocation has started stays stuck

**What happens.** A send enters revocation through any of these paths in `bark/src/actions/lightning/pay.rs`:
- `PaymentInitiated` with a pending payment once `is_htlc_near_expiry` (`:162-167`);
- a transient error near expiry (`on_retry`, `:215-226`);
- any abort after the HTLCs exist (`on_abort`, `:258-266`).

From then on, bark never checks the payment again. `check_lightning_send_payment_status` (`:588`) is called only from `PaymentInitiated`:

```rust
Progress::RevocableHtlcs { htlcs, revocation } |
Progress::RevocationStuck { htlcs, revocation } => {
	revoke_lightning_send_htlcs(wallet, &self, htlcs, revocation).await?;
	return Ok(Advance::Done);
},
```

If the payee settles while bark is revoking:
- **The server refuses the revocation,** correctly: "invoice has already been paid" (`server/src/ln/mod.rs:495-497`), or "This lightning payment has completed" past expiry (`:520-525`).
- **bark parks for good.** The refusal aborts, and `RevocableHtlcs` becomes `RevocationStuck` (`:267-273`), which parks with the error forever (`:274-283`).
- **The mailbox drops the preimage.** It settles one only for `PaymentInitiated` and ignores every other phase with "checkpoint is not PaymentInitiated; ignoring" (`bark/src/mailbox.rs:636-649`).

**Impact:**
- The send stays stuck: no paid record, the movement `Pending`, the HTLC VTXOs `Locked`.
- Those HTLCs keep counting in `Balance::pending_lightning_send` (`bark/src/balance.rs:185-189`), so the balance overstates what the user holds by the amount already paid.
- **With `allow_exit_of_htlcs` set** (`Wallet::allow_lightning_send_to_exit`), it's worse. Near expiry, bark exits HTLCs that were actually paid and finishes the movement `Failed` (`exit_lightning_send_htlcs`, `pay.rs:744-779`). Meanwhile the server can spend that output with the preimage (`lib/src/vtxo/policy/mod.rs:607-621`). The user pays exit fees for nothing, and their history says the payment failed.
- **Master raises captaind's `htlc_send_expiry_delta` default from 258 to 1008 blocks** (`d6bae740c`; its title says 1080, but `captaind.default.toml:185` sets 1008). On servers with that default, a send stuck this way waits about a week rather than about two days.

**Suggested fix:**
1. In `RevocableHtlcs` and `RevocationStuck`, call `check_lightning_send_payment_status` before revoking. On `Success(preimage)`, settle as `PaymentInitiated` does: the paid record, the HTLCs spent, the movement `Successful`.
2. Check the status again when the server rejects a revocation.
3. Let the mailbox settle a valid preimage in every phase that holds HTLCs (`HtlcReceived`, `PaymentInitiated`, `RevocableHtlcs`, `RevocationStuck`), under the action lock. This builds on !2663.

**Test.** `item5_mailbox_drops_preimage_once_revocation_started` in the attached patch is below. It puts a send in each revocation phase, then delivers a valid `LightningSendFinished` preimage through the mailbox. Today it ends with no paid record, a `Pending` movement and a `Locked` HTLC; the same message settles a `PaymentInitiated` send. The test passes while the bug exists and should fail once it's fixed. For an integration test, use an LND hold invoice held past the near-expiry threshold, then settled: it should end `Paid`, with the movement `Successful` and no checkpoint.

---

## 3. A board writes its movement before its checkpoint

**What happens.** `board_psbt` creates the BOARD movement, then writes the action checkpoint (`bark/src/board.rs`):

```rust
283	let movement_id = self.inner.movements.new_movement_with_update(
284		Subsystem::BOARD,
285		BoardMovement::Board.to_string(),
		…
292	).await?;
…
316	self.inner.db.upsert_wallet_action_checkpoint(&board.id, &board.clone().into()).await?;
```

The movement isn't tied to the action: it uses `new_movement_with_update`, not `get_or_create_movement_with_action`. Nothing reconciles a `Pending` BOARD movement without a checkpoint, because `pending_boards`, `pending_board_vtxos` and `sync_pending_boards` read checkpoints only (`board.rs:39-57, 101-115`).

**Impact, for a finalised PSBT** (`board_amount`, `board_all`, or a signed PSBT). If the process dies, or the checkpoint write fails, between lines 292 and 316:
- the movement stays `Pending` forever;
- a retry of the same board creates a second BOARD movement for the same funding outpoint.

No funds are at risk here: since `f85ca92ac` ("bark: keep failed boards out of the onchain wallet", in 0.8.0), the funding transaction is only signed at this point and is broadcast only from the checkpoint. But an app that waits for pending boards to clear, before boarding again or for a status, waits forever.

**Impact, for an unfinalised PSBT:**
1. The other party holds the signatures and may broadcast the funding transaction itself (`board.rs:194-197`).
2. The cosigned VTXO exists only in the checkpoint (`Progress::Broadcasting { signed_vtxo }`, `bark/src/actions/board.rs:110-117`); movements store only VTXO ids (`bark/src/movement/update.rs:36`).
3. A crash in the window therefore strands the funds in a board output that bark can neither register nor exit. Recovering them needs the server. Open MR !2551 (the server tracks pending boards from cosign) would help, but only on seed restore.

**Suggested fix:**
1. Create the movement with `get_or_create_movement_with_action(.., &board_action_id(utxo), ..)`, as Lightning, arkoor and offboard already do, so a retry reuses it.
2. Write the movement and the checkpoint in one SQLite transaction, through a persister method beside `get_or_create_movement_for_action` (`bark/src/persist/sqlite/mod.rs:132-153`).

A lighter alternative is for `sync_pending_boards` to fail any `Pending` BOARD movement whose action has no checkpoint. That's safe for finalised PSBTs only.

**Test** (`testing/tests/bark_sdk/board.rs`):
1. Wrap the persister so the first `upsert_wallet_action_checkpoint` after the movement insert fails.
2. Assert that `board_psbt` returns an error.
3. Call `sync_pending_boards`, and assert that no `Pending` BOARD movement is left without a checkpoint.

---

## 4. "Invoice has already been paid" is untyped and carries no amount, fee or movement

**What happens.** Paying an invoice whose hash has a paid record ends in `bail!("Invoice has already been paid")` (`bark/src/lightning/pay.rs:368-370`), a plain `anyhow` error. The paid record is only `{ payment_hash, preimage, paid_at }` (`bark/src/persist/models.rs:179-183`).

**Impact.** An app that offers "pay again" for a failed invoice has to match an error string to recognise a payment that's already done. Even then, it can't tell the user how much was paid or what it cost, because it can't find the movement that paid it. !2664 gives each attempt its own movement, but doesn't change this.

**Suggested fix:**
- a typed error, e.g. `AlreadyPaid { payment_hash, movement_id }`;
- or a lookup from a payment hash to the movement that paid it;
- and optionally the amount and fee on the paid record.

---

## 5. Request: a caller-supplied operation id

For crash recovery, we keep a journal entry for each operation before submitting it. After a restart, we need to match the entry to bark's movement exactly, never by amount.

We can already do that for most operations, from keys we know before submission:
- the payment hash for BOLT11 sends;
- the funding outpoint for boards;
- the VTXO ids for exits and for offboards or refreshes of chosen VTXOs;
- the claim PSBT's txid.

**Where we can't:**
- `send_arkoor_payment`;
- `send_onchain`;
- `offboard_all`;
- maintenance refresh.

In each of these, bark picks the inputs and a random action id inside `pub(crate)` builders:
- `bark/src/actions/arkoor_send.rs:229, 259, 272-274`;
- `bark/src/actions/offboard.rs:434, 444-447`.

The caller learns the movement id only after the call returns (`bark/src/arkoor.rs:295-321`, `bark/src/offboard.rs:106-139`). A crash in between leaves a movement we can't tie to our journal entry.

**Request:** an optional caller-supplied action id (an idempotency key) on those calls. If a checkpoint or movement already exists for that id (`get_movement_id_by_action`), bark would resume it or return it rather than start a second operation. Exposing the `start_*` step, so the id is known before driving, would work too.

**Test:** call twice with the same key, and crash between the checkpoint and the return. Both cases should give one movement and one server operation.

---

## Our open MRs

| MR | What it does | State (2026-10-24) |
| --- | --- | --- |
| [!2663](https://gitlab.com/ark-bitcoin/bark/-/merge_requests/2663) | Lightning drivers act only on the current checkpoint, reloaded under the action lock, and an interrupted settlement is finished. Without it, a driver holding a stale copy can bring a finished send's checkpoint back, and a send whose paid record was written before a crash stays `Pending` with its HTLCs locked. It also makes the daemon's periodic sync drive pending Lightning sends. | Open, not yet approved |
| [!2664](https://gitlab.com/ark-bitcoin/bark/-/merge_requests/2664) | A retried BOLT11 gets its own movement. Without it, the retry reuses the failed attempt's movement, which ends `Successful` with the first attempt's date, `intended_balance` and inputs, the second attempt's `effective_balance`, and an `offchain_fee` of 0. | Open, blocked on !2663 |

The attached tests reproduce both problems on 0.8.0 and pass against the fixes (table below).

## Running the attached tests

[`bark-0.8-repro-tests.patch`](bark-0.8-repro-tests.patch) adds:
- `bark/src/lightning/noah_verify.rs`, with five unit tests;
- two test-only helpers: `Wallet::offline_for_test` (an in-memory database with an unreachable server and chain, taken from !2663) and `ChainSource::set_tip_for_test`.

It needs no bitcoind and no server. Each test asserts today's behaviour, so **a passing test means the bug is present.**

```sh
git checkout 959a489f
git apply bark-0.8-repro-tests.patch
cargo test -p bark-wallet --lib noah_verify   # we ran it with Rust 1.95.0
```

| Test | Issue | At `959a489f` | At !2664's head (which includes !2663) |
| --- | --- | --- | --- |
| `item5_mailbox_drops_preimage_once_revocation_started` | 2 | passes: bug present | passes: not fixed |
| `item6_stale_copy_brings_a_finished_send_back` | !2663 | passes | fails: fixed |
| `item6_mailbox_settles_while_a_driver_holds_the_lock` | !2663 | passes | fails: fixed |
| `item6_interrupted_settlement_reads_paid_but_stays_pending` | !2663 | passes | fails: fixed |
| `item7_retry_reuses_and_mixes_the_failed_attempts_movement` | !2664 | passes | fails: fixed |

On the MR branch, `Wallet::offline_for_test` already exists, so apply only `noah_verify.rs` and its `mod` line in `bark/src/lightning/mod.rs`.

## Checked, and fine in 0.8.0

In 0.7.x, a board that died after cosign could be rebroadcast by the next on-chain sync, because `finish_psbt` had already applied the transaction to the BDK graph. `f85ca92ac` fixed that, and the fix is in 0.8.0. Item 3 is what remains.
