use std::time::{SystemTime, UNIX_EPOCH};

use rand::Rng;

use super::{keyspace::RedisKey, redis_client::RedisClient};

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

    pub async fn issue_k1(&self) -> anyhow::Result<String> {
        let mut k1_bytes = [0u8; 32];
        rand::rng().fill_bytes(&mut k1_bytes);

        let timestamp = current_timestamp();
        let k1_with_timestamp = format!("{}_{}", hex::encode(k1_bytes), timestamp);

        self.persist(&k1_with_timestamp, timestamp).await?;
        Ok(k1_with_timestamp)
    }

    /// Consumes a k1 in one GETDEL, so two requests carrying it cannot both succeed.
    pub async fn take(&self, k1: &str) -> anyhow::Result<bool> {
        let value: Option<i64> = self.client.get_del(&RedisKey::k1(k1)).await?;
        Ok(value.is_some())
    }

    #[cfg(test)]
    pub async fn contains(&self, k1: &str) -> anyhow::Result<bool> {
        self.client.exists(&RedisKey::k1(k1)).await
    }

    #[cfg(test)]
    pub async fn remove(&self, k1: &str) -> anyhow::Result<()> {
        self.client.del(&RedisKey::k1(k1)).await
    }

    #[cfg(test)]
    pub async fn insert_with_timestamp(&self, k1: &str, timestamp: u64) -> anyhow::Result<()> {
        self.persist(k1, timestamp).await
    }

    #[cfg(test)]
    pub async fn clear_all(&self) -> anyhow::Result<()> {
        self.client.flush_db().await
    }

    async fn persist(&self, k1: &str, timestamp: u64) -> anyhow::Result<()> {
        let ttl_seconds = u64::try_from(self.ttl_seconds).unwrap_or(u64::MAX);
        self.client
            .set_ex(&RedisKey::k1(k1), timestamp as i64, ttl_seconds)
            .await
    }
}

fn current_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_else(|_| std::time::Duration::from_secs(0))
        .as_secs()
}
