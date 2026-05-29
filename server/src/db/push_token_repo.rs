use anyhow::Result;
use sqlx::{PgPool, Postgres, Transaction};

/// A struct to encapsulate push token-related database operations.
pub struct PushTokenRepository<'a> {
    pool: &'a PgPool,
}

impl<'a> PushTokenRepository<'a> {
    /// Creates a new repository instance.
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    /// Inserts a new push token record, or updates the token if the pubkey already exists.
    pub async fn upsert(&self, pubkey: &str, push_token: &str) -> Result<()> {
        sqlx::query(
            "INSERT INTO push_tokens (pubkey, push_token)
             VALUES ($1, $2)
             ON CONFLICT(pubkey)
             DO UPDATE SET push_token = excluded.push_token, updated_at = now()",
        )
        .bind(pubkey)
        .bind(push_token)
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
    /// Deletes all push tokens for a given user within a transaction.
    pub async fn delete_by_pubkey(tx: &mut Transaction<'_, Postgres>, pubkey: &str) -> Result<()> {
        sqlx::query("DELETE FROM push_tokens WHERE pubkey = $1")
            .bind(pubkey)
            .execute(&mut **tx)
            .await?;
        Ok(())
    }

    /// Finds all push tokens in the database.
    pub async fn find_all(&self) -> Result<Vec<String>> {
        let tokens = sqlx::query_scalar::<_, String>("SELECT push_token FROM push_tokens")
            .fetch_all(self.pool)
            .await?;

        Ok(tokens)
    }

    /// Finds all `(pubkey, push_token)` pairs in the database.
    pub async fn find_all_with_pubkeys(&self) -> Result<Vec<(String, String)>> {
        let rows = sqlx::query_as::<_, (String, String)>(
            "SELECT pt.pubkey, pt.push_token
             FROM push_tokens pt
             INNER JOIN users u ON pt.pubkey = u.pubkey
             WHERE u.status = 'active'",
        )
        .fetch_all(self.pool)
        .await?;

        Ok(rows)
    }
}
