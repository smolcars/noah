use deadpool_redis::redis::AsyncCommands;
use rand::RngExt;

use super::redis_client::RedisClient;

const EMAIL_VERIFICATION_PREFIX: &str = "email_verification:";
const EMAIL_VERIFICATION_TTL_SECONDS: u64 = 600; // 10 minutes
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
        let key = format!("{}{}:code", EMAIL_VERIFICATION_PREFIX, pubkey);
        let email_key = format!("{}{}:email", EMAIL_VERIFICATION_PREFIX, pubkey);
        let mut conn = self.client.get_connection().await?;
        let _: () = conn
            .set_ex(&key, code, EMAIL_VERIFICATION_TTL_SECONDS)
            .await?;
        let _: () = conn
            .set_ex(&email_key, email, EMAIL_VERIFICATION_TTL_SECONDS)
            .await?;
        Ok(())
    }

    pub async fn get_code(&self, pubkey: &str) -> anyhow::Result<Option<String>> {
        let key = format!("{}{}:code", EMAIL_VERIFICATION_PREFIX, pubkey);
        let mut conn = self.client.get_connection().await?;
        let code: Option<String> = conn.get(&key).await?;
        Ok(code)
    }

    pub async fn get_email(&self, pubkey: &str) -> anyhow::Result<Option<String>> {
        let key = format!("{}{}:email", EMAIL_VERIFICATION_PREFIX, pubkey);
        let mut conn = self.client.get_connection().await?;
        let email: Option<String> = conn.get(&key).await?;
        Ok(email)
    }

    pub async fn verify(
        &self,
        pubkey: &str,
        code: &str,
        dev_mode: bool,
    ) -> anyhow::Result<Option<String>> {
        // In dev mode, accept the test code
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
        let code_key = format!("{}{}:code", EMAIL_VERIFICATION_PREFIX, pubkey);
        let email_key = format!("{}{}:email", EMAIL_VERIFICATION_PREFIX, pubkey);
        let mut conn = self.client.get_connection().await?;
        let _: () = conn.del(&code_key).await?;
        let _: () = conn.del(&email_key).await?;
        Ok(())
    }

    pub fn generate_code() -> String {
        let code: u32 = rand::rng().random_range(100000..1000000);
        code.to_string()
    }
}
