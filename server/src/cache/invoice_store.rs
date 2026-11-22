use super::{keyspace::RedisKey, redis_client::RedisClient};

const INVOICE_TTL_SECONDS: u64 = 60;

#[derive(Clone)]
pub struct InvoiceStore {
    client: RedisClient,
}

impl InvoiceStore {
    pub fn new(client: RedisClient) -> Self {
        Self { client }
    }

    pub async fn store(
        &self,
        pubkey: &str,
        transaction_id: &str,
        invoice: &str,
    ) -> anyhow::Result<()> {
        let key = RedisKey::lnurl_invoice(pubkey, transaction_id);
        self.client.set_ex(&key, invoice, INVOICE_TTL_SECONDS).await
    }

    pub async fn get(&self, pubkey: &str, transaction_id: &str) -> anyhow::Result<Option<String>> {
        self.client
            .get(&RedisKey::lnurl_invoice(pubkey, transaction_id))
            .await
    }

    pub async fn remove(&self, pubkey: &str, transaction_id: &str) -> anyhow::Result<()> {
        self.client
            .del(&RedisKey::lnurl_invoice(pubkey, transaction_id))
            .await
    }
}
