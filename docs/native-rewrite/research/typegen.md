**TL;DR — 2026-10-19:** Keep ts-rs for React Native. Adopt **Utoipa → versioned OpenAPI → Apple Swift OpenAPI Generator + Fabrikt/Kotlinx**, conditional on contract tests. This covers models and endpoints, but **is not plug-and-play**: experiments found nullable-reference omissions, broken Kotlin union generation, and unsigned-range loss. Tested schema normalization fixes the first two shapes; integer fidelity still needs resolution. Use handwritten notification codecs with Rust fixtures as a temporary fallback.

**Inventory.** [types.rs](/server/src/types.rs) contains all **59 TS exports: 54 structs, 5 enums**. There are **32 Option fields, 8 u64, 5 i64, 1 u32, 7 i32**, two Vec fields, and one `BTreeMap<String,f64>`. Five `rename_all` annotations cover UPPERCASE/lowercase/camelCase/snake_case. Four enums are strings; `NotificationData` is an internally tagged six-variant union using `notification_type`, without a content envelope. Two fields use both `default` and `skip_serializing_if`: `amount_usd` and last-login `device_info`. No exported flatten, untagged/adjacently tagged enums, HashMap, chrono/time types, nested generics, or generic DTO declarations. Dates are strings or integer timestamps.

[public_api_v0.rs](/server/src/routes/public_api_v0.rs) adds **six unexported HTTP types**: GetK1, Nip05RequestQuery/Response, LnurlpRequestQuery, LnurlpDefaultResponse/InvoiceResponse. They add camelCase, omitted optional fields, u16/u64 and maps/vectors. LNURL returns `Json<Value>` with different shapes depending on query; its schema needs explicit responses.

**Endpoints:** [main.rs](/server/src/main.rs:305) defines **37 operations: 32 POST, 5 GET; 33 under `/v0`**. Below, paths have `/v0` prefixed unless explicitly root-level.

| Requirement | Method and paths |
|---|---|
| Public | GET `/getk1`; POST `/auth/login`, `/app_version`; root-level GET `/`, `/health`, `/.well-known/lnurlp/{username}`, `/.well-known/nostr.json` |
| Bearer; registration optional | POST `/register` |
| Bearer + registered user | POST `/email/send_verification`, `/email/verify`, `/prices`, `/historical-price`, `/register_push_token`, `/mailbox/authorize`, `/mailbox/revoke`, `/lnurlp/submit_invoice`, `/ln_address_suggestions`, `/user_info`, `/update_lightning_identity`, `/update_profile`, `/deregister`, `/report_job_status`, `/heartbeat_response`, `/report_last_login`, `/recurring_payments/sync`, `/support/ticket`, `/backup/upload_url`, `/backup/complete_upload`, `/backup/list`, `/backup/download_url`, `/backup/delete`, `/backup/settings`, `/backup/v2/upload`, `/backup/v2/complete`, `/backup/v2/list`, `/backup/v2/download`, `/backup/v2/delete` |

Email verification is not required by the mounted router. [api.ts](/client/src/lib/api.ts) manually pairs paths with TS request/response types, uses native GET/POST, obtains bearer tokens through signed k1 login, and casts parsed JSON to `T`; those casts provide no runtime validation.

**Comparison.**

| Approach | Noah fit and evidence |
|---|---|
| Typeshare | Swift/Kotlin/TS, no endpoints. **1.13.4 tested:** rejects all 13 i64/u64 fields and NotificationData without `content`. Parser explicitly rejects flatten. Scratch U53/I54 substitutions generate simple models, but narrow Rust semantics; adding content would break the existing wire format. [Parser](https://github.com/1Password/typeshare/blob/v1.13.4/core/src/parser.rs). |
| Utoipa/OpenAPI | **5.5.0 tested:** generates all 59 schemas. `utoipa-axum` can register routes and specification together; handler responses/security remain explicit. Aide offers another Axum/schema route, but was not tested. [Utoipa integration](https://docs.rs/utoipa-axum/latest/utoipa_axum/), [Aide](https://docs.rs/aide/latest/aide/axum/). |
| Specta | Current main calls Swift stable and Kotlin partial; published native exporters are 0.0.3 with Specta 2.0.0-rc.25. Current source supports UInt64/ULong. Promising model-only alternative; Noah compatibility **unverified**. [Project](https://github.com/specta-rs/specta). |
| Schemars → quicktype | Serde-aware JSON Schema; no endpoints. Kotlinx renderer maps non-nullable unions to `JsonElement`, losing notification type safety; signed Long also limits u64. [Schemars](https://docs.rs/schemars_derive/latest/schemars_derive/derive.JsonSchema.html), [renderer](https://github.com/glideapps/quicktype/blob/master/packages/quicktype-core/src/language/Kotlin/KotlinXRenderer.ts). |
| Facet | Swift/Kotlin/TS generation, separate reflection/rename annotations. Existing Serde fidelity **unverified**. [facet-generate](https://github.com/redbadger/facet-generate). |
| Handwritten + fixtures | Smallest tooling investment; exact Codable/Kotlinx control. Fixtures catch sampled wire regressions, but cannot prove complete field/endpoint coverage. Useful bridge, weaker permanent drift protection. |

Typeshare is not archived, but its latest CLI [release](https://github.com/1Password/typeshare/releases/tag/v1.13.4) is December 2025 and latest default-branch commit observed was January 2026. Treat maintenance as slow, not definitively abandoned. Apple [1.14.2](https://github.com/apple/swift-openapi-generator/releases/tag/1.14.2) shipped today; Fabrikt [28.0.0](https://github.com/fabrikt-io/fabrikt/releases/tag/28.0.0) October 9.

**Actual trials** are retained in scratch (not committed). Typeshare and Utoipa used extracted real declarations preserving fields/Serde attributes, with unrelated derives/validation removed.

Apple generator main `2b3e804` compiled generated Swift and round-tripped all six notification variants. However, raw Utoipa nullable-reference schemas caused **five fields to disappear**, including registration device information. OpenAPI Generator **7.26.0**, Kotlin `jvm-ktor` + Kotlinx, incorrectly merged all notification variant fields into one required-field class. Fabrikt **28.0.0** initially emitted an empty sealed interface.

[normalize.py](typegen/normalize.py) creates named, flattened notification schemas plus discriminator mappings and inlines five nullable references. After normalization, Swift preserved device information and passed all six round-trips; Fabrikt emitted six sealed-interface implementations. Kotlin output was inspected, **not compiled/tested**. This demonstrates a viable schema adaptation, not a production-ready pipeline.

**Generated examples**, abridged from normalized output; initializers, generated codecs, imports, validation annotations and namespaces omitted. Signed integer types below expose the unresolved range issue.

```swift
enum UserStatus: String, Codable, Hashable, Sendable, CaseIterable {
    case active, inactive, deregistered
}
struct LightningClaimRequestNotification: Codable, Hashable, Sendable {
    var amount_sat: Swift.Int64
    var amount_usd: Swift.String?
    var payment_hash: Swift.String
}
enum NotificationData: Codable, Hashable, Sendable {
    case backup_trigger(NotificationDataBackupTrigger)
    case heartbeat(NotificationDataHeartbeat)
    case lightning_claim_request(NotificationDataLightningClaimRequest)
    case lightning_invoice_request(NotificationDataLightningInvoiceRequest)
    case maintenance(NotificationDataMaintenance)
    case recurring_payment_due(NotificationDataRecurringPaymentDue)
    // Generated decoder switches on notification_type;
    // encoder writes variant fields into the same JSON object.
}
```

```kotlin
enum class UserStatus(val value: String) {
    @SerialName("active") ACTIVE("active"),
    @SerialName("inactive") INACTIVE("inactive"),
    @SerialName("deregistered") DEREGISTERED("deregistered")
}
@Serializable
data class LightningClaimRequestNotification(
    @SerialName("amount_sat") val amountSat: Long,
    @SerialName("amount_usd") val amountUsd: String? = null,
    @SerialName("payment_hash") val paymentHash: String,
)
@Serializable
@JsonClassDiscriminator("notification_type")
@ExperimentalSerializationApi
sealed interface NotificationData

@SerialName("lightning_claim_request")
@Serializable
data class NotificationDataLightningClaimRequest(
    @SerialName("amount_sat") val amountSat: Long,
    @SerialName("amount_usd") val amountUsd: String? = null,
    @SerialName("payment_hash") val paymentHash: String,
) : NotificationData
// Five additional generated implementations represent the other variants.
```

**Integration steps.**

1. Preserve `TS` derives/export paths. Add `ToSchema` to DTOs and explicit request/query/response/error annotations to **35 named handlers**; document the two inline operational handlers separately. Register HTTP bearer security and a custom registered-user requirement. Middleware gating is not inferred. Prefer a shared router constructor for production/tests/schema export.
2. Export deterministic `api/openapi.json` from Rust without starting services. Implement audited normalization in that exporter. Resolve u64/u32 mappings through tested native unsigned codecs/type mappings or explicitly enforced API bounds; do not silently narrow the contract.
3. Generate into proposed `ios/NoahKit/Sources/NoahKit/Generated/` and `android/core/network/src/main/kotlin/.../generated/`, outside existing `client/ios` and `client/android`. Pin tool versions/configurations. Keep transport/token refresh in a small handwritten layer.
4. Add `just generate-api`: Rust export → native generators, alongside existing ts-rs test exports. `cargo test` can compare the Rust schema snapshot; a dedicated CI job regenerates TS/schema/native output and fails on tracked **and untracked** differences. Existing server CI runs `cargo test` but has no generated-file drift gate. Run Swift/Kotlin compilation and fixture tests separately.

**Risks/acceptance:** test every enum variant, absent/null/value optionals, unknown tags, integer boundaries (`2^53`, `i64::MAX`, `u64::MAX`, `u32::MAX`), and endpoint auth parity. Both unsigned maxima failed generated Swift decoding; unchanged TS `number` also cannot represent every u64. Fixture comparisons should use JSON semantics and direction-specific omission rules. Schema equality alone does not prove runtime behavior.

Repository remained unchanged. Full Kotlin builds, live endpoint calls, Aide/Specta/Facet/quicktype execution, and a production unsigned-integer solution remain unverified.
