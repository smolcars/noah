use rand::RngExt;

use super::{keyspace::RedisKey, redis_client::RedisClient};

const EMAIL_VERIFICATION_TTL_SECONDS: u64 = 600;
const TEST_VERIFICATION_CODE: &str = "000000";

#[derive(Clone)]
pub struct EmailVerificationStore {
    client: RedisClient,
}

impl EmailVerificationStore {
    pub fn new(client: RedisClient) -> Self {
        Self { client }
    }

    pub async fn store(&self, pubkey: &str, email: &str, code: &str) -> anyhow::Result<()> {
        self.client
            .set_ex(
                &RedisKey::email_verification_code(pubkey),
                code,
                EMAIL_VERIFICATION_TTL_SECONDS,
            )
            .await?;
        self.client
            .set_ex(
                &RedisKey::email_verification_email(pubkey),
                email,
                EMAIL_VERIFICATION_TTL_SECONDS,
            )
            .await
    }

    pub async fn get_code(&self, pubkey: &str) -> anyhow::Result<Option<String>> {
        self.client
            .get(&RedisKey::email_verification_code(pubkey))
            .await
    }

    pub async fn get_email(&self, pubkey: &str) -> anyhow::Result<Option<String>> {
        self.client
            .get(&RedisKey::email_verification_email(pubkey))
            .await
    }

    pub async fn verify(
        &self,
        pubkey: &str,
        code: &str,
        dev_mode: bool,
    ) -> anyhow::Result<Option<String>> {
        if dev_mode && code == TEST_VERIFICATION_CODE {
            let email = self.get_email(pubkey).await?;
            if email.is_some() {
                self.remove(pubkey).await?;
                return Ok(email);
            }
        }

        let stored_code = self.get_code(pubkey).await?;
        match stored_code {
            Some(stored) if stored == code => {
                let email = self.get_email(pubkey).await?;
                self.remove(pubkey).await?;
                Ok(email)
            }
            _ => Ok(None),
        }
    }

    pub async fn remove(&self, pubkey: &str) -> anyhow::Result<()> {
        self.client
            .del(&RedisKey::email_verification_code(pubkey))
            .await?;
        self.client
            .del(&RedisKey::email_verification_email(pubkey))
            .await
    }

    pub fn generate_code() -> String {
        let code: u32 = rand::rng().random_range(100000..1000000);
        code.to_string()
    }
}
