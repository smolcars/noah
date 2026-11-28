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
- **Direct Ark payments.** Noah's own apps pay a Noah recipient's Ark address directly when no
  comment is attached, without calling the callback, so there is nothing to attach payer data to.
  This needs a proof the payer posts, planned as an extension of LNURL-verify
  ([LUD-21](https://github.com/lnurl/luds/blob/luds/21.md)).
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

## Callback

1. Before tracing or Sentry see the request, `redact_lnurl_pay_query_middleware` (the outermost
   layer) moves `payerdata` and `comment` out of the URI into a request extension. The query is
   limited to 7 KiB.
2. Payer data is at most 2 KiB of JSON. `name` is trimmed and at most 80 characters;
   `identifier` must be a valid Lightning address, at most 320 characters, and is stored
   lowercase with an IDNA domain. A comment is at most 280 characters. Control and bidirectional
   control characters are refused. An empty `comment=` counts as no comment (released Noah apps
   always send one).
3. Payer data or a comment sent where it can't reach the recipient (none of the conditions above,
   or the 128-record quota is full) is refused with an LNURL error, never dropped unseen.
4. When the recipient qualifies, the server reserves an encrypted record before asking barkd for
   the invoice, then stores the invoice and its payment hash in it. A record is kept for every such
   payment, with or without payer data, so the app can show the payment as a Lightning address
   receive. If storing fails after barkd issued the invoice, the callback fails when payer data was
   sent (barkd's unpaid invoice expires), and otherwise returns the invoice unlabeled.

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
