use anyhow::Result;
use sqlx::{PgPool, Postgres, Transaction};

use crate::types::PushProvider;

/// Where one wallet's pushes go. Without a provider, the token's own format decides
/// between Expo and UnifiedPush.
#[derive(Debug, Clone)]
pub struct PushTarget {
    pub pubkey: String,
    pub push_token: String,
    pub provider: Option<PushProvider>,
    pub apns_sandbox: bool,
}

impl From<(String, String, Option<String>, bool)> for PushTarget {
    fn from(
        (pubkey, push_token, provider, apns_sandbox): (String, String, Option<String>, bool),
    ) -> Self {
        // The column's CHECK constraint allows only these two values.
        let provider = provider.map(|provider| match provider.as_str() {
            "apns" => PushProvider::Apns,
            _ => PushProvider::Fcm,
        });
        Self {
            pubkey,
            push_token,
            provider,
            apns_sandbox,
        }
    }
}

/// A struct to encapsulate push token-related database operations.
pub struct PushTokenRepository<'a> {
    pool: &'a PgPool,
}

impl<'a> PushTokenRepository<'a> {
    /// Creates a new repository instance.
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    /// Inserts a new Expo or UnifiedPush token, or replaces whatever the pubkey had.
    pub async fn upsert(&self, pubkey: &str, push_token: &str) -> Result<()> {
        self.upsert_with_provider(pubkey, push_token, None, false)
            .await
    }

    /// Inserts a new push token record, or replaces the whole registration if the pubkey
    /// already exists, so a wallet restored on an older app stops using the old provider.
    pub async fn upsert_with_provider(
        &self,
        pubkey: &str,
        push_token: &str,
        provider: Option<PushProvider>,
        apns_sandbox: bool,
    ) -> Result<()> {
        sqlx::query(
            "INSERT INTO push_tokens (pubkey, push_token, provider, apns_sandbox)
             VALUES ($1, $2, $3, $4)
             ON CONFLICT(pubkey)
             DO UPDATE SET push_token = excluded.push_token,
                           provider = excluded.provider,
                           apns_sandbox = excluded.apns_sandbox,
                           updated_at = now()",
        )
        .bind(pubkey)
        .bind(push_token)
        .bind(provider.map(PushProvider::as_str))
        .bind(apns_sandbox)
        .execute(self.pool)
        .await?;
        Ok(())
    }

    /// Finds a push token by its associated public key.
    pub async fn find_by_pubkey(&self, pubkey: &str) -> Result<Option<String>> {
        let token =
            sqlx::query_scalar::<_, String>("SELECT push_token FROM push_tokens WHERE pubkey = $1")
                .bind(pubkey)
                .fetch_optional(self.pool)
                .await?;

        Ok(token)
    }

    /// Finds where a public key's pushes go.
    pub async fn find_target_by_pubkey(&self, pubkey: &str) -> Result<Option<PushTarget>> {
        let target = sqlx::query_as::<_, (String, String, Option<String>, bool)>(
            "SELECT pubkey, push_token, provider, apns_sandbox
             FROM push_tokens
             WHERE pubkey = $1",
        )
        .bind(pubkey)
        .fetch_optional(self.pool)
        .await?;

        Ok(target.map(PushTarget::from))
    }

    /// Deletes all push tokens for a given user within a transaction.
    pub async fn delete_by_pubkey(tx: &mut Transaction<'_, Postgres>, pubkey: &str) -> Result<()> {
        sqlx::query("DELETE FROM push_tokens WHERE pubkey = $1")
            .bind(pubkey)
            .execute(&mut **tx)
            .await?;
        Ok(())
    }

    /// Finds all push tokens in the database.
    pub async fn find_all(&self) -> Result<Vec<PushTarget>> {
        let rows = sqlx::query_as::<_, (String, String, Option<String>, bool)>(
            "SELECT pubkey, push_token, provider, apns_sandbox FROM push_tokens",
        )
        .fetch_all(self.pool)
        .await?;

        Ok(rows.into_iter().map(PushTarget::from).collect())
    }

    /// Finds the push tokens of all active users.
    pub async fn find_all_active(&self) -> Result<Vec<PushTarget>> {
        let rows = sqlx::query_as::<_, (String, String, Option<String>, bool)>(
            "SELECT pt.pubkey, pt.push_token, pt.provider, pt.apns_sandbox
             FROM push_tokens pt
             INNER JOIN users u ON pt.pubkey = u.pubkey
             WHERE u.status = 'active'",
        )
        .fetch_all(self.pool)
        .await?;

        Ok(rows.into_iter().map(PushTarget::from).collect())
    }
}
