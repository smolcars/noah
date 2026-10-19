# Server API contract

`openapi.json` is the deterministic OpenAPI 3.1 contract generated from the Rust server with utoipa 5.5.0. All 59 ts-rs exports remain unchanged; the document also includes the HTTP-only DTOs and named notification variants (72 schemas total).

Check the contract without starting services:

```sh
cargo test -p server --bin server openapi::
```

Accept an intentional contract change:

```sh
UPDATE_OPENAPI=1 cargo test -p server --bin server openapi::export_openapi -- --exact
```

The export sorts all object keys, uses stable pretty JSON with a trailing newline, and fails on a missing or differing snapshot unless `UPDATE_OPENAPI=1`. It runs in the existing server test suite. ts-rs continues to regenerate `client/src/types/serverTypes.ts` through its existing tests.

On this macOS environment, server checks run inside `nix develop .#server`; the shell also needs `PROTOC` pointing to an installed protobuf compiler.

## Contract adaptations

- `NotificationData` is still the flat, internally tagged Serde union. Rust post-processing gives each variant a named object schema and adds an explicit `notification_type` discriminator mapping. No content envelope is added.
- Nullable references are inlined as their actual object or enum with a nullable type. Nullable enum values include JSON null. This preserves the five fields that the Swift generator dropped with raw utoipa nullable references. Requiredness, property descriptions, defaults and omission rules are retained from the derives.
- Utoipa's `u-int64`, `u-int32` and `u-int16` spellings become `int64`, `uint32` and `uint16`, each with `minimum: 0`. Practical amounts, sizes, counts and timestamps are below `2^63`; native clients use `Int64`/`Long` for u64. This documents an interoperability assumption, not an enforced server bound. Full-range u64 is not supported by those signed native types.
- LNURL success is an explicit `oneOf` of parameter and invoice responses. No query amount returns parameters; an amount returns an invoice response, including the empty `pr` used for negotiated direct Ark payments.
- Application errors use `ApiErrorResponse`. Axum JSON/query rejection and IP rate-limit bodies are documented as plain text. `getk1` retains its bare 500 response.

The router test inspects the actual `main.rs` routing expressions, follows merges/nesting and middleware layers, and compares paths, methods, handler operation IDs and auth requirements against the document. It rejects missing/phantom operations and unsupported routing syntax. Axum's implicit HEAD handling and CORS preflights are not separately mounted API operations.

## Operations and auth

All 37 explicit operations are documented: 5 GET and 32 POST. Authentication uses the HTTP `bearer` scheme with JWT bearer format. Each registered-user operation explicitly states the user-exists middleware requirement in its description. Email verification is optional.

Public (7):

```text
GET  /
GET  /health
GET  /.well-known/lnurlp/{username}
GET  /.well-known/nostr.json
GET  /v0/getk1
POST /v0/auth/login
POST /v0/app_version
```

Bearer JWT; registration optional (1):

```text
POST /v0/register
```

Bearer JWT and registered user (29):

```text
POST /v0/email/send_verification
POST /v0/email/verify
POST /v0/prices
POST /v0/historical-price
POST /v0/register_push_token
POST /v0/mailbox/authorize
POST /v0/mailbox/revoke
POST /v0/lnurlp/submit_invoice
POST /v0/ln_address_suggestions
POST /v0/user_info
POST /v0/update_lightning_identity
POST /v0/update_profile
POST /v0/deregister
POST /v0/report_job_status
POST /v0/heartbeat_response
POST /v0/report_last_login
POST /v0/recurring_payments/sync
POST /v0/support/ticket
POST /v0/backup/upload_url
POST /v0/backup/complete_upload
POST /v0/backup/list
POST /v0/backup/download_url
POST /v0/backup/delete
POST /v0/backup/settings
POST /v0/backup/v2/upload
POST /v0/backup/v2/complete
POST /v0/backup/v2/list
POST /v0/backup/v2/download
POST /v0/backup/v2/delete
```

## Native generator trial (2026-10-19)

Server verification passes: `cargo fmt --all --check`, `cargo clippy -p server --all-targets --locked -- -D warnings`, and `cargo test -p server --locked` (108 library tests and 290 server tests). A deliberately changed snapshot is rejected by the export test. Regenerated ts-rs output is byte-identical to the starting commit.

Scratch output lives under `/tmp/noah-openapi-phase04a`; no generated native code is committed.

- Offline OpenAPI Generator 7.26.0 validation passes. Its 18 unused-model recommendations are expected for notification/query schemas and the retained named schemas whose nullable usages are inlined.
- Apple's Swift OpenAPI Generator at `2b3e804e54f3839467336ffd47401215d66217c0` generates types and client code successfully. Both compile in a scratch Swift package using the cached OpenAPI runtime. All six notification cases round-trip. Probes preserve nullable device/attachment fields and values at `2^53`, `Int64.max` and `u32::MAX`. The cached runtime emits Swift concurrency warnings; generated code compiles.
- Fabrikt 28.0.0 generates Kotlinx models and client sources successfully. All generated models compile with Kotlin 2.4.21 and Kotlinx Serialization 1.11.0. All six notification implementations round-trip, and nullable fields and the same integer boundaries survive. Pass `--custom-type-mapping integer:uint32=kotlin.Long`: Fabrikt otherwise narrows uint32 to Kotlin `Int`.
- Both native probes reject `u64::MAX`, consistent with the documented signed-64-bit assumption.
- Fabrikt's default Kotlinx codec cannot decode the untagged LNURL union from its actual wire JSON. Native integration needs a content-based serializer for that union. Its default OkHttp client sources also use Jackson despite the Kotlinx model selection; generated Kotlin HTTP clients were not compiled or exercised. Selecting/adapting transport remains native-client work.

Proposed server-only `justfile` recipe (not added):

```just
# Accept intentional OpenAPI changes; native generation is added in Phase 0.4b.
generate-api:
    UPDATE_OPENAPI=1 cargo test -p server --bin server openapi::export_openapi -- --exact
```

Native client integration, permanent cross-platform boundary fixtures, and the dedicated TS/schema/native CI drift job remain in Phase 0.4. The server snapshot drift test already runs with ordinary `cargo test`.
