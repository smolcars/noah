//! Offline contract export. Run with UPDATE_OPENAPI=1 to accept intentional changes.
use serde_json::{Map, Value, json};
use utoipa::OpenApi;

use crate::routes::{gated_api_v0, public_api_v0};
use crate::types::NotificationData;

#[derive(OpenApi)]
#[openapi(
    info(
        title = "Noah API",
        version = "0.1.0",
        license(name = "MIT", identifier = "MIT"),
        description = "Noah wallet API. Bearer authentication uses a JWT obtained from /v0/auth/login. Registered-user requirements are stated per operation; email verification is optional. Unsigned 64-bit values use int64 for native Int64/Long clients: practical amounts, sizes, counts and Unix timestamps are below 2^63. This is an interoperability assumption, not a new server validation rule; Rust u64 can represent larger values. JSON extractor and IP rate-limit rejections are plain text, while application errors use ApiErrorResponse."
    ),
    paths(
        crate::root,
        crate::health,
        gated_api_v0::authorize_mailbox,
        gated_api_v0::complete_backup_object_upload,
        gated_api_v0::complete_upload,
        gated_api_v0::delete_backup,
        gated_api_v0::delete_backup_object,
        gated_api_v0::deregister,
        gated_api_v0::get_backup_object_download_url,
        gated_api_v0::get_download_url,
        gated_api_v0::get_upload_url,
        gated_api_v0::get_user_info,
        gated_api_v0::heartbeat_response,
        gated_api_v0::initiate_backup_object_upload,
        gated_api_v0::list_backup_objects,
        gated_api_v0::list_backups,
        gated_api_v0::ln_address_suggestions,
        gated_api_v0::register_push_token,
        gated_api_v0::report_job_status,
        gated_api_v0::report_last_login,
        gated_api_v0::revoke_mailbox_authorization,
        gated_api_v0::submit_invoice,
        gated_api_v0::submit_support_ticket,
        gated_api_v0::sync_recurring_payments,
        gated_api_v0::update_backup_settings,
        gated_api_v0::update_lightning_identity,
        gated_api_v0::update_profile,
        public_api_v0::auth_login,
        public_api_v0::check_app_version,
        public_api_v0::fiat_prices,
        public_api_v0::get_k1,
        public_api_v0::historical_fiat_price,
        public_api_v0::lnurlp_request,
        public_api_v0::nip05_request,
        public_api_v0::register,
        public_api_v0::send_verification_email,
        public_api_v0::verify_email
    ),
    components(schemas(
        NotificationData,
        public_api_v0::Nip05RequestQuery,
        public_api_v0::LnurlpRequestQuery
    )),
    modifiers(&BearerSecurity)
)]
struct ApiDoc;

struct BearerSecurity;

impl utoipa::Modify for BearerSecurity {
    fn modify(&self, openapi: &mut utoipa::openapi::OpenApi) {
        use utoipa::openapi::security::{HttpAuthScheme, HttpBuilder, SecurityScheme};
        openapi.components.as_mut().unwrap().add_security_scheme(
            "bearer",
            SecurityScheme::Http(
                HttpBuilder::new()
                    .scheme(HttpAuthScheme::Bearer)
                    .bearer_format("JWT")
                    .build(),
            ),
        );
    }
}

fn document() -> Value {
    let mut document = serde_json::to_value(ApiDoc::openapi()).unwrap();
    let schemas = document["components"]["schemas"]
        .as_object()
        .unwrap()
        .clone();
    normalize_fields(&mut document, &schemas);
    flatten_notifications(document["components"]["schemas"].as_object_mut().unwrap());
    // serde_json's preserve_order can be enabled transitively; sort explicitly.
    document.sort_all_objects();
    document
}

fn normalize_fields(value: &mut Value, schemas: &Map<String, Value>) {
    match value {
        Value::Object(object) => {
            // Raw nullable refs made five fields disappear in Swift. Inline the actual
            // object/enum with a nullable type, as verified by the native generator trial.
            for union in ["oneOf", "anyOf"] {
                let reference = object
                    .get(union)
                    .and_then(Value::as_array)
                    .and_then(|parts| {
                        if parts.len() == 2 && parts.contains(&json!({ "type": "null" })) {
                            parts
                                .iter()
                                .find_map(|part| part.get("$ref").and_then(Value::as_str))
                        } else {
                            None
                        }
                    });
                if let Some(reference) = reference {
                    let name = reference.strip_prefix("#/components/schemas/").unwrap();
                    let mut inner = schemas[name].as_object().unwrap().clone();
                    let kind = inner["type"]
                        .as_str()
                        .expect("nullable ref must be an object or enum");
                    inner.insert("type".into(), json!([kind, "null"]));
                    if let Some(values) = inner.get_mut("enum") {
                        values.as_array_mut().unwrap().push(Value::Null);
                    }
                    object.remove(union);
                    inner.append(object);
                    *object = inner;
                }
            }
            // Utoipa 5 spells its non-strict unsigned formats `u-int*`.
            let unsigned_format = match object.get("format").and_then(Value::as_str) {
                Some("u-int64") => Some("int64"),
                Some("u-int32") => Some("uint32"),
                Some("u-int16") => Some("uint16"),
                _ => None,
            };
            if let Some(format) = unsigned_format {
                object.insert("format".into(), json!(format));
                object.insert("minimum".into(), json!(0));
            }
            if unsigned_format == Some("int64") {
                let description = object.entry("description").or_insert(json!(""));
                let existing = description.as_str().unwrap();
                *description = json!(format!(
                    "{existing} Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients."
                ).trim());
            }
            for child in object.values_mut() {
                normalize_fields(child, schemas);
            }
        }
        Value::Array(values) => {
            for child in values {
                normalize_fields(child, schemas);
            }
        }
        _ => {}
    }
}

fn flatten_notifications(schemas: &mut Map<String, Value>) {
    let variants = schemas["NotificationData"]["oneOf"]
        .as_array()
        .unwrap()
        .clone();
    let mut references = Vec::new();
    let mut mapping = Map::new();
    for variant in variants {
        let parts = variant["allOf"]
            .as_array()
            .expect("internally tagged newtype variant");
        assert_eq!(parts.len(), 2);
        let reference = parts.iter().find_map(|part| part["$ref"].as_str()).unwrap();
        let tag = parts
            .iter()
            .find(|part| part["properties"].is_object())
            .unwrap();
        let tag_value = tag["properties"]["notification_type"]["enum"][0]
            .as_str()
            .unwrap();
        let name = format!(
            "NotificationData{}",
            tag_value
                .split('_')
                .map(|word| {
                    let mut chars = word.chars();
                    format!(
                        "{}{}",
                        chars.next().unwrap().to_ascii_uppercase(),
                        chars.as_str()
                    )
                })
                .collect::<String>()
        );
        let mut payload = schemas[reference.strip_prefix("#/components/schemas/").unwrap()].clone();
        payload["properties"]
            .as_object_mut()
            .unwrap()
            .extend(tag["properties"].as_object().unwrap().clone());
        payload["required"]
            .as_array_mut()
            .unwrap()
            .extend(tag["required"].as_array().unwrap().clone());
        assert!(
            schemas.insert(name.clone(), payload).is_none(),
            "variant name collision"
        );
        let reference = format!("#/components/schemas/{name}");
        references.push(json!({ "$ref": reference }));
        mapping.insert(tag_value.into(), json!(reference));
    }
    schemas["NotificationData"]["oneOf"] = json!(references);
    schemas["NotificationData"]["discriminator"] = json!({
        "propertyName": "notification_type", "mapping": mapping
    });
}

#[test]
fn export_openapi() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../api/openapi.json");
    let generated = format!("{}\n", serde_json::to_string_pretty(&document()).unwrap());
    if std::env::var("UPDATE_OPENAPI").as_deref() == Ok("1") {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &generated).unwrap();
    }
    let committed = std::fs::read_to_string(&path).unwrap_or_default();
    assert!(
        committed == generated,
        "api/openapi.json is stale; run UPDATE_OPENAPI=1 cargo test -p server openapi::export_openapi"
    );
}

/// Push payloads exactly as the server serializes them, for the native apps'
/// decoding tests (`apps/fixtures/server-notification-data.json`).
#[test]
fn export_notification_fixtures() {
    use crate::types::{
        BackupTriggerNotification, HeartbeatNotification, LightningClaimRequestNotification,
        LightningInvoiceRequestNotification, MaintenanceNotification,
        RecurringPaymentDueNotification,
    };

    let notifications = [
        (
            "maintenance",
            NotificationData::Maintenance(MaintenanceNotification {
                notification_k1: "k1-maintenance".into(),
            }),
        ),
        (
            "lightning invoice request",
            NotificationData::LightningInvoiceRequest(LightningInvoiceRequestNotification {
                transaction_id: "tx-123".into(),
                amount: 21_000,
            }),
        ),
        (
            "lightning claim request with usd",
            NotificationData::LightningClaimRequest(LightningClaimRequestNotification {
                payment_hash: "ab".repeat(32),
                amount_sat: 2_100_000_000_000_000,
                amount_usd: Some("12.34".into()),
            }),
        ),
        (
            "lightning claim request without usd",
            NotificationData::LightningClaimRequest(LightningClaimRequestNotification {
                payment_hash: "cd".repeat(32),
                amount_sat: 1,
                amount_usd: None,
            }),
        ),
        (
            "backup trigger",
            NotificationData::BackupTrigger(BackupTriggerNotification {
                notification_k1: "k1-backup".into(),
            }),
        ),
        (
            "heartbeat",
            NotificationData::Heartbeat(HeartbeatNotification {
                notification_id: "heartbeat-1".into(),
            }),
        ),
        (
            "recurring payment due",
            NotificationData::RecurringPaymentDue(RecurringPaymentDueNotification {
                due_count: u32::MAX,
            }),
        ),
    ];
    let cases: Vec<_> = notifications
        .iter()
        .map(|(name, notification)| {
            json!({
                "name": name,
                "input": serde_json::to_value(notification).unwrap(),
                "expected": notification.notification_type(),
            })
        })
        .collect();
    let fixture = json!({
        "description": "NotificationData as the server serializes it into the push `body` string. Generated by server/src/openapi.rs; regenerate with UPDATE_OPENAPI=1.",
        "cases": cases,
    });

    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../apps/fixtures/server-notification-data.json");
    let generated = format!("{}\n", serde_json::to_string_pretty(&fixture).unwrap());
    if std::env::var("UPDATE_OPENAPI").as_deref() == Ok("1") {
        std::fs::write(&path, &generated).unwrap();
    }
    let committed = std::fs::read_to_string(&path).unwrap_or_default();
    assert!(
        committed == generated,
        "apps/fixtures/server-notification-data.json is stale; run UPDATE_OPENAPI=1 cargo test -p server openapi::"
    );
}

#[path = "openapi_tests.rs"]
mod tests;
