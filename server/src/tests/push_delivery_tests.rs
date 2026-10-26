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
        assert_eq!(tokens.find_all().await.unwrap(), vec![token.to_string()]);
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
