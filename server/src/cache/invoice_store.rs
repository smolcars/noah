use deadpool_redis::redis::AsyncCommands;

use super::redis_client::RedisClient;

// Not "invoice:": a server from before the payee binding writes `invoice:<transaction_id>` with an
// ID the submitter chooses, and must not be able to write a key this one trusts during a deploy.
const INVOICE_PREFIX: &str = "lnurl_invoice:";
const INVOICE_TTL_SECONDS: u64 = 60;

#[derive(Clone)]
pub struct InvoiceStore {
    client: RedisClient,
}

impl InvoiceStore {
    pub fn new(client: RedisClient) -> Self {
        Self { client }
    }

    // Keyed by the payee as well, so only the account the request was pushed to can answer it.
    fn key(pubkey: &str, transaction_id: &str) -> String {
        format!("{INVOICE_PREFIX}{pubkey}:{transaction_id}")
    }

    pub async fn store(
        &self,
        pubkey: &str,
        transaction_id: &str,
        invoice: &str,
    ) -> anyhow::Result<()> {
        let key = Self::key(pubkey, transaction_id);
        let mut conn = self.client.get_connection().await?;
        let _: () = conn.set_ex(&key, invoice, INVOICE_TTL_SECONDS).await?;
        Ok(())
    }

    pub async fn get(&self, pubkey: &str, transaction_id: &str) -> anyhow::Result<Option<String>> {
        let key = Self::key(pubkey, transaction_id);
        let mut conn = self.client.get_connection().await?;
        let invoice: Option<String> = conn.get(&key).await?;
        Ok(invoice)
    }

    pub async fn remove(&self, pubkey: &str, transaction_id: &str) -> anyhow::Result<()> {
        let key = Self::key(pubkey, transaction_id);
        let mut conn = self.client.get_connection().await?;
        let _: () = conn.del(&key).await?;
        Ok(())
    }
}
