use std::{sync::Arc, time::Duration};

use chrono::Utc;
use expo_push_notification_client::Priority;
use server_rpc::protos::mailbox_server::{
    IncomingLightningPaymentMessage, MailboxMessage, mailbox_message::Message,
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
