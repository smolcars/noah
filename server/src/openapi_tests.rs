use super::*;
use crate::types::*;
use std::collections::{BTreeMap, BTreeSet};
use syn::{Expr, Item, Pat, Stmt, visit::Visit};

#[derive(Clone, Debug, PartialEq, Eq)]
struct MountedOperation {
    path: String,
    method: String,
    handler: String,
    bearer: bool,
    registered: bool,
}

fn string_literal(expr: &Expr) -> String {
    let Expr::Lit(literal) = expr else {
        panic!("expected literal")
    };
    let syn::Lit::Str(value) = &literal.lit else {
        panic!("expected string")
    };
    value.value()
}

fn path_name(expr: &Expr) -> String {
    let Expr::Path(path) = expr else {
        panic!("expected path")
    };
    path.path.segments.last().unwrap().ident.to_string()
}

// Inspect the real router expression without starting services or duplicating its route list.
// Unsupported routing syntax fails closed so a refactor must update this audit as well.
fn mounted_routes(
    expr: &Expr,
    bindings: &BTreeMap<String, Expr>,
    source: &syn::File,
) -> Vec<MountedOperation> {
    match expr {
        Expr::Path(_) => mounted_routes(&bindings[&path_name(expr)], bindings, source),
        Expr::Call(call) if path_name(&call.func) == "new" => vec![],
        Expr::Call(call) => {
            let name = path_name(&call.func);
            let function = source
                .items
                .iter()
                .find_map(|item| match item {
                    Item::Fn(function) if function.sig.ident == name => Some(function),
                    _ => None,
                })
                .expect("router helper must be defined in main.rs");
            let Some(Stmt::Expr(expr, None)) = function.block.stmts.last() else {
                panic!("expected router helper expression")
            };
            mounted_routes(expr, bindings, source)
        }
        Expr::MethodCall(call) => {
            let mut routes = mounted_routes(&call.receiver, bindings, source);
            match call.method.to_string().as_str() {
                "route" => {
                    let mut method_router = &call.args[1];
                    while let Expr::MethodCall(layer) = method_router {
                        assert_eq!(layer.method, "layer", "unsupported method router");
                        method_router = &layer.receiver;
                    }
                    let Expr::Call(method) = method_router else {
                        panic!("expected method router")
                    };
                    routes.push(MountedOperation {
                        path: string_literal(&call.args[0]),
                        method: path_name(&method.func),
                        handler: path_name(&method.args[0]),
                        bearer: false,
                        registered: false,
                    });
                }
                "merge" => routes.extend(mounted_routes(&call.args[0], bindings, source)),
                "nest" => {
                    let prefix = string_literal(&call.args[0]);
                    routes.extend(
                        mounted_routes(&call.args[1], bindings, source)
                            .into_iter()
                            .map(|mut route| {
                                route.path = format!("{prefix}{}", route.path);
                                route
                            }),
                    );
                }
                "layer" => {
                    let mut layer = &call.args[0];
                    if let Expr::MethodCall(clone) = layer
                        && clone.method == "clone"
                    {
                        layer = &clone.receiver;
                    }
                    if let Expr::Path(_) = layer {
                        layer = &bindings[&path_name(layer)];
                    }
                    if let Expr::Call(factory) = layer
                        && path_name(&factory.func) == "from_fn_with_state"
                    {
                        match path_name(&factory.args[1]).as_str() {
                            "auth_middleware" => {
                                routes.iter_mut().for_each(|route| route.bearer = true)
                            }
                            "user_exists_middleware" => {
                                routes.iter_mut().for_each(|route| route.registered = true)
                            }
                            other => panic!("unrecognized middleware: {other}"),
                        }
                    }
                }
                "with_state" => {}
                other => panic!("unrecognized router method: {other}"),
            }
            routes
        }
        _ => panic!("unsupported router expression"),
    }
}

#[test]
fn every_mounted_operation_and_auth_gate_matches() {
    let source = syn::parse_file(include_str!("main.rs")).unwrap();
    let start = source
        .items
        .iter()
        .find_map(|item| match item {
            Item::Fn(function) if function.sig.ident == "start_server" => Some(function),
            _ => None,
        })
        .unwrap();
    let bindings: BTreeMap<_, _> = start
        .block
        .stmts
        .iter()
        .filter_map(|stmt| {
            let Stmt::Local(local) = stmt else {
                return None;
            };
            let Pat::Ident(name) = &local.pat else {
                return None;
            };
            Some((name.ident.to_string(), *local.init.as_ref()?.expr.clone()))
        })
        .collect();
    let routes = mounted_routes(&bindings["app"], &bindings, &source);

    struct RouteCount(usize);
    impl<'ast> Visit<'ast> for RouteCount {
        fn visit_expr_method_call(&mut self, node: &'ast syn::ExprMethodCall) {
            if node.method == "route" {
                self.0 += 1;
            }
            syn::visit::visit_expr_method_call(self, node);
        }
    }
    let mut total = RouteCount(0);
    total.visit_file(&source);
    assert_eq!(
        routes.len(),
        total.0,
        "all route declarations must be audited"
    );
    assert_eq!(routes.len(), 37);
    let doc = document();
    let documented: BTreeSet<_> = doc["paths"]
        .as_object()
        .unwrap()
        .iter()
        .flat_map(|(path, item)| {
            item.as_object()
                .unwrap()
                .keys()
                .map(move |method| (path.clone(), method.clone()))
        })
        .collect();
    let mounted: BTreeSet<_> = routes
        .iter()
        .map(|route| (route.path.clone(), route.method.clone()))
        .collect();
    assert_eq!(documented, mounted, "no missing or phantom operations");
    for route in routes {
        let op = &doc["paths"][&route.path][&route.method];
        assert_eq!(op["operationId"], route.handler, "{}", route.path);
        assert_eq!(op.get("security").is_some(), route.bearer, "{}", route.path);
        if route.bearer {
            assert_eq!(op["security"], json!([{ "bearer": [] }]));
        }
        assert_eq!(
            op["description"]
                .as_str()
                .unwrap_or_default()
                .contains("a registered user (user-exists middleware)"),
            route.registered,
            "{}",
            route.path
        );
    }
    assert_eq!(
        doc["components"]["securitySchemes"]["bearer"],
        json!({
            "type": "http", "scheme": "bearer", "bearerFormat": "JWT"
        })
    );
}

#[test]
fn all_ts_exports_and_unsigned_fields_have_schemas() {
    let doc = document();
    let schemas = &doc["components"]["schemas"];
    let mut exports = 0;
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
            if attributes.iter().any(|attr| attr.path().is_ident("ts")) {
                exports += 1;
                assert!(schemas.get(&name).is_some(), "missing TS export: {name}");
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
    assert_eq!(exports, 59);
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
