use std::time::{SystemTime, UNIX_EPOCH};

use deadpool_redis::redis::{AsyncCommands, cmd};
use rand::Rng;

use super::redis_client::RedisClient;

/// Handles issuing and validating k1 challenges in Redis.
#[derive(Clone)]
pub struct K1Store {
    client: RedisClient,
    ttl_seconds: usize,
}

impl K1Store {
    pub fn new(client: RedisClient, ttl_seconds: usize) -> Self {
        Self {
            client,
            ttl_seconds,
        }
    }

    /// Generates, stores, and returns a fresh k1 token.
    pub async fn issue_k1(&self) -> anyhow::Result<String> {
        let mut k1_bytes = [0u8; 32];
        rand::rng().fill_bytes(&mut k1_bytes);

        let timestamp = current_timestamp();
        let k1_with_timestamp = format!("{}_{}", hex::encode(k1_bytes), timestamp);

        self.persist(&k1_with_timestamp, timestamp).await?;
        Ok(k1_with_timestamp)
    }

    /// Checks whether the provided k1 exists in the cache.
    pub async fn contains(&self, k1: &str) -> anyhow::Result<bool> {
        let mut conn = self.client.get_connection().await?;
        let exists: bool = conn.exists(k1).await?;
        Ok(exists)
    }

    /// Removes a k1 token from the cache.
    pub async fn remove(&self, k1: &str) -> anyhow::Result<()> {
        let mut conn = self.client.get_connection().await?;
        let _: () = conn.del(k1).await?;
        Ok(())
    }

    /// Atomically consumes a k1 token so it cannot be reused.
    pub async fn take(&self, k1: &str) -> anyhow::Result<bool> {
        let mut conn = self.client.get_connection().await?;
        let value: Option<i64> = cmd("GETDEL").arg(k1).query_async(&mut conn).await?;
        Ok(value.is_some())
    }

    /// Inserts an externally created k1 string. Useful for tests.
    pub async fn insert_with_timestamp(&self, k1: &str, timestamp: u64) -> anyhow::Result<()> {
        self.persist(k1, timestamp).await
    }

    /// Clears all cached values. Only intended for tests.
    pub async fn clear_all(&self) -> anyhow::Result<()> {
        let mut conn = self.client.get_connection().await?;
        let _: () = cmd("FLUSHDB").query_async(&mut conn).await?;
        Ok(())
    }

    async fn persist(&self, k1: &str, timestamp: u64) -> anyhow::Result<()> {
        let mut conn = self.client.get_connection().await?;
        let ttl_seconds = u64::try_from(self.ttl_seconds).unwrap_or(u64::MAX);
        let _: () = conn.set_ex(k1, timestamp as i64, ttl_seconds).await?;
        Ok(())
    }
}

fn current_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_else(|_| std::time::Duration::from_secs(0))
        .as_secs()
}
