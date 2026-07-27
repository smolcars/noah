# LNURL-pay behavior

This note documents Noah's LNURL-pay sender and receiver behavior, including
LUD-18 payer identity, comments, and interoperability behavior.

Tracking issue: [smolcars/noah#162](https://github.com/smolcars/noah/issues/162)

Specifications:

- [LUD-06: LNURL-pay](https://github.com/lnurl/luds/blob/luds/06.md)
- [LUD-18: payer identity in LNURL-pay](https://github.com/lnurl/luds/blob/luds/18.md)
- [LUD-12: comments in LNURL-pay](https://github.com/lnurl/luds/blob/luds/12.md)

## LUD-18 payer identity

When an LNURL-pay service advertises a `payerData` object, Noah supports these
fields:

| LUD-18 field | Noah source |
| --- | --- |
| `name` | The user's saved profile display name |
| `identifier` | The user's saved Noah Lightning address |

Noah includes only fields explicitly advertised by the service. Optional
unsupported fields are ignored. A payment stops before the callback if the
service marks an unsupported field as mandatory, or if a supported mandatory
field has no saved value.

If the service does not advertise `payerData`, Noah continues with ordinary
LNURL-pay and does not send a `payerdata` callback parameter.

The negotiated object is serialized once and sent in the callback's lowercase
`payerdata` query parameter. For example:

```json
{
  "name": "Hampus",
  "identifier": "hampus@noahwallet.io"
}
```

## Comments

Noah sends a comment only when the service advertises a positive
`commentAllowed` limit. The limit is checked using Unicode code points rather
than JavaScript UTF-16 code units.

The send screen currently allows the user to enter a note before LNURL
discovery. If the service does not support comments, Noah omits that note from
the callback rather than failing the payment.

## Discovery and callback behavior

- Noah first attempts Lightning-address discovery with its optional Ark
  extension. If that request fails, it retries standard discovery without the
  extension.
- Both standard Lightning and Ark routes use the LNURL callback response.
- Callback URLs must use HTTPS and redirects are not followed, preventing
  payer identity or comments from being redirected to cleartext HTTP.
- The parsed LNURL body controls success or failure independently of the HTTP
  status code, as specified by LUD-01.
- A returned BOLT11 is checked for the requested amount and Bitcoin network.
- Noah does not require or validate the former LNURL-pay metadata binding via a
  BOLT11 `description_hash`; that LUD-06 requirement has been removed.

## Deferred LUD-06 metadata presentation

The discovery response's `metadata` string is currently retained with the
resolved route, but Noah does not yet validate that it decodes to a LUD-06
metadata array containing a `text/plain` entry. The send confirmation also
does not show that `text/plain` description. Validation and presentation are
therefore deferred, and the sender is not yet fully LUD-06 compliant.

This limitation is separate from the deliberate decision not to enforce the
removed LNURL-pay metadata binding through BOLT11 `description_hash`. The
BOLT11 feature itself remains valid.

## Receive-side LUD-18

Standard LNURL-pay discovery advertises optional `name` and `identifier`
fields with `mandatory: false`, plus a 280-code-point comment limit. A sender
may provide either, both, or neither identity field. Noah treats every value
as payer-provided and unverified.

The standard BOLT11 receive path handles the metadata as follows:

1. Before application tracing or Sentry sees the request, middleware removes
   `payerdata` and `comment` from the URI and places them in a private request
   extension. The raw query is limited to 7 KiB.
2. The callback validates and normalizes the supported fields. Payer data is
   limited to 2 KiB; names to 80 Unicode code points; identifiers to a
   normalized, valid Lightning address of at most 320 code points; and
   comments to 280 code points. Control and bidirectional-control characters
   are rejected.
3. The server encrypts the short-lived delivery record with
   XChaCha20-Poly1305 in Redis/Dragonfly. The unbound callback record lives for
   two minutes.
4. The authenticated invoice submission validates the BOLT11 signature,
   network, exact amount, and expiry, derives its payment hash, and atomically
   binds the encrypted record before exposing the invoice to the callback.
   The bound record lives through the invoice expiry plus a five-minute grace
   period. Invoice lifetimes up to the receiver's configured 48-hour Bark
   expiry are accepted.
5. Mailbox settlement changes the record to ready state for up to seven days.
   The authenticated client lists only its own ready records.
6. Once the exact payment-hash movement exists, the client writes a versioned
   JSON Merge Patch through Bark's `update_history_metadata`, reads history
   back to verify it, schedules the normal wallet backup, and then
   acknowledges the relay record. Unacknowledged work is retried during
   startup, foregrounding, sync, and Lightning claim processing.

PostgreSQL never stores the payer identity or comment. Redis/Dragonfly is only
an encrypted, expiring delivery relay; Bark's SQLite movement metadata is the
canonical wallet copy and is included in the existing v2 wallet snapshot.
The server's remote push remains PII-free. After claiming the payment, the
device creates one local notification using the persisted payer label and
comment when available, or a generic Lightning-received message otherwise.

Transaction lists and details render both identity fields as
`Name (⚡ identifier)` when both are present, with the comment below. A single
available field is shown on its own. The detail view explicitly labels the
values as payer-provided.

### Direct Ark receive

The existing direct-Ark LNURL callback remains enabled. Ark-negotiated
discovery deliberately advertises no `payerData` and `commentAllowed: 0`, so
it does not currently accept receive-side LUD-18 metadata. A future design can
correlate Ark settlement without storing payer data in PostgreSQL by assigning
one-time Bark receive addresses and matching their public VTXO identity in the
mailbox stream. That address pool must be backed up before publication and
addresses must never be reused.

## Manual interoperability verification

A Noah wallet configured with:

```text
name:       Hampus
identifier: hampus@noahwallet.io
```

successfully sent payer identity and comments to LNURL-pay receivers.

One receiver displayed the payer label and comment as:

```text
Hampus (⚡ hampus@noahwallet.io)
Hello from Noah
```

This independently confirmed visible interoperability for all three values:

- `name`: `Hampus`
- `identifier`: `hampus@noahwallet.io`
- `comment`: `Hello from Noah`

A separate 1,000-sat payment received by Blixt Lightning Box appeared in
Blixt's transaction list as:

```text
hampus@noahwallet.io: Hello
```

This confirmed the identifier and comment, but the card did not display the
name.

## Why Blixt did not show the name

The local Blixt source was inspected read-only on its `windows` branch at
commit `2b682bceb3b2f16aa8dc7c652afd8433de27a1f1`.

Blixt's receive path is designed to retain the name:

1. `src/state/LightningBox.ts` advertises optional `name`, `identifier`, and
   `email` payer-data fields.
2. Its callback handler parses and retains all three fields.
3. `src/state/Receive.ts` attaches the parsed object to the transaction.
4. `src/storage/database/transaction.ts` persists the three fields separately.

The apparent omission comes from `src/components/TransactionCard.tsx`. It
renders exactly one payer label using this priority:

```text
identifier -> email -> name
```

When both `name` and `identifier` are present, the card therefore displays only
`hampus@noahwallet.io`. Blixt's transaction-details screen does not expose the
other LUD-18 fields either. This is a Blixt presentation limitation, not an
indication that Noah failed to send the name.

## Deferred work

- Extend receive-side payer metadata to the direct-Ark path using one-time,
  backed-up Bark receive addresses and mailbox correlation.
- Validate LUD-06 metadata and show its `text/plain` description during send
  confirmation.
- Decide how payer-provided receive metadata should appear in transaction CSV
  exports. It is intentionally omitted for now.
- Consider showing both payer name and identifier in Blixt.
