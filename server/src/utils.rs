use std::str::FromStr;

use crate::cache::k1_store::K1Store;
use crate::db::user_repo::UserRepository;
use crate::errors::ApiError;
use sqlx::PgPool;

pub async fn verify_message(
    message: &str,
    signature: bitcoin::secp256k1::ecdsa::Signature,
    public_key: &bitcoin::secp256k1::PublicKey,
) -> Result<bool, ApiError> {
    let hash = bitcoin::sign_message::signed_msg_hash(message);
    let secp = bitcoin::secp256k1::Secp256k1::new();
    let msg = bitcoin::secp256k1::Message::from_digest_slice(&hash[..])?;
    Ok(secp.verify_ecdsa(&msg, &signature, public_key).is_ok())
}

pub async fn verify_auth(
    k1: String,
    signature: String,
    public_key: String,
) -> anyhow::Result<bool> {
    let signature = bitcoin::secp256k1::ecdsa::Signature::from_str(&signature)?;
    let public_key = bitcoin::secp256k1::PublicKey::from_str(&public_key)?;

    let is_valid = verify_message(&k1, signature, &public_key).await?;

    Ok(is_valid)
}

pub async fn make_k1(k1_store: &K1Store) -> anyhow::Result<String> {
    k1_store.issue_k1().await
}

pub async fn verify_user_exists(pool: &PgPool, pubkey: &str) -> Result<bool, ApiError> {
    let user_repo = UserRepository::new(pool);
    user_repo.exists_by_pubkey(pubkey).await.map_err(|e| {
        tracing::error!("Failed to query user: {}", e);
        ApiError::Database(e)
    })
}

/// Resolves on SIGTERM (how Railway stops a deploy) or Ctrl-C.
pub async fn shutdown_signal() {
    let ctrl_c = async {
        if let Err(error) = tokio::signal::ctrl_c().await {
            tracing::error!(error = %error, "failed to listen for Ctrl-C");
            std::future::pending::<()>().await;
        }
    };

    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut terminate) => {
                terminate.recv().await;
            }
            Err(error) => {
                tracing::error!(error = %error, "failed to listen for SIGTERM");
                std::future::pending::<()>().await;
            }
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => tracing::info!("received Ctrl-C; shutting down"),
        _ = terminate => tracing::info!("received SIGTERM; shutting down"),
    }
}
