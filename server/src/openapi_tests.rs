use super::*;
use crate::types::*;
use std::collections::{BTreeMap, BTreeSet};
use syn::Item;

/// Every method axum's `MethodRouter` can route.
const PROBED_METHODS: [&str; 9] = [
    "get", "head", "post", "put", "delete", "patch", "options", "trace", "connect",
];

/// The paths the production router serves. Axum has no route listing, but its
/// Debug output names every path; an unreadable format yields no paths and fails below.
fn served_paths(app: &axum::Router) -> BTreeSet<String> {
    let debug = format!("{app:?}");
    let mut paths = BTreeSet::new();
    for entry in debug.split("RouteId(").skip(1) {
        let Some((_, rest)) = entry.split_once("): \"") else {
            continue;
        };
        let path = &rest[..rest.find('"').unwrap()];
        if !path.contains("__private__axum") {
            paths.insert(path.to_string());
        }
    }
    paths
}

async fn send(
    app: &axum::Router,
    method: &str,
    path: &str,
    token: Option<&str>,
    peer: &mut u32,
) -> (
    axum::http::StatusCode,
    Option<String>,
    axum::http::HeaderMap,
) {
    let (status, headers, body) = exchange(app, method, path, token, None, peer).await;
    let code = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|body| body["code"].as_str().map(String::from));
    (status, code, headers)
}

/// Sends one request from its own peer address, so this audit never reaches a quota.
async fn exchange(
    app: &axum::Router,
    method: &str,
    path: &str,
    token: Option<&str>,
    json: Option<&Value>,
    peer: &mut u32,
) -> (
    axum::http::StatusCode,
    axum::http::HeaderMap,
    axum::body::Bytes,
) {
    use axum::{body::Body, extract::ConnectInfo, http::Request};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    *peer += 1;
    let mut request = Request::builder()
        .method(method.to_uppercase().as_str())
        .uri(path.replace("{username}", "nobody"));
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    let mut request = match json {
        Some(json) => request
            .header("content-type", "application/json")
            .body(Body::from(json.to_string())),
        None => request.body(Body::empty()),
    }
    .unwrap();
    request
        .extensions_mut()
        .insert(ConnectInfo(std::net::SocketAddr::from((
            std::net::Ipv4Addr::from(0x0a00_0000 + *peer),
            3000,
        ))));
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let headers = response.headers().clone();
    let body = response.into_body().collect().await.unwrap().to_bytes();
    (status, headers, body)
}

#[tokio::test]
async fn every_operation_is_served_behind_its_documented_gates() {
    use crate::tests::common::{TestUser, create_test_user, setup_test_app};
    use axum::http::StatusCode;

    let (_, state, _guard) = setup_test_app().await;
    let app = crate::app_router(state.clone());
    let registered = TestUser::new_with_key(&[0x11; 32]);
    create_test_user(&state, &registered, None).await;
    let registered = registered.access_token(&state);
    let unregistered = TestUser::new_with_key(&[0x22; 32]).access_token(&state);
    let forged = crate::auth::mint_access_token(
        &crate::config::Config {
            auth_jwt_secret: "not-the-server-secret".to_string(),
            ..crate::tests::common::TestUser::get_config()
        },
        &TestUser::new_with_key(&[0x22; 32]).pubkey().to_string(),
    )
    .unwrap()
    .token;
    let mut peer = 0;

    let doc = document();
    assert_eq!(
        doc["components"]["securitySchemes"]["bearer"],
        json!({
            "type": "http", "scheme": "bearer", "bearerFormat": "JWT"
        })
    );
    let paths = doc["paths"].as_object().unwrap();
    assert_eq!(
        served_paths(&app),
        paths.keys().cloned().collect::<BTreeSet<_>>(),
        "the router serves exactly the documented paths"
    );
    assert_eq!(
        paths
            .values()
            .map(|item| item.as_object().unwrap().len())
            .sum::<usize>(),
        37
    );

    for (path, item) in paths {
        for method in PROBED_METHODS {
            let Some(op) = item.get(method) else {
                let (status, _, headers) =
                    send(&app, method, path, Some(&registered), &mut peer).await;
                if method == "head" && item.get("get").is_some() {
                    // Axum serves HEAD from the GET handler with the body dropped; the document lists only GET.
                    let (get, _, _) = send(&app, "get", path, Some(&registered), &mut peer).await;
                    assert_eq!(status, get, "{method} {path} answers as GET does");
                } else if method == "options" && path == "/.well-known/lnurlp/{username}" {
                    // Browser wallets fetch LNURL cross-origin, so the CORS layer answers the preflight, not a handler.
                    assert_eq!(status, StatusCode::OK, "{method} {path}");
                    assert_eq!(
                        headers["access-control-allow-methods"], "GET",
                        "{method} {path}"
                    );
                } else {
                    // Every other method the document leaves out is refused, so no operation goes undocumented.
                    assert_eq!(status, StatusCode::METHOD_NOT_ALLOWED, "{method} {path}");
                }
                continue;
            };
            let operation = format!("{method} {path}");
            let bearer = op.get("security").is_some();
            let needs_registration = op["description"]
                .as_str()
                .unwrap_or_default()
                .contains("a registered user (user-exists middleware)");

            let (status, code, _) = send(&app, method, path, None, &mut peer).await;
            assert_ne!(status, StatusCode::NOT_FOUND, "{operation} has no route");
            assert_ne!(
                status,
                StatusCode::METHOD_NOT_ALLOWED,
                "{operation} has no route"
            );
            if !bearer {
                assert!(!needs_registration, "{operation}");
                assert_ne!(status, StatusCode::UNAUTHORIZED, "{operation} is public");
                // Without a body a public handler answers 2xx or rejects the input. A 5xx means
                // something in front of it failed, such as a gate that expects an authenticated user.
                assert!(
                    status.is_success() || status.is_client_error(),
                    "{operation} is public, got {status} {code:?}"
                );
                continue;
            }
            assert_eq!(op["security"], json!([{ "bearer": [] }]), "{operation}");
            assert_eq!(
                (status, code.as_deref()),
                (StatusCode::UNAUTHORIZED, Some("AUTH_REQUIRED")),
                "{operation} without a token"
            );

            let (status, code, _) = send(&app, method, path, Some(&forged), &mut peer).await;
            assert_eq!(
                (status, code.as_deref()),
                (StatusCode::UNAUTHORIZED, Some("INVALID_TOKEN")),
                "{operation} with a token the server did not sign"
            );

            let (status, code, _) = send(&app, method, path, Some(&unregistered), &mut peer).await;
            let probe = format!("{operation} with a valid token for a key that never registered");
            if needs_registration {
                assert_eq!(
                    (status, code.as_deref()),
                    (StatusCode::UNAUTHORIZED, Some("USER_NOT_FOUND")),
                    "{probe}"
                );
            } else {
                assert_ne!(code.as_deref(), Some("USER_NOT_FOUND"), "{probe}");
            }
        }
    }
}

/// Gated operations whose request names a stored object (an ID, key or name another user can
/// hold), with all their request fields. `true` marks one that may still succeed when it names
/// another user's object, because it then acts only on the caller's own.
const OBJECT_FIELDS: [(&str, &[&str], bool); 13] = [
    (
        "post /v0/backup/complete_upload",
        &["backup_size", "backup_version", "s3_key"],
        false,
    ),
    ("post /v0/backup/delete", &["backup_version"], false),
    ("post /v0/backup/download_url", &["backup_version"], false),
    ("post /v0/backup/upload_url", &["backup_version"], true),
    ("post /v0/backup/v2/complete", &["backup_id"], false),
    ("post /v0/backup/v2/delete", &["backup_id"], false),
    ("post /v0/backup/v2/download", &["backup_id"], false),
    ("post /v0/heartbeat_response", &["notification_id"], false),
    (
        "post /v0/lnurlp/submit_invoice",
        &["invoice", "transaction_id"],
        true,
    ),
    (
        "post /v0/recurring_payments/sync",
        &[
            "schedules",
            "schedules[].next_run_at",
            "schedules[].schedule_id",
        ],
        true,
    ),
    (
        "post /v0/register",
        &[
            "ark_address",
            "device_info",
            "device_info.app_build",
            "device_info.app_version",
            "device_info.device_manufacturer",
            "device_info.device_model",
            "device_info.os_name",
            "device_info.os_version",
            "ln_address",
        ],
        false,
    ),
    (
        "post /v0/report_job_status",
        &["error_message", "notification_k1", "report_type", "status"],
        false,
    ),
    (
        "post /v0/update_lightning_identity",
        &["nostr_pubkey", "username"],
        false,
    ),
];

/// Gated operations whose request fields were reviewed as naming nothing another user owns.
const CALLER_ONLY_FIELDS: [(&str, &[&str]); 17] = [
    ("post /v0/backup/list", &[]),
    ("post /v0/backup/settings", &["backup_enabled"]),
    ("post /v0/backup/v2/list", &[]),
    (
        "post /v0/backup/v2/upload",
        &["encrypted_sha256", "encrypted_size", "format_version"],
    ),
    ("post /v0/deregister", &[]),
    ("post /v0/email/send_verification", &["email"]),
    ("post /v0/email/verify", &["code"]),
    ("post /v0/historical-price", &["currency", "timestamp"]),
    ("post /v0/ln_address_suggestions", &["query"]),
    // The mailbox key signs the authorization, so naming another mailbox needs its key.
    (
        "post /v0/mailbox/authorize",
        &["encoded", "expiry", "mailbox_id"],
    ),
    ("post /v0/mailbox/revoke", &[]),
    ("post /v0/prices", &[]),
    (
        "post /v0/register_push_token",
        &["apns_sandbox", "provider", "push_token"],
    ),
    (
        "post /v0/report_last_login",
        &[
            "device_info",
            "device_info.app_build",
            "device_info.app_version",
            "device_info.device_manufacturer",
            "device_info.device_model",
            "device_info.os_name",
            "device_info.os_version",
        ],
    ),
    (
        "post /v0/support/ticket",
        &[
            "attachment",
            "attachment.base64_data",
            "attachment.content_type",
            "attachment.filename",
            "body",
            "device_info",
            "device_info.app_build",
            "device_info.app_version",
            "device_info.device_manufacturer",
            "device_info.device_model",
            "device_info.os_name",
            "device_info.os_version",
            "email",
            "name",
            "subject",
        ],
    ),
    ("post /v0/update_profile", &["display_name"]),
    ("post /v0/user_info", &[]),
];

/// Every field a gated operation's request carries: each parameter as `<in> parameter <name>`,
/// and each body property at any depth, as `a.b` inside an object and `a[].b` inside an array.
/// A request shape it does not understand is an error, so no field is skipped silently.
fn gated_request_fields(doc: &Value) -> BTreeMap<String, Result<BTreeSet<String>, String>> {
    let mut operations = BTreeMap::new();
    for (path, item) in doc["paths"].as_object().unwrap() {
        for (method, op) in item.as_object().unwrap() {
            if op.get("security").is_none() {
                continue;
            }
            let mut fields = BTreeSet::new();
            let result = (|| {
                for parameter in op["parameters"].as_array().into_iter().flatten() {
                    let (Some(location), Some(name)) =
                        (parameter["in"].as_str(), parameter["name"].as_str())
                    else {
                        return Err(format!("a parameter it cannot read: {parameter}"));
                    };
                    fields.insert(format!("{location} parameter {name}"));
                }
                let Some(body) = op.get("requestBody") else {
                    return Ok(());
                };
                let content = body["content"]
                    .as_object()
                    .map(|content| content.keys().collect::<Vec<_>>());
                if content.as_deref() != Some(&[&"application/json".to_string()][..]) {
                    return Err(format!(
                        "a body other than one application/json schema: {body}"
                    ));
                }
                collect_fields(
                    doc,
                    &body["content"]["application/json"]["schema"],
                    "",
                    &mut fields,
                    0,
                )
            })();
            operations.insert(format!("{method} {path}"), result.map(|()| fields));
        }
    }
    operations
}

fn collect_fields(
    doc: &Value,
    schema: &Value,
    at: &str,
    fields: &mut BTreeSet<String>,
    depth: usize,
) -> Result<(), String> {
    if depth > 16 {
        return Err(format!("a schema nested too deep at `{at}`"));
    }
    if let Some(reference) = schema.get("$ref") {
        let target = reference
            .as_str()
            .and_then(|reference| reference.strip_prefix("#/components/schemas/"))
            .and_then(|name| doc["components"]["schemas"].get(name))
            .ok_or_else(|| format!("a reference it cannot resolve at `{at}`: {reference}"))?;
        return collect_fields(doc, target, at, fields, depth + 1);
    }
    let types = match &schema["type"] {
        Value::String(kind) => vec![kind.as_str()],
        Value::Array(kinds) => kinds.iter().filter_map(Value::as_str).collect(),
        _ => Vec::new(),
    };
    let understood = [
        "type",
        "properties",
        "required",
        "items",
        "description",
        "format",
        "minimum",
        "maximum",
        "enum",
        "additionalProperties",
    ];
    if let Some(key) = schema
        .as_object()
        .unwrap()
        .keys()
        .find(|key| !understood.contains(&key.as_str()))
    {
        return Err(format!("a schema with `{key}` at `{at}`: {schema}"));
    }
    if types.contains(&"object") {
        if schema
            .get("additionalProperties")
            .is_some_and(|extra| extra != false)
        {
            return Err(format!("an object with open-ended properties at `{at}`"));
        }
        for (name, property) in schema["properties"].as_object().into_iter().flatten() {
            let field = if at.is_empty() {
                name.clone()
            } else {
                format!("{at}.{name}")
            };
            fields.insert(field.clone());
            collect_fields(doc, property, &field, fields, depth + 1)?;
        }
        Ok(())
    } else if types.contains(&"array") {
        collect_fields(doc, &schema["items"], &format!("{at}[]"), fields, depth + 1)
    } else if !types.is_empty()
        && types
            .iter()
            .all(|kind| ["string", "integer", "number", "boolean", "null"].contains(kind))
    {
        Ok(())
    } else {
        Err(format!("a schema of no known type at `{at}`: {schema}"))
    }
}

/// Why each gated operation's request fields are not classified exactly once; empty when they are.
fn classification_failures(doc: &Value) -> Vec<String> {
    let operations = gated_request_fields(doc);
    let mut failures = Vec::new();
    for (operation, fields) in &operations {
        let fields = match fields {
            Ok(fields) => fields,
            Err(shape) => {
                failures.push(format!(
                    "{operation} sends {shape}. Teach gated_request_fields to read it, so none of \
                     its fields escapes classification."
                ));
                continue;
            }
        };
        let listed = OBJECT_FIELDS
            .iter()
            .map(|(op, fields, _)| (op, fields))
            .chain(CALLER_ONLY_FIELDS.iter().map(|(op, fields)| (op, fields)))
            .filter(|(op, _)| *op == operation)
            .map(|(_, fields)| {
                fields
                    .iter()
                    .map(|field| field.to_string())
                    .collect::<BTreeSet<_>>()
            })
            .collect::<Vec<_>>();
        if listed != [fields.clone()] {
            failures.push(format!(
                "{operation} sends {fields:?}. If a field names something a user owns, list the \
                 operation once in OBJECT_FIELDS and give user B one to name; otherwise list it \
                 once in CALLER_ONLY_FIELDS."
            ));
        }
    }
    for (operation, _) in OBJECT_FIELDS
        .iter()
        .map(|(op, fields, _)| (op, fields))
        .chain(CALLER_ONLY_FIELDS.iter().map(|(op, fields)| (op, fields)))
    {
        if !operations.contains_key(*operation) {
            failures.push(format!(
                "{operation} is classified but is not a gated operation"
            ));
        }
    }
    failures
}

#[test]
fn a_request_field_the_inventory_cannot_classify_fails() {
    let edits: [(&str, fn(&mut Value)); 4] = [
        ("a nested property", |doc| {
            doc["components"]["schemas"]["RecurringPaymentScheduleEntry"]["properties"]["backup_id"] =
                json!({"type": ["string", "null"]});
        }),
        // On an operation with no body today, so a reader that skips the shape sees nothing new.
        ("a form body", |doc| {
            doc["paths"]["/v0/backup/list"]["post"]["requestBody"] = json!({"content": {
                "application/x-www-form-urlencoded": {"schema": {
                    "type": "object", "properties": {"backup_id": {"type": "string"}},
                }},
            }});
        }),
        ("an inline schema", |doc| {
            doc["paths"]["/v0/backup/list"]["post"]["requestBody"] = json!({"content": {
                "application/json": {"schema": {
                    "type": "object", "properties": {"backup_id": {"type": "string"}},
                }},
            }});
        }),
        ("a query parameter", |doc| {
            doc["paths"]["/v0/backup/list"]["post"]["parameters"] =
                json!([{"in": "query", "name": "backup_id", "schema": {"type": "string"}}]);
        }),
    ];
    assert_eq!(classification_failures(&document()), Vec::<String>::new());
    let escaped = edits
        .into_iter()
        .filter(|(_, edit)| {
            let mut doc = document();
            edit(&mut doc);
            classification_failures(&doc).is_empty()
        })
        .map(|(case, _)| case)
        .collect::<Vec<_>>();
    assert!(
        escaped.is_empty(),
        "a gated request gained a field, and the inventory missed it in: {escaped:?}"
    );
}

/// Everything `owner` holds that a request can name, as text to compare.
async fn holdings(
    state: &crate::AppState,
    owner: &str,
    named: &serde_json::Map<String, Value>,
) -> String {
    use crate::db::{
        backup_repo::BackupRepository, heartbeat_repo::HeartbeatRepository,
        job_status_repo::JobStatusRepository, recurring_payment_repo::RecurringPaymentRepository,
        user_repo::UserRepository,
    };
    let backups = BackupRepository::new(&state.db_pool);
    let backup_id = named["backup_id"].as_str().unwrap().parse().unwrap();
    format!(
        "{:?}\n{:?}\n{:?}\n{:?}\n{:?}\n{:?}\n{:?}",
        UserRepository::new(&state.db_pool)
            .find_by_pubkey(owner)
            .await
            .unwrap(),
        backups.find_by_pubkey_and_version(owner, 1).await.unwrap(),
        backups.find_object(owner, backup_id).await.unwrap(),
        HeartbeatRepository::find_status_and_responded_at(
            &state.db_pool,
            named["notification_id"].as_str().unwrap()
        )
        .await
        .unwrap(),
        JobStatusRepository::find_status_and_error_by_k1(
            &state.db_pool,
            owner,
            named["notification_k1"].as_str().unwrap()
        )
        .await
        .unwrap(),
        RecurringPaymentRepository::new(&state.db_pool)
            .list_for_pubkey(owner)
            .await
            .unwrap(),
        state
            .invoice_store
            .get(owner, named["transaction_id"].as_str().unwrap())
            .await
            .unwrap(),
    )
}

/// Operations whose answer may tell A that B's object exists, and why that is by design.
const DISTINGUISHABLE: [(&str, &str); 2] = [
    (
        "post /v0/register",
        "Ark and Lightning addresses are unique, so taking one that is taken must say so",
    ),
    (
        "post /v0/update_lightning_identity",
        "a Lightning name is unique, so taking one that is taken must say so",
    ),
];

/// Response fields built from the request's own values for the caller's own object, so they
/// differ with the value named whoever holds it.
const DERIVED_RESPONSE_FIELDS: [(&str, &[&str]); 1] = [(
    "post /v0/backup/upload_url",
    // A's own key for the version named, and a URL signed for it.
    &["s3_key", "upload_url"],
)];

/// What a response tells its caller, without what differs by design: the time it was sent and
/// the fields in DERIVED_RESPONSE_FIELDS.
fn answer(
    operation: &str,
    (status, headers, body): (
        axum::http::StatusCode,
        axum::http::HeaderMap,
        axum::body::Bytes,
    ),
) -> (axum::http::StatusCode, Vec<String>, Value) {
    let headers = headers
        .iter()
        .filter(|(name, _)| !["date", "content-length"].contains(&name.as_str()))
        .map(|(name, value)| format!("{name}: {value:?}"))
        .collect();
    let mut body = serde_json::from_slice(&body)
        .unwrap_or_else(|_| Value::String(String::from_utf8_lossy(&body).into_owned()));
    for (op, fields) in DERIVED_RESPONSE_FIELDS {
        if op == operation {
            for field in fields {
                body.as_object_mut().unwrap().remove(*field);
            }
        }
    }
    (status, headers, body)
}

/// The authorization class: a gated request that names another user's object must not act on it.
/// User B holds one of every object a gated request can name; user A names each with A's token.
#[tokio::test]
async fn no_gated_operation_acts_on_an_object_another_user_holds() {
    use crate::db::{
        backup_repo::BackupRepository, heartbeat_repo::HeartbeatRepository,
        job_status_repo::JobStatusRepository, user_repo::UserRepository,
    };
    use crate::tests::common::{TestUser, setup_test_app, test_invoice};

    let (_, state, _guard) = setup_test_app().await;
    let app = crate::app_router(state.clone());
    let mut peer = 0;
    let [a, b] = [0x31, 0x32].map(|byte| TestUser::new_with_key(&[byte; 32]));
    let ark_b = "tark1qbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    let mut tx = state.db_pool.begin().await.unwrap();
    for (user, ln_address, ark_address) in
        [(&a, "a@localhost", None), (&b, "b@localhost", Some(ark_b))]
    {
        UserRepository::create(&mut tx, &user.pubkey().to_string(), ln_address, ark_address)
            .await
            .unwrap();
    }
    tx.commit().await.unwrap();
    let pubkey_b = b.pubkey().to_string();
    let token_b = b.access_token(&state);

    let (status, _, body) = exchange(
        &app,
        "post",
        "/v0/backup/v2/upload",
        Some(&token_b),
        Some(
            &json!({"format_version": 2, "encrypted_size": 1, "encrypted_sha256": "00".repeat(32)}),
        ),
        &mut peer,
    )
    .await;
    assert!(
        status.is_success(),
        "user B reserves a backup object: {status}"
    );
    let backup_id = serde_json::from_slice::<Value>(&body).unwrap()["backup_id"].clone();
    let backups = BackupRepository::new(&state.db_pool);
    assert!(
        backups
            .complete_object(&pubkey_b, backup_id.as_str().unwrap().parse().unwrap())
            .await
            .unwrap()
    );
    let hour = chrono::Utc::now().timestamp() + 3600;
    // A second schedule A does not list makes a prune that ignores the owner delete one of B's.
    let schedules = json!([
        {"schedule_id": "b-schedule", "next_run_at": hour},
        {"schedule_id": "b-other", "next_run_at": hour},
    ]);
    for (path, body) in [
        (
            "/v0/backup/complete_upload",
            json!({"s3_key": format!("{pubkey_b}/backup_v1.db"), "backup_version": 1, "backup_size": 1024}),
        ),
        (
            "/v0/recurring_payments/sync",
            json!({"schedules": schedules}),
        ),
    ] {
        let (status, _, _) =
            exchange(&app, "post", path, Some(&token_b), Some(&body), &mut peer).await;
        assert!(
            status.is_success(),
            "user B creates its object with {path}: {status}"
        );
    }
    let notification_id = HeartbeatRepository::new(&state.db_pool)
        .create_notification(&pubkey_b)
        .await
        .unwrap();
    let mut tx = state.db_pool.begin().await.unwrap();
    JobStatusRepository::create_with_k1_and_prune(
        &mut tx,
        &pubkey_b,
        "b-k1",
        &ReportType::Backup,
        &ReportStatus::Pending,
        None,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    // User B's identifier for every field in OBJECT_FIELDS, and a valid value for the rest.
    let named = json!({
        "s3_key": format!("{pubkey_b}/backup_v1.db"),
        "backup_version": 1,
        "backup_size": 2048,
        "backup_id": backup_id,
        "notification_id": notification_id,
        // The ID the LNURL callback pushed to B; B's answer is read back under B's key.
        "transaction_id": "b-transaction",
        "invoice": test_invoice(bitcoin::Network::Regtest, 1_000_000, 7),
        // Another due time for B's ID, so an update that ignores the owner moves B's schedule.
        "schedules": [{"schedule_id": "b-schedule", "next_run_at": hour + 3600}],
        "ark_address": ark_b,
        "ln_address": "b@localhost",
        "device_info": null,
        "notification_k1": "b-k1",
        "report_type": "backup",
        "status": "success",
        "error_message": "answered by another user",
        "username": "b",
        "nostr_pubkey": null,
    });
    let named = named.as_object().unwrap();
    let before = holdings(&state, &pubkey_b, named).await;
    // The same request naming objects nobody holds; fields that name no object keep B's values.
    let mut nobody = named.clone();
    let pubkey_nobody = TestUser::new_with_key(&[0x3f; 32]).pubkey().to_string();
    for (field, value) in [
        ("s3_key", json!(format!("{pubkey_nobody}/backup_v2.db"))),
        ("backup_version", json!(2)),
        ("backup_id", json!("00000000-0000-4000-8000-000000000000")),
        (
            "notification_id",
            json!("00000000-0000-4000-8000-000000000001"),
        ),
        ("transaction_id", json!("nobody-transaction")),
        (
            "schedules",
            json!([{"schedule_id": "nobody-schedule", "next_run_at": hour + 3600}]),
        ),
        (
            "ark_address",
            json!("tark1qnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn"),
        ),
        ("ln_address", json!("nobody@localhost")),
        ("notification_k1", json!("nobody-k1")),
        ("username", json!("nobody")),
    ] {
        assert!(nobody.insert(field.to_string(), value).is_some());
    }

    let failures = classification_failures(&document());
    assert!(failures.is_empty(), "{}", failures.join("\n"));

    let token_a = a.access_token(&state);
    for (operation, fields, may_succeed) in OBJECT_FIELDS {
        let (method, path) = operation.split_once(' ').unwrap();
        let request = |values: &serde_json::Map<String, Value>| {
            let body = fields
                .iter()
                // A nested field travels inside its top-level one.
                .filter(|field| !field.contains(['.', '[']))
                .map(|field| {
                    let value = values.get(*field);
                    assert!(
                        value.is_some(),
                        "user B holds nothing for {operation} to name in `{field}`"
                    );
                    (field.to_string(), value.unwrap().clone())
                })
                .collect();
            Value::Object(body)
        };
        let (status, headers, response) = exchange(
            &app,
            method,
            path,
            Some(&token_a),
            Some(&request(named)),
            &mut peer,
        )
        .await;
        let text = String::from_utf8_lossy(&response);
        assert!(
            may_succeed || !status.is_success(),
            "{operation} with user B's identifiers and user A's token succeeded: {status} {text}"
        );
        assert!(
            !status.is_server_error(),
            "{operation} with user B's identifiers failed inside the server: {status} {text}"
        );
        assert!(
            !text.contains(&pubkey_b),
            "{operation} with user B's identifiers revealed B's object to user A: {text}"
        );
        assert_eq!(
            holdings(&state, &pubkey_b, named).await,
            before,
            "{operation} with user B's identifiers and user A's token changed what B holds"
        );

        // The server must not tell A whether B's object exists: any difference from naming an
        // object nobody holds, in status, body or headers, is something A learns about B.
        if DISTINGUISHABLE.iter().any(|(op, _)| *op == operation) {
            continue;
        }
        let unheld = exchange(
            &app,
            method,
            path,
            Some(&token_a),
            Some(&request(&nobody)),
            &mut peer,
        )
        .await;
        assert_eq!(
            answer(operation, (status, headers, response)),
            answer(operation, unheld),
            "{operation} answers user A differently when it names user B's object than when it \
             names one nobody holds"
        );
    }
}

#[test]
fn all_schema_types_and_unsigned_fields_have_schemas() {
    let doc = document();
    let schemas = &doc["components"]["schemas"];
    let mut schema_types = 0;
    for file in [
        include_str!("types.rs"),
        include_str!("routes/public_api_v0.rs"),
    ] {
        for item in syn::parse_file(file).unwrap().items {
            let (name, attributes, fields) = match item {
                Item::Struct(item) => (item.ident.to_string(), item.attrs, Some(item.fields)),
                Item::Enum(item) => (item.ident.to_string(), item.attrs, None),
                _ => continue,
            };
            if attributes.iter().any(|attr| {
                attr.path().is_ident("derive")
                    && attr
                        .meta
                        .require_list()
                        .is_ok_and(|list| list.tokens.to_string().contains("ToSchema"))
            }) {
                schema_types += 1;
                assert!(schemas.get(&name).is_some(), "missing schema: {name}");
            }
            if let Some(fields) = fields {
                for field in fields {
                    let syn::Type::Path(ty) = field.ty else {
                        continue;
                    };
                    let kind = ty.path.segments.last().unwrap().ident.to_string();
                    if !["u64", "u32", "u16"].contains(&kind.as_str()) {
                        continue;
                    }
                    let field_name = field.ident.unwrap().to_string();
                    let property = match field_name.as_str() {
                        "max_sendable" => "maxSendable",
                        "min_sendable" => "minSendable",
                        "comment_allowed" => "commentAllowed",
                        _ => &field_name,
                    };
                    let schema = &schemas[&name]["properties"][property];
                    assert_eq!(schema["type"], "integer", "{name}.{property}");
                    assert_eq!(
                        schema["format"],
                        if kind == "u64" {
                            "int64"
                        } else if kind == "u32" {
                            "uint32"
                        } else {
                            "uint16"
                        }
                    );
                    assert_eq!(schema["minimum"].as_f64(), Some(0.0));
                }
            }
        }
    }
    assert_eq!(schema_types, 68);
    let amount = &doc["paths"]["/.well-known/lnurlp/{username}"]["get"]["parameters"][1]["schema"];
    assert_eq!(amount["format"], "int64");
    assert_eq!(amount["minimum"].as_f64(), Some(0.0));
}

#[test]
fn nullable_references_and_serde_names_survive_normalization() {
    let doc = document();
    let schemas = &doc["components"]["schemas"];
    for (name, field, kind) in [
        ("RegisterResponse", "event", "string"),
        ("RegisterPayload", "device_info", "object"),
        ("ReportLastLoginPayload", "device_info", "object"),
        ("SubmitSupportTicketPayload", "device_info", "object"),
        ("SubmitSupportTicketPayload", "attachment", "object"),
    ] {
        let property = &schemas[name]["properties"][field];
        assert_eq!(property["type"], json!([kind, "null"]), "{name}.{field}");
        assert!(property.get("$ref").is_none());
        assert!(
            !schemas[name]["required"]
                .as_array()
                .is_some_and(|required| required.contains(&json!(field)))
        );
    }
    assert_eq!(
        schemas["RegisterResponse"]["properties"]["event"]["enum"],
        json!(["REGISTERED", null])
    );
    assert_eq!(
        schemas["UserStatus"]["enum"],
        json!(["active", "inactive", "deregistered"])
    );
    assert_eq!(
        schemas["ReportType"]["enum"],
        json!(["maintenance", "backup"])
    );
    assert_eq!(
        schemas["ReportStatus"]["enum"],
        json!(["pending", "success", "failure", "timeout"])
    );
    assert_eq!(
        serde_json::to_value(ReportLastLoginPayload { device_info: None }).unwrap(),
        json!({})
    );
    assert_eq!(
        schemas["LnurlpResponse"]["oneOf"],
        json!([
            {"$ref":"#/components/schemas/LnurlpDefaultResponse"},
            {"$ref":"#/components/schemas/LnurlpInvoiceResponse"}
        ])
    );
}

#[test]
fn notification_variants_match_flat_wire_objects() {
    let doc = document();
    let schemas = &doc["components"]["schemas"];
    let union = &schemas["NotificationData"];
    let fixtures = [
        json!({"notification_type":"maintenance", "notification_k1":"k1"}),
        json!({"notification_type":"backup_trigger", "notification_k1":"k1"}),
        json!({"notification_type":"heartbeat", "notification_id":"id"}),
        json!({"notification_type":"recurring_payment_due", "due_count":u32::MAX}),
        json!({"notification_type":"lightning_invoice_request", "transaction_id":"id", "amount":i64::MAX}),
        json!({"notification_type":"lightning_claim_request", "payment_hash":"hash", "amount_sat":1u64 << 53}),
        json!({"notification_type":"lightning_send_finished", "notification_id":"id"}),
    ];
    assert_eq!(union["oneOf"].as_array().unwrap().len(), fixtures.len());
    for fixture in fixtures {
        let notification: NotificationData = serde_json::from_value(fixture.clone()).unwrap();
        assert_eq!(serde_json::to_value(&notification).unwrap(), fixture);
        let tag = notification.notification_type();
        let reference = union["discriminator"]["mapping"][tag].as_str().unwrap();
        let variant = &schemas[reference.strip_prefix("#/components/schemas/").unwrap()];
        assert!(
            union["oneOf"]
                .as_array()
                .unwrap()
                .contains(&json!({"$ref":reference}))
        );
        assert!(variant.get("allOf").is_none());
        assert_eq!(
            variant["properties"]["notification_type"]["enum"],
            json!([tag])
        );
        for field in variant["required"].as_array().unwrap() {
            assert!(fixture.get(field.as_str().unwrap()).is_some());
        }
        for field in fixture.as_object().unwrap().keys() {
            assert!(variant["properties"].get(field).is_some());
        }
    }
    let claim = &schemas["NotificationDataLightningClaimRequest"];
    assert!(
        !claim["required"]
            .as_array()
            .unwrap()
            .contains(&json!("amount_usd"))
    );
    assert_eq!(
        claim["properties"]["amount_usd"]["type"],
        json!(["string", "null"])
    );
}
