use std::{sync::Arc, time::Duration};

use chrono::Utc;
use expo_push_notification_client::Priority;
use server_rpc::protos::mailbox_server::{
    ArkoorMessage, IncomingLightningPaymentMessage, MailboxMessage, mailbox_message::Message,
};
use tokio_util::sync::CancellationToken;

use crate::{
    AppStruct,
    db::{
        mailbox_authorization_repo::MailboxAuthorizationRepository,
        push_token_repo::PushTokenRepository,
    },
    mailbox_worker::{MailboxSessionContext, process_mailbox_message},
    tests::common::{TestUser, create_test_user, setup_test_app},
    types::NotificationData,
};

#[tokio::test]
async fn mailbox_claim_push_reaches_expo_and_unified_push() {
    let (_, state, _guard) = setup_test_app().await;
    let (sender, mut deliveries) = tokio::sync::mpsc::unbounded_channel();
    let state = Arc::new(AppStruct {
        push_deliveries: Some(sender),
        ..(*state).clone()
    });
    let user = TestUser::new();
    create_test_user(&state, &user, None).await;
    let pubkey = user.pubkey().to_string();
    let tokens = PushTokenRepository::new(&state.db_pool);
    let repo = MailboxAuthorizationRepository::new(&state.db_pool);
    let session = MailboxSessionContext {
        worker_id: "transport-test".to_string(),
        stream_idle_reconnect: Duration::from_secs(10),
        cancellation_token: CancellationToken::new(),
    };
    for token in ["ExpoPushToken[test-token]", "https://1.1.1.1/up/native"] {
        tokens.upsert(&pubkey, token).await.unwrap();
        repo.upsert(
            &pubkey,
            "deadbeef",
            "cafebabe",
            Utc::now().timestamp() + 3600,
        )
        .await
        .unwrap();
        let mailbox = repo
            .claim_runnable(
                Utc::now(),
                &session.worker_id,
                Utc::now() + chrono::Duration::minutes(1),
                1,
            )
            .await
            .unwrap()
            .remove(0);
        let message = MailboxMessage {
            checkpoint: 42,
            message: Some(Message::IncomingLightningPayment(
                IncomingLightningPaymentMessage {
                    payment_hash: vec![0x12; 32],
                    amount_msat: 42000,
                },
            )),
        };
        assert!(
            process_mailbox_message(&state, &mailbox, &session, &message, true)
                .await
                .unwrap()
        );
        {
            let (target, notification) = deliveries.try_recv().expect("expected claim delivery");
            assert_eq!(target, token);
            assert_eq!(notification.priority, Priority::High);
            assert!(notification.content_available);
            assert!(notification.title.is_none() && notification.body.is_none());
            let NotificationData::LightningClaimRequest(claim) =
                serde_json::from_str(&notification.data).unwrap()
            else {
                panic!("expected claim request");
            };
            assert_eq!(claim.amount_sat, 42);
            assert_eq!(claim.payment_hash, "12".repeat(32));
        }
        assert!(deliveries.try_recv().is_err());
        assert_eq!(
            repo.find_by_pubkey(&pubkey)
                .await
                .unwrap()
                .unwrap()
                .last_checkpoint,
            42
        );
    }
}

#[tokio::test]
async fn mailbox_arkoor_payment_sends_one_push_for_all_its_vtxos() {
    use ark::{ProtocolEncoding, test_util::VTXO_VECTORS};

    let (_, state, _guard) = setup_test_app().await;
    let (sender, mut deliveries) = tokio::sync::mpsc::unbounded_channel();
    let state = Arc::new(AppStruct {
        push_deliveries: Some(sender),
        ..(*state).clone()
    });
    let user = TestUser::new();
    create_test_user(&state, &user, None).await;
    let pubkey = user.pubkey().to_string();
    let token = "ExpoPushToken[test-token]";
    PushTokenRepository::new(&state.db_pool)
        .upsert(&pubkey, token)
        .await
        .unwrap();
    let repo = MailboxAuthorizationRepository::new(&state.db_pool);
    let session = MailboxSessionContext {
        worker_id: "transport-test".to_string(),
        stream_idle_reconnect: Duration::from_secs(10),
        cancellation_token: CancellationToken::new(),
    };
    repo.upsert(
        &pubkey,
        "deadbeef",
        "cafebabe",
        Utc::now().timestamp() + 3600,
    )
    .await
    .unwrap();
    let mailbox = repo
        .claim_runnable(
            Utc::now(),
            &session.worker_id,
            Utc::now() + chrono::Duration::minutes(1),
            1,
        )
        .await
        .unwrap()
        .remove(0);

    // One payment of three pubkey VTXOs (8,000 + 10,000 + 10,000 sats) plus an
    // HTLC VTXO that doesn't count towards it.
    let message = MailboxMessage {
        checkpoint: 43,
        message: Some(Message::Arkoor(ArkoorMessage {
            vtxos: [
                &VTXO_VECTORS.arkoor2_vtxo,
                &VTXO_VECTORS.round1_vtxo,
                &VTXO_VECTORS.round2_vtxo,
                &VTXO_VECTORS.arkoor3_vtxo,
            ]
            .iter()
            .map(|vtxo| vtxo.serialize())
            .collect(),
        })),
    };
    assert!(
        process_mailbox_message(&state, &mailbox, &session, &message, true)
            .await
            .unwrap()
    );

    let (target, notification) = deliveries.try_recv().expect("expected one receive push");
    assert_eq!(target, token);
    assert_eq!(notification.title.as_deref(), Some("Payment received"));
    assert_eq!(
        notification.body.as_deref(),
        Some("You received 28000 sats via Ark.")
    );
    assert_eq!(notification.data, "{}");
    assert_eq!(notification.priority, Priority::High);
    assert!(!notification.content_available);
    assert!(
        deliveries.try_recv().is_err(),
        "one payment must send one push"
    );
    assert_eq!(
        repo.find_by_pubkey(&pubkey)
            .await
            .unwrap()
            .unwrap()
            .last_checkpoint,
        43
    );
}

#[tokio::test]
async fn push_transport_policy_and_registration_replacement_are_preserved() {
    use crate::push::{PushNotificationData, send_expo_push_notification, send_push_notification};
    let (_, state, _guard) = setup_test_app().await;
    let (sender, mut deliveries) = tokio::sync::mpsc::unbounded_channel();
    let state = Arc::new(AppStruct {
        push_deliveries: Some(sender),
        ..(*state).clone()
    });
    let user = TestUser::new();
    create_test_user(&state, &user, None).await;
    let pubkey = user.pubkey().to_string();
    let tokens = PushTokenRepository::new(&state.db_pool);
    for token in ["ExpoPushToken[test-token]", "https://1.1.1.1/up/native"] {
        tokens.upsert(&pubkey, token).await.unwrap();
        assert_eq!(
            tokens.find_by_pubkey(&pubkey).await.unwrap().as_deref(),
            Some(token)
        );
        assert_eq!(
            tokens
                .find_all()
                .await
                .unwrap()
                .into_iter()
                .map(|target| target.push_token)
                .collect::<Vec<_>>(),
            vec![token.to_string()]
        );
        for kind in [
            "lightning_invoice_request",
            "lightning_claim_request",
            "maintenance",
            "heartbeat",
            "backup_trigger",
            "recurring_payment_due",
        ] {
            let push = PushNotificationData {
                title: None,
                body: None,
                data: serde_json::json!({"notification_type": kind}).to_string(),
                priority: Priority::High,
                content_available: true,
            };
            send_push_notification(state.clone(), push.clone(), Some(pubkey.clone()))
                .await
                .unwrap();
            assert_eq!(deliveries.try_recv().unwrap().0, token);
            assert!(deliveries.try_recv().is_err());
            // The existing Expo-only path must still exclude UnifiedPush for every type.
            send_expo_push_notification(state.clone(), push, Some(pubkey.clone()))
                .await
                .unwrap();
            if token.starts_with("ExpoPushToken[") {
                assert_eq!(deliveries.try_recv().unwrap().0, token);
            }
            assert!(deliveries.try_recv().is_err());
        }
    }
}

#[tracing_test::traced_test]
#[tokio::test]
async fn rejected_expo_send_logs_the_error_not_missing_tokens() {
    use axum::{Router, http::StatusCode, routing::post};
    use expo_push_notification_client::Expo;

    use crate::{
        push::send_unique_k1_notifications,
        types::{NotificationRequestData, RecurringPaymentDueNotification},
    };

    let (_, state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&state, &user, None).await;
    let pubkey = user.pubkey().to_string();
    PushTokenRepository::new(&state.db_pool)
        .upsert(&pubkey, "ExpoPushToken[device-secret]")
        .await
        .unwrap();

    // Stands in for Expo rejecting the access token.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let expo_url = format!("http://{}", listener.local_addr().unwrap());
    let expo_api = Router::new().route(
        "/--/api/v2/push/send",
        post(|| async { StatusCode::UNAUTHORIZED }),
    );
    tokio::spawn(async move { axum::serve(listener, expo_api).await });

    let receipts = send_unique_k1_notifications(
        Expo::new_with_base_url(Some("expo-access-secret".to_string()), &expo_url),
        state.clone(),
        NotificationRequestData::RecurringPaymentDue(RecurringPaymentDueNotification {
            due_count: 1,
        }),
        Some(pubkey),
    )
    .await
    .unwrap();

    assert!(receipts.is_empty());
    assert!(logs_contain("Failed to send push notification"));
    assert!(logs_contain("recurring_payment_due"));
    assert!(logs_contain("401 Unauthorized"));
    assert!(!logs_contain("no push tokens found"));
    assert!(!logs_contain("expo-access-secret"));
    assert!(!logs_contain("device-secret"));
}

mod expo_tickets {
    use std::collections::HashMap;

    use axum::{Json, Router, routing::post};
    use expo_push_notification_client::Expo;
    use serde_json::{Value, json};

    use super::*;
    use crate::{
        AppState,
        db::user_repo::UserRepository,
        push::{PushNotificationData, send_batched_notifications, send_unique_k1_notifications},
        types::{NotificationRequestData, RecurringPaymentDueNotification},
    };

    const EXPO_ACCESS_TOKEN: &str = "expo-access-secret";
    const DEAD_TOKEN: &str = "ExpoPushToken[dead-device-secret]";
    const LIVE_TOKEN: &str = "ExpoPushToken[live-device-secret]";

    #[derive(Clone, Copy)]
    enum SendPath {
        UniqueK1,
        Batched,
    }

    const SEND_PATHS: [SendPath; 2] = [SendPath::UniqueK1, SendPath::Batched];

    /// Stands in for Expo's send endpoint: each push token in a request gets the ticket
    /// `tickets` holds for it, or an ok ticket, in request order; `null` means no ticket.
    /// Test messages stay under the 1 KiB above which the Expo client gzips the body.
    async fn expo_answering_with(tickets: HashMap<&'static str, Value>) -> Expo {
        let tickets = Arc::new(tickets);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let expo_url = format!("http://{}", listener.local_addr().unwrap());
        let expo_api = Router::new().route(
            "/--/api/v2/push/send",
            post(move |Json(request): Json<Value>| {
                let tickets = tickets.clone();
                async move {
                    // The client sends one message as an object, several as an array.
                    let messages = match request {
                        Value::Array(messages) => messages,
                        message => vec![message],
                    };
                    let data: Vec<Value> = messages
                        .iter()
                        .flat_map(|message| message["to"].as_array().unwrap().clone())
                        .filter_map(|token| match tickets.get(token.as_str().unwrap()) {
                            Some(Value::Null) => None,
                            Some(ticket) => Some(ticket.clone()),
                            None => Some(json!({ "status": "ok", "id": "receipt-id" })),
                        })
                        .collect();
                    Json(json!({ "data": data }))
                }
            }),
        );
        tokio::spawn(async move { axum::serve(listener, expo_api).await });
        Expo::new_with_base_url(Some(EXPO_ACCESS_TOKEN.to_string()), &expo_url)
    }

    /// An error ticket shaped like Expo's, whose message and details quote the token.
    fn error_ticket(token: &str, code: Option<&str>) -> Value {
        let mut ticket = json!({
            "status": "error",
            "message": format!("\"{token}\" is not a registered push notification recipient"),
        });
        if let Some(code) = code {
            ticket["details"] = json!({ "error": code, "expoPushToken": token });
        }
        ticket
    }

    /// Registers a user with one push token and returns its pubkey.
    async fn user_with_push_token(state: &AppState, key: u8, push_token: &str) -> String {
        let pubkey = TestUser::new_with_key(&[key; 32]).pubkey().to_string();
        let mut tx = state.db_pool.begin().await.unwrap();
        UserRepository::create(&mut tx, &pubkey, &format!("user{key}@localhost"), None)
            .await
            .unwrap();
        tx.commit().await.unwrap();
        PushTokenRepository::new(&state.db_pool)
            .upsert(&pubkey, push_token)
            .await
            .unwrap();
        pubkey
    }

    /// Sends one notification to `pubkey`'s device, or to every device. Returns how many
    /// devices the per-device (unique k1) path reports as dispatched; the batched path
    /// reports nothing.
    async fn send(
        path: SendPath,
        expo: Expo,
        state: &AppState,
        pubkey: Option<&str>,
    ) -> Option<usize> {
        match path {
            SendPath::UniqueK1 => Some(
                send_unique_k1_notifications(
                    expo,
                    state.clone(),
                    NotificationRequestData::RecurringPaymentDue(RecurringPaymentDueNotification {
                        due_count: 1,
                    }),
                    pubkey.map(str::to_string),
                )
                .await
                .unwrap()
                .len(),
            ),
            SendPath::Batched => {
                send_batched_notifications(
                    expo,
                    state.clone(),
                    PushNotificationData {
                        title: None,
                        body: None,
                        data: json!({ "notification_type": "maintenance" }).to_string(),
                        priority: Priority::High,
                        content_available: true,
                    },
                    pubkey.map(str::to_string),
                    true,
                )
                .await
                .unwrap();
                None
            }
        }
    }

    fn count(lines: &[&str], parts: &[&str]) -> usize {
        lines
            .iter()
            .filter(|line| parts.iter().all(|part| line.contains(part)))
            .count()
    }

    fn assert_no_secrets_logged(lines: &[&str]) -> Result<(), String> {
        for secret in [EXPO_ACCESS_TOKEN, "device-secret"] {
            if count(lines, &[secret]) > 0 {
                return Err(format!("logs contain {secret}"));
            }
        }
        Ok(())
    }

    /// Tokens are kept: a device that is gone misses its heartbeats and is deregistered.
    #[tracing_test::traced_test]
    #[tokio::test]
    async fn device_not_registered_is_logged_and_keeps_the_token() {
        let (_, state, _guard) = setup_test_app().await;
        let dead = user_with_push_token(&state, 1, DEAD_TOKEN).await;
        let live = user_with_push_token(&state, 2, LIVE_TOKEN).await;
        let tokens = PushTokenRepository::new(&state.db_pool);

        for path in SEND_PATHS {
            let expo = expo_answering_with(HashMap::from([(
                DEAD_TOKEN,
                error_ticket(DEAD_TOKEN, Some("DeviceNotRegistered")),
            )]))
            .await;

            // Expo accepted the request, so the device still counts as dispatched.
            assert!(matches!(
                send(path, expo, &state, Some(&dead)).await,
                None | Some(1)
            ));
            assert_eq!(
                tokens.find_by_pubkey(&dead).await.unwrap().as_deref(),
                Some(DEAD_TOKEN)
            );
            assert_eq!(
                tokens.find_by_pubkey(&live).await.unwrap().as_deref(),
                Some(LIVE_TOKEN)
            );
        }

        logs_assert(|lines| {
            let warned = [
                " WARN ",
                "Expo returned an error ticket",
                "error_code=\"DeviceNotRegistered\"",
                &dead,
            ];
            if count(lines, &warned) != 2 {
                return Err("expected one warning per send path".to_string());
            }
            if count(lines, &["ERROR"]) > 0 || count(lines, &[live.as_str()]) > 0 {
                return Err("expected only the dead device's warnings".to_string());
            }
            assert_no_secrets_logged(lines)
        });
    }

    #[tracing_test::traced_test]
    #[tokio::test]
    async fn invalid_credentials_is_logged_as_an_error_and_keeps_tokens() {
        let (_, state, _guard) = setup_test_app().await;
        let first = user_with_push_token(&state, 1, DEAD_TOKEN).await;
        let second = user_with_push_token(&state, 2, LIVE_TOKEN).await;
        let tokens = PushTokenRepository::new(&state.db_pool);

        for path in SEND_PATHS {
            let expo = expo_answering_with(HashMap::from([
                (
                    DEAD_TOKEN,
                    error_ticket(DEAD_TOKEN, Some("InvalidCredentials")),
                ),
                (
                    LIVE_TOKEN,
                    error_ticket(LIVE_TOKEN, Some("InvalidCredentials")),
                ),
            ]))
            .await;

            assert!(matches!(
                send(path, expo, &state, None).await,
                None | Some(2)
            ));
            assert_eq!(
                tokens.find_by_pubkey(&first).await.unwrap().as_deref(),
                Some(DEAD_TOKEN)
            );
            assert_eq!(
                tokens.find_by_pubkey(&second).await.unwrap().as_deref(),
                Some(LIVE_TOKEN)
            );
        }

        logs_assert(|lines| {
            let rejected = [
                "ERROR",
                "Expo rejected the push credentials",
                "InvalidCredentials",
            ];
            if count(lines, &rejected) != 4 {
                return Err("expected one error per device and send path".to_string());
            }
            assert_no_secrets_logged(lines)
        });
    }

    #[tracing_test::traced_test]
    #[tokio::test]
    async fn other_ticket_errors_only_warn() {
        let (_, state, _guard) = setup_test_app().await;
        let cases = [
            (
                "ExpoPushToken[too-big-device-secret]",
                Some("MessageTooBig"),
            ),
            (
                "ExpoPushToken[rate-device-secret]",
                Some("MessageRateExceeded"),
            ),
            ("ExpoPushToken[no-code-device-secret]", None),
        ];
        let mut users = vec![];
        for (key, (token, _)) in (1..).zip(cases) {
            users.push((user_with_push_token(&state, key, token).await, token));
        }
        let tokens = PushTokenRepository::new(&state.db_pool);

        for path in SEND_PATHS {
            let expo = expo_answering_with(
                cases
                    .iter()
                    .map(|(token, code)| (*token, error_ticket(token, *code)))
                    .collect(),
            )
            .await;

            assert!(matches!(
                send(path, expo, &state, None).await,
                None | Some(3)
            ));
            for (pubkey, token) in &users {
                assert_eq!(
                    tokens.find_by_pubkey(pubkey).await.unwrap().as_deref(),
                    Some(*token)
                );
            }
        }

        logs_assert(|lines| {
            for code in ["MessageTooBig", "MessageRateExceeded", "unknown"] {
                let error_code = format!("error_code=\"{code}\"");
                let warned = [" WARN ", "Expo returned an error ticket", &error_code];
                if count(lines, &warned) != 2 {
                    return Err(format!("expected one {code} warning per send path"));
                }
            }
            // The per-device path logs each code with its device's pubkey.
            for ((pubkey, _), (_, code)) in users.iter().zip(cases) {
                let error_code = format!("error_code=\"{}\"", code.unwrap_or("unknown"));
                if count(lines, &[&error_code, pubkey]) != 1 {
                    return Err(format!("expected {error_code} for {pubkey}"));
                }
            }
            if count(lines, &["ERROR"]) > 0 {
                return Err("expected warnings only".to_string());
            }
            assert_no_secrets_logged(lines)
        });
    }

    /// The client can't parse a response holding an error code it doesn't know, so the
    /// send fails as a whole and is logged as such.
    #[tracing_test::traced_test]
    #[tokio::test]
    async fn unrecognised_ticket_error_fails_the_send() {
        let (_, state, _guard) = setup_test_app().await;
        let first = user_with_push_token(&state, 1, DEAD_TOKEN).await;
        let second = user_with_push_token(&state, 2, LIVE_TOKEN).await;
        let tokens = PushTokenRepository::new(&state.db_pool);

        for path in SEND_PATHS {
            let expo = expo_answering_with(HashMap::from([(
                DEAD_TOKEN,
                error_ticket(DEAD_TOKEN, Some("SomeFutureError")),
            )]))
            .await;

            // Only the per-device path sees the other device's send succeed.
            assert!(matches!(
                send(path, expo, &state, None).await,
                None | Some(1)
            ));
            assert_eq!(
                tokens.find_by_pubkey(&first).await.unwrap().as_deref(),
                Some(DEAD_TOKEN)
            );
            assert_eq!(
                tokens.find_by_pubkey(&second).await.unwrap().as_deref(),
                Some(LIVE_TOKEN)
            );
        }

        logs_assert(|lines| {
            if count(lines, &["ERROR", "Failed to send push notification"]) != 2 {
                return Err("expected one send failure per send path".to_string());
            }
            assert_no_secrets_logged(lines)
        });
    }

    #[tracing_test::traced_test]
    #[tokio::test]
    async fn ok_tickets_change_nothing() {
        let (_, state, _guard) = setup_test_app().await;
        let first = user_with_push_token(&state, 1, DEAD_TOKEN).await;
        let second = user_with_push_token(&state, 2, LIVE_TOKEN).await;
        let tokens = PushTokenRepository::new(&state.db_pool);

        for path in SEND_PATHS {
            let expo = expo_answering_with(HashMap::new()).await;

            assert!(matches!(
                send(path, expo, &state, None).await,
                None | Some(2)
            ));
            assert_eq!(
                tokens.find_by_pubkey(&first).await.unwrap().as_deref(),
                Some(DEAD_TOKEN)
            );
            assert_eq!(
                tokens.find_by_pubkey(&second).await.unwrap().as_deref(),
                Some(LIVE_TOKEN)
            );
        }

        logs_assert(|lines| {
            if count(lines, &["ERROR"]) > 0 || count(lines, &[" WARN "]) > 0 {
                return Err("expected no warnings or errors".to_string());
            }
            assert_no_secrets_logged(lines)
        });
    }

    /// Expo keeps getting a visible notification's title and body beside its data;
    /// only UnifiedPush gets them as a typed payload.
    #[tokio::test]
    async fn visible_notification_reaches_expo_with_its_title_and_body() {
        let (_, state, _guard) = setup_test_app().await;
        let pubkey = user_with_push_token(&state, 1, LIVE_TOKEN).await;
        let (sender, mut requests) = tokio::sync::mpsc::unbounded_channel();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let expo_url = format!("http://{}", listener.local_addr().unwrap());
        let expo_api = Router::new().route(
            "/--/api/v2/push/send",
            post(move |Json(request): Json<Value>| {
                let sender = sender.clone();
                async move {
                    sender.send(request).unwrap();
                    Json(json!({ "data": [{ "status": "ok", "id": "receipt-id" }] }))
                }
            }),
        );
        tokio::spawn(async move { axum::serve(listener, expo_api).await });

        send_batched_notifications(
            Expo::new_with_base_url(Some(EXPO_ACCESS_TOKEN.to_string()), &expo_url),
            state.clone(),
            PushNotificationData {
                title: Some("Payment received".to_string()),
                body: Some("You received 10000 sats via Ark.".to_string()),
                data: "{}".to_string(),
                priority: Priority::High,
                content_available: false,
            },
            Some(pubkey),
            true,
        )
        .await
        .unwrap();

        // The client sends one message as an object.
        let request = requests.try_recv().expect("expected one Expo request");
        assert_eq!(request["to"], json!([LIVE_TOKEN]));
        assert_eq!(request["title"], "Payment received");
        assert_eq!(request["body"], "You received 10000 sats via Ark.");
        assert_eq!(request["data"], "{}");
        assert!(requests.try_recv().is_err());
    }

    /// Tickets that can't be matched to their tokens one by one are skipped.
    #[tracing_test::traced_test]
    #[tokio::test]
    async fn tickets_that_do_not_match_the_tokens_are_skipped() {
        let (_, state, _guard) = setup_test_app().await;
        let first = user_with_push_token(&state, 1, DEAD_TOKEN).await;
        let second = user_with_push_token(&state, 2, LIVE_TOKEN).await;
        let tokens = PushTokenRepository::new(&state.db_pool);
        // One ticket comes back for the batched send's two tokens.
        let expo = expo_answering_with(HashMap::from([
            (
                DEAD_TOKEN,
                error_ticket(DEAD_TOKEN, Some("DeviceNotRegistered")),
            ),
            (LIVE_TOKEN, Value::Null),
        ]))
        .await;

        send(SendPath::Batched, expo, &state, None).await;
        assert_eq!(
            tokens.find_by_pubkey(&first).await.unwrap().as_deref(),
            Some(DEAD_TOKEN)
        );
        assert_eq!(
            tokens.find_by_pubkey(&second).await.unwrap().as_deref(),
            Some(LIVE_TOKEN)
        );

        logs_assert(|lines| {
            let mismatch = [" WARN ", "different number of tickets", "tickets=1"];
            if count(lines, &mismatch) != 1 {
                return Err("expected one ticket count warning".to_string());
            }
            if count(lines, &["Expo returned an error ticket"]) > 0 {
                return Err("a ticket was logged".to_string());
            }
            assert_no_secrets_logged(lines)
        });
    }
}

/// Direct APNs and FCM sends, against stand-ins for both services.
mod direct_push {
    use std::sync::{
        Mutex,
        atomic::{AtomicUsize, Ordering},
    };

    use axum::{
        Json, Router,
        body::Bytes,
        http::{HeaderMap, StatusCode, Uri},
        response::IntoResponse,
        routing::{any, post},
    };
    use expo_push_notification_client::Expo;
    use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode, decode_header};
    use serde_json::{Value, json};

    use super::*;
    use crate::{
        AppState,
        config::Config,
        db::user_repo::UserRepository,
        direct_push::DirectPush,
        push::{PushNotificationData, send_batched_notifications, send_unique_k1_notifications},
        types::{NotificationRequestData, PushProvider, RecurringPaymentDueNotification},
    };

    // Throwaway keys generated for these tests; they sign nothing real.
    const APNS_TEST_KEY: &str = "-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgbtOHWZEbIdnYWJ9G
83+BNfIy3Wg3HhFY13ElOwoScvehRANCAASq/cnMCf1AAC81JzUqAH3HOCNVu7XT
qziEeeK9iNKHR4poDdxItDzYzWr/n7s94kty3wVy+Al49ZmyQsOJchTF
-----END PRIVATE KEY-----";
    const APNS_TEST_PUBLIC_KEY: &str = "-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEqv3JzAn9QAAvNSc1KgB9xzgjVbu1
06s4hHnivYjSh0eKaA3cSLQ82M1q/5+7PeJLct8FcvgJePWZskLDiXIUxQ==
-----END PUBLIC KEY-----";
    const FCM_TEST_KEY: &str = "-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCvqqqs3zgn1DjN
OKiIt8pRmahlnUCsFBhuU0gJUsInN2+NHNf40EmNsFkxXnsQH8EbAkBvCiLzYeVf
lGDHR70bMF9E8r8cjtgdPf/2BJsURobRzkU92PxDdpndmDepQF9erfXXpUDK3qya
excvAIvi9aW1uiV3hzk9LQAXMerq+NR0xtFzPDpGlReaRAU8ybLgfRdXeuWUFp+E
4gf1/olalVdRDIGlagXES9yrf8CoA1WpOI8/QVpBiJdyRvMldLMAl961PA0vvAn5
xSCe7UwPjJEN3TKhmL/KvpM65OQeXpDot8Mrp80WxHUyruQGiiKkWcnVahQ/LYIt
JAQPFb2XAgMBAAECggEAEnUzfM7xlsHGUXida3bVqTBPkOnD31uMhknK58E3NFQE
rRugQn0ZlWviW1J1n31EAZisI4lkSxV4uqEbxXoEupl2ZodpIaY4EA6GhBekzIMX
7G3+OYrrVWgIr8+BiGUCqwSgBc96g4vVMArfUJQqnqeNXg8X8RDCKMA/N7C04d55
o+e/sJbjEO/iTH05KF7ufl2/FZWhr5WGRZQGHt2YZRMsV7g/h5Vtjht2fFHAGm2l
1kushoLtvY8UXuvufG3IJtU6hpV/n/eXSOknDhC1xhoyqzmq9zWXwPX2nR5ox29A
BBogTO7Mqh0ca/VVubDIIxacrOcPcvSkERiLdkU8hQKBgQDqZR8ee2jx/GEAiB+U
Oqs+Z2PYUobCJLZ5ab7sE2XknyxYzQlkGcnLwfYMDoBgWM8g2ji9vmNjZSE+k2qB
f7GCwucd2sINJSeLafVZc827NkOqumI8+aYRsAIrYaDeIIV+SvsoKDSU2mc+MLkr
KUAxvTiOLO7ybdhMa6rrobUrHQKBgQC/28SJtXBFUO2QkRKdxVbQBsLbcyQ1lND9
UF5MT5tXaX8cXA0B4V4fXwhpOpC8t05RmOhnduMSpO5ygNtJ6Vz7g9VlQKj4bxVf
x61UXGV7Mc1EYMOv8w9TV+6T/GVq3K3//xn+xeU0LKjPmAkzoxuwKNXWJ57KFPCy
m9Ju77o5QwKBgQCQLVNO9KpA7vTb1t9V1iCvaflIfrrVgK+dTyZZIDV0w1QL61Lz
d8lgxBYMpH7ixL3jf1eXnxyTbwlb7GF+9oTB4hE3k+/5FfmVD8ATHeBWX7KPucXj
q8wy5QjwlkTVXTu/y29h7Bkr1ova/S1pFx6deb7n9UulY1vA5hlkhzthIQKBgBID
RyOKtly7qWI+TJolce4cBGVoHQkuzKRLzF6rxrDhRb7a4ZnAl/6oBHkkt3wQVbkR
exhjsBQLChwT/wKkd0dh7EISXQreGwqgC6T0+84iMdxunVDCqDix88NPGhJ7X5a+
U6l42PkaDEtZQdvoP2w9mT3/AEiGyMZsHMivlzNTAoGBALzmptklRZXdC7mzHSJQ
i7foAyk5IoGiLUF9uEVfDEfh8Es/PrPs8PFdP7pAJi4ezlL+OvVtn7htYjAAV5Ts
g1sELP0xF63OK73/Oiudkwar7u9ruhlxXajsTLhzo6k/7nx1GyZAsHRvB0ahzmwI
x2TheTi/KO+SQTU6rRx3QfLf
-----END PRIVATE KEY-----";
    const FCM_TEST_PUBLIC_KEY: &str = "-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAr6qqrN84J9Q4zTioiLfK
UZmoZZ1ArBQYblNICVLCJzdvjRzX+NBJjbBZMV57EB/BGwJAbwoi82HlX5Rgx0e9
GzBfRPK/HI7YHT3/9gSbFEaG0c5FPdj8Q3aZ3Zg3qUBfXq3116VAyt6smnsXLwCL
4vWltbold4c5PS0AFzHq6vjUdMbRczw6RpUXmkQFPMmy4H0XV3rllBafhOIH9f6J
WpVXUQyBpWoFxEvcq3/AqANVqTiPP0FaQYiXckbzJXSzAJfetTwNL7wJ+cUgnu1M
D4yRDd0yoZi/yr6TOuTkHl6Q6LfDK6fNFsR1Mq7kBooipFnJ1WoUPy2CLSQEDxW9
lwIDAQAB
-----END PUBLIC KEY-----";
    const APNS_TOKEN: &str = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    const FCM_TOKEN: &str = "fcm-device-secret:APA91b";
    const GOOGLE_ACCESS_TOKEN: &str = "google-access-secret";

    #[derive(Clone, Debug)]
    struct Received {
        path: String,
        headers: HeaderMap,
        body: Value,
    }

    /// Answers each request with the next status and JSON body, then with 200 once the
    /// list is used up, and records what it received.
    #[derive(Clone, Default)]
    struct Provider {
        answers: Arc<Mutex<Vec<(StatusCode, Value)>>>,
        received: Arc<Mutex<Vec<Received>>>,
        token_requests: Arc<Mutex<Vec<String>>>,
        stall_token_requests: bool,
    }

    impl Provider {
        fn answering(answers: Vec<(StatusCode, Value)>) -> Self {
            Self {
                answers: Arc::new(Mutex::new(answers)),
                ..Self::default()
            }
        }

        fn received(&self) -> Vec<Received> {
            self.received.lock().unwrap().clone()
        }

        fn token_requests(&self) -> Vec<String> {
            self.token_requests.lock().unwrap().clone()
        }

        async fn start(&self) -> String {
            let provider = self.clone();
            let oauth = self.clone();
            let router = Router::new()
                .route(
                    "/token",
                    post(move |body: String| {
                        let oauth = oauth.clone();
                        async move {
                            if oauth.stall_token_requests {
                                std::future::pending::<()>().await;
                            }
                            oauth.token_requests.lock().unwrap().push(body);
                            Json(json!({
                                "access_token": GOOGLE_ACCESS_TOKEN,
                                "expires_in": 3599,
                            }))
                        }
                    }),
                )
                .fallback(any(move |uri: Uri, headers: HeaderMap, body: Bytes| {
                    let provider = provider.clone();
                    async move {
                        provider.received.lock().unwrap().push(Received {
                            path: uri.path().to_string(),
                            headers,
                            body: serde_json::from_slice(&body).unwrap(),
                        });
                        let mut answers = provider.answers.lock().unwrap();
                        if answers.is_empty() {
                            (StatusCode::OK, Json(json!({}))).into_response()
                        } else {
                            let (status, body) = answers.remove(0);
                            (status, Json(body)).into_response()
                        }
                    }
                }));
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let url = format!("http://{}", listener.local_addr().unwrap());
            tokio::spawn(async move { axum::serve(listener, router).await });
            url
        }
    }

    fn config_with_credentials(token_uri: &str) -> Config {
        Config {
            apns_key_p8: Some(APNS_TEST_KEY.to_string()),
            apns_key_id: Some("KEYID12345".to_string()),
            apns_team_id: Some("TEAMID1234".to_string()),
            apns_topic: Some("com.noahwallet.regtest".to_string()),
            fcm_service_account_json: Some(
                json!({
                    "project_id": "noah-test",
                    "client_email": "push@noah-test.iam.gserviceaccount.com",
                    "private_key": FCM_TEST_KEY,
                    "token_uri": token_uri,
                })
                .to_string(),
            ),
            ..TestUser::get_config()
        }
    }

    /// An app whose direct pushes go to `provider`.
    async fn state_using(state: &AppState, provider: &Provider) -> AppState {
        state_using_deadline(state, provider, None).await
    }

    async fn state_using_deadline(
        state: &AppState,
        provider: &Provider,
        deadline: Option<Duration>,
    ) -> AppState {
        let url = provider.start().await;
        let mut direct_push =
            DirectPush::from_config(&config_with_credentials(&format!("{url}/token"))).unwrap();
        direct_push.use_urls(&url, &url);
        if let Some(deadline) = deadline {
            direct_push.use_send_deadline(deadline);
        }
        Arc::new(AppStruct {
            direct_push: Arc::new(direct_push),
            ..(**state).clone()
        })
    }

    /// An Expo stand-in that counts its requests, for proving a direct send skips Expo.
    async fn counting_expo() -> (Expo, Arc<AtomicUsize>) {
        let requests = Arc::new(AtomicUsize::new(0));
        let counter = requests.clone();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let router = Router::new().fallback(any(move || {
            let counter = counter.clone();
            async move {
                counter.fetch_add(1, Ordering::SeqCst);
                StatusCode::OK
            }
        }));
        tokio::spawn(async move { axum::serve(listener, router).await });
        (
            Expo::new_with_base_url(Some("expo-access-secret".to_string()), &url),
            requests,
        )
    }

    async fn user_with_target(
        state: &AppState,
        key: u8,
        token: &str,
        provider: PushProvider,
        apns_sandbox: bool,
    ) -> String {
        let pubkey = TestUser::new_with_key(&[key; 32]).pubkey().to_string();
        let mut tx = state.db_pool.begin().await.unwrap();
        UserRepository::create(&mut tx, &pubkey, &format!("user{key}@localhost"), None)
            .await
            .unwrap();
        tx.commit().await.unwrap();
        PushTokenRepository::new(&state.db_pool)
            .upsert_with_provider(&pubkey, token, Some(provider), apns_sandbox)
            .await
            .unwrap();
        pubkey
    }

    fn claim_push() -> PushNotificationData {
        PushNotificationData {
            title: None,
            body: None,
            data: json!({ "notification_type": "lightning_claim_request" }).to_string(),
            priority: Priority::High,
            content_available: true,
        }
    }

    async fn send_batched(state: &AppState, push: PushNotificationData, pubkey: &str) {
        let (expo, expo_requests) = counting_expo().await;
        send_batched_notifications(expo, state.clone(), push, Some(pubkey.to_string()), true)
            .await
            .unwrap();
        assert_eq!(expo_requests.load(Ordering::SeqCst), 0);
    }

    async fn send_unique(state: &AppState, pubkey: &str) -> usize {
        let (expo, expo_requests) = counting_expo().await;
        let dispatched = send_unique_k1_notifications(
            expo,
            state.clone(),
            NotificationRequestData::RecurringPaymentDue(RecurringPaymentDueNotification {
                due_count: 1,
            }),
            Some(pubkey.to_string()),
        )
        .await
        .unwrap()
        .len();
        assert_eq!(expo_requests.load(Ordering::SeqCst), 0);
        dispatched
    }

    fn header<'a>(received: &'a Received, name: &str) -> &'a str {
        received.headers.get(name).unwrap().to_str().unwrap()
    }

    #[tokio::test]
    async fn apns_gets_a_signed_request_for_the_tokens_environment() {
        let (_, state, _guard) = setup_test_app().await;
        let provider = Provider::default();
        let state = state_using(&state, &provider).await;
        let production = user_with_target(&state, 1, APNS_TOKEN, PushProvider::Apns, false).await;
        let sandbox = user_with_target(&state, 2, APNS_TOKEN, PushProvider::Apns, true).await;

        send_batched(&state, claim_push(), &production).await;
        assert_eq!(send_unique(&state, &sandbox).await, 1);

        let received = provider.received();
        assert_eq!(received.len(), 2);
        assert_eq!(
            received[0].path,
            format!("/production/3/device/{APNS_TOKEN}")
        );
        assert_eq!(received[1].path, format!("/sandbox/3/device/{APNS_TOKEN}"));
        for request in &received {
            assert_eq!(header(request, "apns-topic"), "com.noahwallet.regtest");
            assert_eq!(header(request, "apns-push-type"), "background");
            assert_eq!(header(request, "apns-priority"), "5");
            assert!(header(request, "apns-expiration").parse::<u64>().unwrap() > 0);
            assert_eq!(request.body["aps"], json!({ "content-available": 1 }));
            assert!(
                request.body["body"]
                    .as_str()
                    .unwrap()
                    .contains("notification_type")
            );
        }

        // The token is reused and verifies against the key's public half.
        let provider_token = header(&received[0], "authorization")
            .strip_prefix("Bearer ")
            .unwrap();
        assert_eq!(
            header(&received[1], "authorization"),
            format!("Bearer {provider_token}")
        );
        let token_header = decode_header(provider_token).unwrap();
        assert_eq!(token_header.alg, Algorithm::ES256);
        assert_eq!(token_header.kid.as_deref(), Some("KEYID12345"));
        let mut validation = Validation::new(Algorithm::ES256);
        validation.required_spec_claims.clear();
        validation.validate_exp = false;
        let claims = decode::<Value>(
            provider_token,
            &DecodingKey::from_ec_pem(APNS_TEST_PUBLIC_KEY.as_bytes()).unwrap(),
            &validation,
        )
        .unwrap()
        .claims;
        assert_eq!(claims["iss"], "TEAMID1234");
        assert!(claims["iat"].as_u64().unwrap() > 0);
    }

    #[tokio::test]
    async fn fcm_gets_a_data_message_and_shares_one_access_token() {
        let (_, state, _guard) = setup_test_app().await;
        let provider = Provider::default();
        let state = state_using(&state, &provider).await;
        let pubkey = user_with_target(&state, 1, FCM_TOKEN, PushProvider::Fcm, false).await;

        send_batched(&state, claim_push(), &pubkey).await;
        send_batched(
            &state,
            PushNotificationData {
                title: Some("Payment received".to_string()),
                body: Some("You received 1 sat".to_string()),
                data: "{}".to_string(),
                priority: Priority::Normal,
                content_available: false,
            },
            &pubkey,
        )
        .await;

        let received = provider.received();
        assert_eq!(received.len(), 2);
        for request in &received {
            assert_eq!(request.path, "/v1/projects/noah-test/messages:send");
            assert_eq!(
                header(request, "authorization"),
                format!("Bearer {GOOGLE_ACCESS_TOKEN}")
            );
            assert_eq!(request.body["message"]["token"], FCM_TOKEN);
            assert!(request.body["message"].get("notification").is_none());
        }
        assert_eq!(received[0].body["message"]["android"]["priority"], "HIGH");
        assert!(
            received[0].body["message"]["data"]["body"]
                .as_str()
                .unwrap()
                .contains("lightning_claim_request")
        );
        assert_eq!(received[1].body["message"]["android"]["priority"], "NORMAL");
        assert_eq!(
            received[1].body["message"]["data"]["title"],
            "Payment received"
        );
        assert_eq!(
            received[1].body["message"]["data"]["message"],
            "You received 1 sat"
        );

        let token_requests = provider.token_requests();
        assert_eq!(token_requests.len(), 1);
        let form: std::collections::HashMap<String, String> =
            reqwest::Url::parse(&format!("http://localhost/?{}", token_requests[0]))
                .unwrap()
                .query_pairs()
                .into_owned()
                .collect();
        assert_eq!(
            form["grant_type"],
            "urn:ietf:params:oauth:grant-type:jwt-bearer"
        );
        let mut validation = Validation::new(Algorithm::RS256);
        validation.validate_aud = false;
        let claims = decode::<Value>(
            &form["assertion"],
            &DecodingKey::from_rsa_pem(FCM_TEST_PUBLIC_KEY.as_bytes()).unwrap(),
            &validation,
        )
        .unwrap()
        .claims;
        assert_eq!(claims["iss"], "push@noah-test.iam.gserviceaccount.com");
        assert_eq!(
            claims["scope"],
            "https://www.googleapis.com/auth/firebase.messaging"
        );
    }

    #[tracing_test::traced_test]
    #[tokio::test]
    async fn transient_failures_are_retried_until_the_send_succeeds() {
        let (_, state, _guard) = setup_test_app().await;
        let provider = Provider::answering(vec![
            (
                StatusCode::SERVICE_UNAVAILABLE,
                json!({ "reason": "ServiceUnavailable" }),
            ),
            (
                StatusCode::TOO_MANY_REQUESTS,
                json!({ "reason": "TooManyRequests" }),
            ),
        ]);
        let state = state_using(&state, &provider).await;
        let pubkey = user_with_target(&state, 1, APNS_TOKEN, PushProvider::Apns, false).await;

        assert_eq!(send_unique(&state, &pubkey).await, 1);

        assert_eq!(provider.received().len(), 3);
        assert!(!logs_contain("Failed to send push notification"));
    }

    #[tracing_test::traced_test]
    #[tokio::test]
    async fn a_send_that_keeps_failing_stops_and_logs_without_secrets() {
        let (_, state, _guard) = setup_test_app().await;
        let provider = Provider::answering(vec![
            (StatusCode::SERVICE_UNAVAILABLE, json!({})),
            (StatusCode::SERVICE_UNAVAILABLE, json!({})),
            (StatusCode::SERVICE_UNAVAILABLE, json!({})),
            (StatusCode::SERVICE_UNAVAILABLE, json!({})),
        ]);
        let state = state_using(&state, &provider).await;
        let pubkey = user_with_target(&state, 1, FCM_TOKEN, PushProvider::Fcm, false).await;

        assert_eq!(send_unique(&state, &pubkey).await, 0);

        assert_eq!(provider.received().len(), 3);
        assert!(logs_contain("Failed to send push notification"));
        assert!(logs_contain("recurring_payment_due"));
        assert!(logs_contain("503"));
        assert!(!logs_contain(FCM_TOKEN));
        assert!(!logs_contain(GOOGLE_ACCESS_TOKEN));
    }

    #[tokio::test]
    async fn a_refused_access_token_is_replaced_before_the_retry() {
        let (_, state, _guard) = setup_test_app().await;
        let provider = Provider::answering(vec![(
            StatusCode::UNAUTHORIZED,
            json!({ "error": { "status": "UNAUTHENTICATED" } }),
        )]);
        let state = state_using(&state, &provider).await;
        let pubkey = user_with_target(&state, 1, FCM_TOKEN, PushProvider::Fcm, false).await;

        assert_eq!(send_unique(&state, &pubkey).await, 1);

        assert_eq!(provider.received().len(), 2);
        assert_eq!(provider.token_requests().len(), 2);
    }

    /// Sends share one token request, so without a deadline a stalled token endpoint
    /// makes each waiting send take its turn at the HTTP timeout.
    #[tracing_test::traced_test]
    #[tokio::test]
    async fn sends_waiting_on_a_stalled_token_request_stop_at_the_deadline() {
        let (_, state, _guard) = setup_test_app().await;
        let provider = Provider {
            stall_token_requests: true,
            ..Provider::default()
        };
        let state = state_using_deadline(&state, &provider, Some(Duration::from_millis(200))).await;
        let first = user_with_target(&state, 1, FCM_TOKEN, PushProvider::Fcm, false).await;
        let second = user_with_target(&state, 2, FCM_TOKEN, PushProvider::Fcm, false).await;
        let third = user_with_target(&state, 3, FCM_TOKEN, PushProvider::Fcm, false).await;

        let started = std::time::Instant::now();
        let dispatched = tokio::join!(
            send_unique(&state, &first),
            send_unique(&state, &second),
            send_unique(&state, &third),
        );

        assert_eq!(dispatched, (0, 0, 0));
        // Well under the 10 s the HTTP client would wait for even one token request.
        assert!(started.elapsed() < Duration::from_secs(5));
        assert!(provider.received().is_empty());
        assert!(logs_contain("Direct push gave up at its deadline"));
        assert!(!logs_contain(FCM_TOKEN));
    }

    /// Tokens are kept: a device that is gone misses its heartbeats and is deregistered.
    #[tracing_test::traced_test]
    #[tokio::test]
    async fn an_unregistered_token_is_not_retried_and_is_kept() {
        let (_, state, _guard) = setup_test_app().await;
        let cases = [
            (
                PushProvider::Apns,
                APNS_TOKEN,
                StatusCode::GONE,
                json!({ "reason": "Unregistered" }),
            ),
            (
                PushProvider::Fcm,
                FCM_TOKEN,
                StatusCode::NOT_FOUND,
                json!({ "error": { "details": [{ "errorCode": "UNREGISTERED" }] } }),
            ),
        ];
        for (key, (kind, token, status, body)) in (1..).zip(cases) {
            let provider = Provider::answering(vec![(status, body)]);
            let state = state_using(&state, &provider).await;
            let pubkey = user_with_target(&state, key, token, kind, false).await;

            // The provider accepted the request, so the device still counts as dispatched.
            assert_eq!(send_unique(&state, &pubkey).await, 1);

            assert_eq!(provider.received().len(), 1);
            assert_eq!(
                PushTokenRepository::new(&state.db_pool)
                    .find_by_pubkey(&pubkey)
                    .await
                    .unwrap()
                    .as_deref(),
                Some(token)
            );
        }
        assert!(logs_contain("no longer registered"));
        assert!(!logs_contain("Failed to send push notification"));
        assert!(!logs_contain(APNS_TOKEN));
    }

    /// Could mean a wrong topic or environment rather than a dead device, so the token
    /// stays and the error reaches the logs.
    #[tracing_test::traced_test]
    #[tokio::test]
    async fn a_rejected_device_token_is_logged_and_not_retried() {
        let (_, state, _guard) = setup_test_app().await;
        let provider = Provider::answering(vec![(
            StatusCode::BAD_REQUEST,
            json!({ "reason": "BadDeviceToken" }),
        )]);
        let state = state_using(&state, &provider).await;
        let pubkey = user_with_target(&state, 1, APNS_TOKEN, PushProvider::Apns, false).await;

        assert_eq!(send_unique(&state, &pubkey).await, 0);

        assert_eq!(provider.received().len(), 1);
        assert!(logs_contain("BadDeviceToken"));
        assert!(!logs_contain(APNS_TOKEN));
    }

    #[tracing_test::traced_test]
    #[tokio::test]
    async fn a_raw_token_without_server_credentials_is_logged() {
        let (_, state, _guard) = setup_test_app().await;
        let apns = user_with_target(&state, 1, APNS_TOKEN, PushProvider::Apns, false).await;
        let fcm = user_with_target(&state, 2, FCM_TOKEN, PushProvider::Fcm, false).await;

        assert_eq!(send_unique(&state, &apns).await, 0);
        assert_eq!(send_unique(&state, &fcm).await, 0);

        assert!(logs_contain("APNs credentials are not configured"));
        assert!(logs_contain("FCM credentials are not configured"));
    }

    #[test]
    fn unusable_credentials_stop_startup_without_quoting_them() {
        let error = |config: Config| {
            DirectPush::from_config(&config)
                .err()
                .expect("expected a credentials error")
                .to_string()
        };
        let valid = config_with_credentials("http://localhost/token");
        assert!(DirectPush::from_config(&valid).is_ok());
        assert!(DirectPush::from_config(&TestUser::get_config()).is_ok());

        assert!(
            error(Config {
                apns_topic: None,
                ..valid.clone()
            })
            .contains("must be set together")
        );
        let bad_key = error(Config {
            apns_key_p8: Some("not-a-key-secret".to_string()),
            ..valid.clone()
        });
        assert!(bad_key.contains("APNS_KEY_P8") && !bad_key.contains("not-a-key-secret"));
        let bad_account = error(Config {
            fcm_service_account_json: Some(r#"{"private_key":"not-a-key-secret""#.to_string()),
            ..valid.clone()
        });
        assert!(
            bad_account.contains("FCM_SERVICE_ACCOUNT_JSON")
                && !bad_account.contains("not-a-key-secret")
        );
        let bad_rsa = error(Config {
            fcm_service_account_json: Some(
                json!({
                    "project_id": "noah-test",
                    "client_email": "push@noah-test.iam.gserviceaccount.com",
                    "private_key": "not-a-key-secret",
                    "token_uri": "http://localhost/token",
                })
                .to_string(),
            ),
            ..valid
        });
        assert!(bad_rsa.contains("invalid private key") && !bad_rsa.contains("not-a-key-secret"));
    }

    /// Run by hand: `cargo test -p server --bin server apns_negotiates_http2 -- --ignored`.
    /// Needs network, and proves the shared client can talk to the real service.
    #[tokio::test]
    #[ignore = "reaches the real APNs sandbox"]
    async fn apns_negotiates_http2_on_the_real_service() {
        let response = reqwest::Client::new()
            .post(format!(
                "https://api.sandbox.push.apple.com/3/device/{APNS_TOKEN}"
            ))
            .json(&json!({}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.version(), reqwest::Version::HTTP_2);
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        assert_eq!(
            response.json::<Value>().await.unwrap()["reason"],
            "MissingProviderToken"
        );
    }
}
