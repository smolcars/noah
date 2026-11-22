use super::*;
use crate::types::*;
use std::collections::BTreeSet;
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

/// Sends one request from its own peer address, so this audit never reaches a quota.
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
    let mut request = request.body(Body::empty()).unwrap();
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
    let code = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|body| body["code"].as_str().map(String::from));
    (status, code, headers)
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
                    assert_ne!(status, StatusCode::METHOD_NOT_ALLOWED, "{method} {path}");
                    assert_ne!(status, StatusCode::NOT_FOUND, "{method} {path}");
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
