use anyhow::Context;

use super::{keyspace::RedisKey, redis_client::RedisClient};

#[derive(Clone)]
pub struct MaintenanceStore {
    client: RedisClient,
}

impl MaintenanceStore {
    pub fn new(client: RedisClient) -> Self {
        Self { client }
    }

    pub async fn get_last_round_timestamp(&self) -> anyhow::Result<Option<u64>> {
        self.client
            .get(&RedisKey::last_round_timestamp())
            .await
            .context("Failed to get last round timestamp")
    }

    pub async fn set_last_round_timestamp(&self, ts: u64) -> anyhow::Result<()> {
        self.client
            .set(&RedisKey::last_round_timestamp(), ts)
            .await
            .context("Failed to set last round timestamp")
    }

    pub async fn get_round_counter(&self) -> anyhow::Result<u16> {
        let counter: Option<u16> = self
            .client
            .get(&RedisKey::round_counter())
            .await
            .context("Failed to get round counter")?;
        Ok(counter.unwrap_or(0))
    }

    pub async fn increment_round_counter(&self) -> anyhow::Result<u16> {
        self.client
            .incr(&RedisKey::round_counter(), 1u16)
            .await
            .context("Failed to increment round counter")
    }

    pub async fn reset_round_counter(&self) -> anyhow::Result<()> {
        self.client
            .set(&RedisKey::round_counter(), 0u16)
            .await
            .context("Failed to reset round counter")
    }
}
