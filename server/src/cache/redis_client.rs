use anyhow::Context;
use deadpool_redis::{
    Connection, Pool, PoolConfig, Runtime,
    redis::{AsyncCommands, FromRedisValue, ToSingleRedisArg, cmd},
};

use super::keyspace::RedisKey;

/// Redis connection pool. Every command takes a `RedisKey`, never a string.
#[derive(Clone)]
pub struct RedisClient {
    pool: Pool,
}

impl RedisClient {
    pub fn new(connection_url: &str) -> anyhow::Result<Self> {
        Self::with_pool_size(connection_url, 32)
    }

    pub fn with_pool_size(connection_url: &str, max_size: usize) -> anyhow::Result<Self> {
        let mut config = deadpool_redis::Config::from_url(connection_url);
        config.pool = Some(PoolConfig {
            max_size,
            ..Default::default()
        });
        let pool = config
            .create_pool(Some(Runtime::Tokio1))
            .context("Failed to create Redis pool")?;

        Ok(Self { pool })
    }

    async fn connection(&self) -> anyhow::Result<Connection> {
        self.pool
            .get()
            .await
            .context("Failed to acquire Redis connection")
    }

    pub async fn check_connection(&self) -> anyhow::Result<()> {
        let mut connection = self.connection().await?;
        let _: String = cmd("PING")
            .query_async(&mut connection)
            .await
            .context("Failed to connect to Redis")?;
        Ok(())
    }

    pub async fn get<V: FromRedisValue>(&self, key: &RedisKey) -> anyhow::Result<V> {
        Ok(self.connection().await?.get(key.as_str()).await?)
    }

    pub async fn get_del<V: FromRedisValue>(&self, key: &RedisKey) -> anyhow::Result<V> {
        Ok(self.connection().await?.get_del(key.as_str()).await?)
    }

    pub async fn set<V: ToSingleRedisArg + Send + Sync>(
        &self,
        key: &RedisKey,
        value: V,
    ) -> anyhow::Result<()> {
        Ok(self.connection().await?.set(key.as_str(), value).await?)
    }

    pub async fn set_ex<V: ToSingleRedisArg + Send + Sync>(
        &self,
        key: &RedisKey,
        value: V,
        ttl_seconds: u64,
    ) -> anyhow::Result<()> {
        Ok(self
            .connection()
            .await?
            .set_ex(key.as_str(), value, ttl_seconds)
            .await?)
    }

    pub async fn incr<V: ToSingleRedisArg + FromRedisValue + Send + Sync>(
        &self,
        key: &RedisKey,
        delta: V,
    ) -> anyhow::Result<V> {
        Ok(self.connection().await?.incr(key.as_str(), delta).await?)
    }

    pub async fn del(&self, key: &RedisKey) -> anyhow::Result<()> {
        Ok(self.connection().await?.del(key.as_str()).await?)
    }

    pub async fn exists(&self, key: &RedisKey) -> anyhow::Result<bool> {
        Ok(self.connection().await?.exists(key.as_str()).await?)
    }

    pub async fn flush_db(&self) -> anyhow::Result<()> {
        let _: () = cmd("FLUSHDB")
            .query_async(&mut self.connection().await?)
            .await?;
        Ok(())
    }

    /// Raw access for tests that play another writer, such as an older server or an attacker.
    #[cfg(test)]
    pub async fn raw_connection(&self) -> anyhow::Result<Connection> {
        self.connection().await
    }
}
