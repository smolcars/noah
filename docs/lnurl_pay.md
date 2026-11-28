# LNURL-pay payer data and comments

How Noah's server receives [LUD-18](https://github.com/lnurl/luds/blob/luds/18.md) payer identity
and [LUD-12](https://github.com/lnurl/luds/blob/luds/12.md) comments for a user's Lightning
address, and hands them to the user's app. Tracking issue:
[#162](https://github.com/smolcars/noah/issues/162).

Sending works without the server: both apps send the payer's name and Lightning address when an
LNURL-pay service asks for them (`SendRecipientService` on each platform).

## What is covered

Only payments that barkd forwards. barkd receives the Lightning payment and forwards it to the
recipient's Ark address, so the app sees an Arkoor receive with no payment hash. The server
matches that delivery to the callback that requested the invoice.

Not covered yet:

- **The device-invoice fallback.** When barkd fails before issuing an invoice, the server wakes
  the recipient's phone to create one. That invoice carries no payer data or comment: the server
  can't match it to its payment yet. The payment itself goes through.
- **Direct Ark payments.** Noah's own apps pay a Noah recipient over Ark when no comment is
  attached: through the `second` payment option, whose callback returns the Ark address at review
  (older app versions take the address discovery returns for `ark=`). Nothing ties that Ark
  payment to the callback yet, so there is nothing to attach payer data to. See
  [Planned: payer data on Ark payments](#planned-payer-data-on-ark-payments).
- **Notifications** name the payer only on app versions that register a notification key (see
  [Notifications](#notifications)); older ones show "Payment received" as before.

## Settings

| Variable | Meaning |
| --- | --- |
| `LNURL_PAY_RECEIVE_METADATA_ENCRYPTION_KEY` | Base64 of 32 random bytes. Encrypts every stored record. Without it the feature is off and the list endpoint returns nothing. |
| `LNURL_PAY_RECEIVE_METADATA_ENABLED` | Accept and store payer data and comments. Needs the key. |
| `LNURL_PAY_RECEIVE_METADATA_ADVERTISED` | Advertise `payerData` and `commentAllowed`. Needs `ENABLED`. |

The two flags let a rolling deploy finish before wallets start sending data: turn on `ENABLED` once
no instance without this code is running, then `ADVERTISED` once every instance accepts the data.
Rotating the key makes stored records unreadable; they expire within seven days.

## Discovery

`GET /.well-known/lnurlp/{username}` includes

```json
"commentAllowed": 280,
"payerData": {"name": {"mandatory": false}, "identifier": {"mandatory": false}}
```

only while `ADVERTISED` is on and the server could pass them on for this recipient: barkd is
configured, the recipient has an Ark address, their mailbox authorization covers the next hour
(the mailbox worker must see the delivery), and they have fewer than 128 payments waiting to be
matched. Otherwise both fields are left out, so wallets don't offer a comment that would be lost.

It always lists its rails in `paymentOptions`
([lnurl/luds#303](https://github.com/lnurl/luds/pull/303)): `{"id": "lightning", "type": "lightning"}`,
and `{"id": "second", "type": "bark"}` while the recipient has an Ark address. Discovery with
`ark=<server key>` still returns the Ark address as `ark`, for released Noah apps.

## Callback

1. Before tracing or Sentry see the request, `redact_lnurl_pay_query_middleware` (the outermost
   layer) moves `payerdata` and `comment` out of the URI into a request extension. The query is
   limited to 7 KiB.
2. Payer data is at most 2 KiB of JSON. `name` is trimmed and at most 80 characters;
   `identifier` must be a valid Lightning address, at most 320 characters, and is stored
   lowercase with an IDNA domain. A comment is at most 280 characters. Control and bidirectional
   control characters are refused. An empty `comment=` counts as no comment (released Noah apps
   always send one).
3. On the Lightning rail, payer data or a comment sent where it can't reach the recipient (none of
   the conditions above, or the 128-record quota is full) is refused with an LNURL error, never
   dropped unseen.
4. When the recipient qualifies, the server reserves an encrypted record before asking barkd for
   the invoice, then stores the invoice and its payment hash in it. A record is kept for every such
   payment, with or without payer data, so the app can show the payment as a Lightning address
   receive. If storing fails after barkd issued the invoice, the callback fails when payer data was
   sent (barkd's unpaid invoice expires), and otherwise returns the invoice unlabeled.
5. `paymentOption=second` returns the recipient's Ark address,
   `{"paymentOption": "second", "paymentDestination": "<address>"}`, for a whole number of sats.
   Payer data and a comment are checked as above, then dropped: until there are
   [reports](#report), nothing ties an Ark payment to its callback, and a note isn't worth failing
   the payment over. `paymentOption=lightning`, or none, gets an invoice as above (with
   `"paymentOption": "lightning"` when it was asked for); any other value gets
   `Unsupported paymentOption`. With a `paymentOption`, the older `ark=` negotiation is ignored.

## Matching the delivery

barkd claims the payment's HTLC VTXOs and forwards the result to the recipient's mailbox, all in
one Arkoor message (one checkpoint). Each delivered VTXO's direct parent, before the claim's
checkpoint transaction, is one of those HTLC VTXOs. barkd's receive movement lists them in its
metadata (`htlc_vtxos`) and is found with `GET /api/v1/history?type=invoice&value=<bolt11>`.
Checked on regtest with barkd 0.8.0: a single 10,000-sat payment arrived as two VTXOs, each the
child (through a checkpoint) of a different one of the two HTLC VTXOs barkd listed, and further up
both chains shared the Ark server's own VTXOs. Split payments are therefore normal, and only the
nearest HTLC ancestors identify the payment.

For an Arkoor message to a recipient with waiting records, the mailbox worker:

1. stores the decoded delivery, encrypted, keyed by mailbox checkpoint;
2. looks up barkd's movement for up to 16 waiting records at a time (8 concurrently), newest
   first;
3. matches a record when the movement's payment hash is the record's, every HTLC VTXO is in the
   delivery's history, every delivered VTXO descends from one of them, the outputs pay the stored
   address on our Ark server, and their total is the amount barkd says it forwarded;
4. sends the usual push, then marks the record ready and advances the mailbox checkpoint in one
   transaction, so the app can't acknowledge the record before the message is consumed.

A movement barkd still reports as pending, a failed lookup, or a record this server version can't
read keeps the checkpoint where it is, and the message replays. More waiting records than one page
end the session without advancing, and the replay checks the next page. This runs while catch-up
notifications are suppressed too. barkd's movement metadata also holds the payment preimage; the
server never reads or logs it.

Records not matched within an hour of the callback are deleted, so a late payment arrives without
metadata. Ready records are kept for at most seven days.

## App API

- `POST /v0/lnurlp/receive_metadata/list`: up to 100 ready records, oldest first. Each has an
  `id`, the `amount_sat` the recipient received (after barkd's fee), a `target` naming the wallet
  movement, and the optional `payer_data` and `comment`. `target` is
  `{"type": "ark_output_vtxos", "vtxo_ids": [...]}`: the exact output VTXOs of one Arkoor
  receive. `lightning_payment_hash` is reserved for device invoices and not produced yet.
- `POST /v0/lnurlp/receive_metadata/ack` with `{"ids": [...]}` (at most 100): deletes those of the
  caller's records once the app has stored them with their movement. Other IDs are ignored.

The app should match a record only to a movement with exactly those output VTXOs, never by amount
or time, store it in Bark's movement metadata so it is backed up with the wallet, and only then
acknowledge it.

Values are payer-provided and unverified; show them as such.

## Notifications

The visible "Payment received" push for a matched payment names the payer (and shows the comment)
even when the app isn't running, including after the user swiped it away, without Apple, Google or
a UnifiedPush distributor seeing the payer data. The title is the payer (`Name (⚡ identifier)`),
the body "Sent you {amount}" with the comment below. Decided on 2026-11-27:

- **End-to-end encrypted content.** When the app registers its push token it also sends a random
  notification key it keeps. The matched payment's push carries, besides today's text, the record
  ID and the payer data encrypted with that key.
- **Structured, not text.** The encrypted part is JSON (amount, name, identifier, comment), so the
  device writes the notification in the user's language and can shorten it, for example for a
  watch.
- **Decrypted on the device before display.** iOS: the `NoahNotificationService` extension, which
  iOS runs for every push marked `mutable-content`, also after a force-quit; it reads the app's
  language and amount unit from the widget snapshot in the app group. Android: the Firebase and
  UnifiedPush handlers, which build Noah's notifications already. Without a key, or when
  decryption fails, today's text shows.
- The key must be readable while the phone is locked (after first unlock), since pushes arrive
  then. iOS keeps it in the app group container; Android in `noBackupFilesDir`, encrypted under an
  AndroidKeyStore key. Neither is backed up; a key that can't be read is replaced and registered
  again.

The apps store the payer data with the payment in a pass after each foreground sync, as described
under [App API](#app-api). History names the payer on the row, and the payment's details show
"Name" and "Lightning address" on their own rows, with the comment as its note. While Noah is
in front, a session-owned wallet notification observer also triggers the pass for successful
`bark.arkoor` receives. It tries immediately, then after delays of 2 and 5 seconds, stopping once
the exact movement contains the metadata. Empty or busy passes still retry: the server may not
have marked the record ready yet. Each attempt uses the usual foreground admission and sync
slot; retries release that slot while waiting. Wallet database changes refresh History after the
write, without a manual refresh. This works with notifications denied or push delivery unavailable;
push receipt no longer triggers metadata retrieval. The minute pass remains the fallback for
missed events and records that become ready after the short retries.

### Push format

`POST /v0/register_push_token` takes an optional `notification_key`: base64 of 32 random bytes
the app generated and keeps. Registering without one (an older app) clears it.

The visible push for a matched payment keeps its title and body ("Payment received", "You
received … via Ark.") and its data (`notification_type: "ark_payment_received"`, `vtxo_ids`), and
adds:

```json
"lnurl_pay_receive": {"v": 1, "id": "<record ID>", "sealed": "<base64>"}
```

`sealed` is there only when the app registered a key. It is AES-256-GCM under that key: base64 of
`nonce (12 bytes) || ciphertext || tag (16 bytes)`, CryptoKit's combined box, with the additional
data `noah-lnurl-pay-receive-notification:v1:<record ID>`. Inside is

```json
{"amount_sat": 9880, "payer_data": {"name": "…", "identifier": "…"}, "comment": "…"}
```

where `payer_data` and `comment` may be null, as in the list endpoint. `amount_sat` is what the
recipient received. `apps/fixtures/lnurl-pay-receive-notification.json` holds sealed examples for
both apps' tests. Every visible APNs push is marked `mutable-content`, so the extension sees them
all and must pass through those it doesn't recognise. The largest possible content still fits
APNs's and FCM's 4 KB limits (tested).

The server sends this push before it marks the record ready, so an app that lists records right
after the push may need to retry briefly.

### iOS notification service extension: one-time Apple setup

Needed before a device or release build contains the extension; simulator builds and CI tests sign
ad hoc and don't need it. Status: **not done yet**.

1. For each variant, an App ID for the extension, `<app bundle ID>.NotificationService`:
   `com.noahwallet.mainnet.NotificationService`, `com.noahwallet.signet.NotificationService`,
   `com.noahwallet.regtest.NotificationService`.
2. Enable App Groups on each and add the variant's group (`group.com.noahwallet.mainnet`,
   `.signet`, `.regtest`), which the app and widget already use.
3. Release archives sign automatically on the self-hosted runner (`-allowProvisioningUpdates` in
   `native-release.yml`), so Xcode can create the profiles for these App IDs if its account may;
   otherwise create App Store profiles for them by hand. Check with one archive before tagging a
   release that contains the extension.

Reading:

- [UNNotificationServiceExtension](https://developer.apple.com/documentation/usernotifications/unnotificationserviceextension)
- [Modifying content in newly delivered notifications](https://developer.apple.com/documentation/usernotifications/modifying-content-in-newly-delivered-notifications)
- [Generating a remote notification](https://developer.apple.com/documentation/usernotifications/generating-a-remote-notification)
  (`mutable-content`)
- [Pushing background updates to your app](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app)
  (why a silent push can't do this: it is throttled and not delivered to a force-quit app)
- [Receive messages in an Android app](https://firebase.google.com/docs/cloud-messaging/android/receive)
  (data messages reach a swiped-away app, not a force-stopped one)

## Planned: payer data on Ark payments

Not built yet; decided on 2026-11-28. It covers payments from Noah apps that go over Ark instead
of Lightning, so the comment and payer data reach the recipient without barkd.

### The gap in `paymentOptions`

LUD-12 comments and LUD-18 payer data date from when every callback returned its own invoice, so
a service could always tie a payment to its callback. `paymentOptions`
([lnurl/luds#303](https://github.com/lnurl/luds/pull/303)) adds rails whose destination may be
reused, such as a static Ark address or an on-chain address. A comment sent for such an option
reaches the service, but the service can't tell which payment it belongs to, so it is lost
unseen. #303 keeps `commentAllowed` and `payerData` at the top level and can't limit them to some
options.

What we would propose on #303: comments and payer data reach the recipient on an option only when
the service can tie the payment to its callback. That holds for Lightning (one invoice per
callback), for a destination issued per payment, and for a report both sides support (below). Our
server still accepts them on `second` without a report and drops them, rather than fail a payment
over a note; a report is best effort anyway.

### Report

A proposed extension to `paymentOptions`, built so others can adopt it; whether #303 takes it is
open. The payer tells the service which payment was its own, after or just before paying. Three
fields, named apart in the way #303's proposed `verifiable` sits beside `verify`:

- **The option** lists the method it accepts: `"reportMethod": "vtxo"`. A name rather than a
  boolean, so a stronger method later gets its own name instead of changing what an existing one
  means. It may become a list if a service needs to accept an old and a new method at once.
- **The callback request** repeats it, `reportMethod=vtxo`: the wallet promises to report, so the
  service keeps the comment and payer data for the match instead of dropping them.
- **The callback response** carries `"report": "<url>"` beside `paymentDestination` (and `verify`,
  if any). The URL holds at least 128 random bits and names that one callback. It is a capability:
  keep it, and what is reported to it, out of logs.

Methods:

- **`vtxo`** (bark): after paying, `POST <report URL>` with
  `{"vtxoIds": ["<txid>:<vout>", …]}`, the VTXOs the send created for the recipient.
- **`tx`** (on-chain, later): before broadcasting, the wallet posts the signed transaction. Only the
  payer has it until then, and the service can check the outputs and amount at once, as with
  BIP70's Payment message. A txid reported after broadcast would prove nothing, since anyone
  watching the address sees it. A fee bump posts the replacement to the same URL; it must spend
  the same inputs. The alternative, a fresh address per payment derived from the user's on-chain
  xpub, needs no report but lets our server see all of the user's on-chain activity.

### Flow

1. **Discovery.** The server lists `lightning`, and `second` (type `bark`) with the Ark server's
   public key as `operator` (as proposed on #303) and `"reportMethod": "vtxo"`. The app offers
   Ark only when the operator is its own Ark server. `ark=<server key>` at discovery keeps working
   for released apps.
2. **Callback.** `paymentOption=second&reportMethod=vtxo`, with `amount`, `payerdata` and `comment`
   as today. The server reserves a record for the recipient and returns the Ark address as
   `paymentDestination` plus `report`.
3. **Payment.** The app stores the report URL in the send journal before paying, as it stores the
   invoice on the Lightning rail, then pays with `send_arkoor_payment`.
4. **Report.** From the movement it gets back, the app reads the VTXOs the send created for the
   recipient (`metadata.destination_vtxos`, see [bark change](#bark-change)) and posts them. It
   retries on later passes until the server accepts or the URL expires; a failed report never
   makes the payment fail.
5. **Match.** The server matches the reported set against one delivery to the recipient that the
   mailbox worker saw, in either order. It also checks that every VTXO pays the recipient's
   address on our Ark server, that they add up to the callback's amount, and that no other
   report bound any of them. The first valid report binds the set; the same set again is
   accepted, a different one refused. The record then becomes ready and follows the existing
   push, list and ack path, so the recipient's app needs no change.

Recurring payments do the same. With this in place, a payment with a comment can go over Ark too.

### Why report the recipient's VTXOs

- Before delivery only the payer and the Ark server know these VTXO IDs, so nobody else can attach
  payer data to someone else's payment. The recipient and our server learn them on delivery.
- The payer's spent VTXOs would need no bark change, but whoever paid those VTXOs to the payer also
  knows their IDs and could report the payment as theirs, or block its payer data.
- It is not a cryptographic proof: a payer with two callbacks to the same recipient could swap
  their reports. It can only mislabel its own payments, whose payer data it chose anyway.

### Why not LNURL-verify

[LUD-21](https://github.com/lnurl/luds/blob/luds/21.md)'s `verify` lets the payer ask whether a
payment settled. A report goes the other way: the payer tells the service which delivery was its
payment. LUD-21's `pr` and `preimage` don't exist for Ark, other wallets would read a `verify`
field as plain LUD-21, and the name promises more than knowing the VTXO IDs proves. So the report
gets its own URL.

### bark change

bark records the spent and change VTXOs on an arkoor send's movement, but the VTXOs it creates for
the recipient only live in the send's checkpoint, which bark deletes when the send is done.
[bark MR 2610](https://gitlab.com/ark-bitcoin/bark/-/merge_requests/2610) made
`send_arkoor_payment` return the movement ID, which isn't enough on its own. A bark MR to store
them as `metadata.destination_vtxos` when the movement is created, as lightning movements keep
`htlc_vtxos`, is prepared; then bark-bolt-ffi needs a release with it and both apps a version bump.

### Later: an address pool

A fresh address per payment, as ArkLabs does, needs no report at all and also names payers using
other bark wallets. Noah can't wake the phone for every callback, so the app would upload a pool
of addresses ahead of time:

- bark already knows their keys, they are in the backed-up wallet database, and a pool well under
  bark's VTXO key gap limit (250) is found again on recovery from the seed phrase.
- The server hands out one only for a callback with payer data or a comment, and puts an
  address back once its callback expires unpaid (the amount check guards a late payment).
- Draining the pool is the DoS risk, limited per recipient. An empty pool falls back to the
  static address, with a report from Noah payers, so payments always go through.

Ruled out: the server deriving addresses from the wallet's VTXO xpub. bark refuses an arkoor
VTXO for a key it hasn't revealed yet (only seed recovery scans ahead), the server's indices would
mix with the wallet's own, and the xpub would let our server recognise every VTXO a user spends,
including in payments to other Noah users.

## Open items

- Confirm barkd forwarding is enabled on mainnet; without it, payments use the device-invoice
  fallback, which carries no payer data.
- Ask barkd to keep `htlc_vtxo_ids` on a settled receive, or to list the VTXOs it delivered, so
  [matching](#matching-the-delivery) doesn't depend on keys in barkd's movement metadata.
- Pay Lightning addresses with bark's payment origin (`pay_lightning_invoice_with_origin`, not in
  bark-bolt-ffi yet), so the movement's `sent_to` is a `lightning-address` that any bark tool
  reads. Today the apps write the address in `noah.repeat_payment`, after the payment succeeds.

## Rejected alternatives

- **Matching by amount and time.** Two payments of the same amount would get each other's payer
  data.
- **Sending callbacks with payer data to the phone instead of barkd.** Those payments would need
  the phone to answer, and lose barkd's forwarding while it is offline.
- **Extending LNURL-verify, or using the spent VTXOs, for Ark payments.** See
  [Why not LNURL-verify](#why-not-lnurl-verify) and
  [Why report the recipient's VTXOs](#why-report-the-recipients-vtxos).
- **Deriving Ark addresses on the server.** See [Later: an address pool](#later-an-address-pool).
