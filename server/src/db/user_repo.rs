use anyhow::Result;
use sqlx::{PgPool, Postgres, Row, Transaction, postgres::PgRow};

use crate::types::UserStatus;

#[derive(Debug, Clone)]
pub struct LightningAddressTakenError;

impl std::fmt::Display for LightningAddressTakenError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "Lightning address already taken")
    }
}

impl std::error::Error for LightningAddressTakenError {}

#[derive(Debug, Clone)]
pub struct DuplicateArkAddressError;

impl std::fmt::Display for DuplicateArkAddressError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "Invalid Ark address, duplicate exists in our database")
    }
}

impl std::error::Error for DuplicateArkAddressError {}

// This struct represents a user record from the database.
// It's a good practice to have a model struct for each of your database tables.
#[derive(Debug)]
pub struct User {
    pub pubkey: String,
    pub lightning_address: Option<String>,
    pub ark_address: Option<String>,
    pub display_name: Option<String>,
    pub email: Option<String>,
    pub is_email_verified: bool,
    pub status: UserStatus,
}

impl<'r> sqlx::FromRow<'r, PgRow> for User {
    fn from_row(row: &'r PgRow) -> std::result::Result<Self, sqlx::Error> {
        let status: String = row.try_get("status")?;
        let status = status
            .parse::<UserStatus>()
            .map_err(|e| sqlx::Error::ColumnDecode {
                index: "status".to_string(),
                source: e.into(),
            })?;

        Ok(Self {
            pubkey: row.try_get("pubkey")?,
            lightning_address: row.try_get("lightning_address")?,
            ark_address: row.try_get("ark_address")?,
            display_name: row.try_get("display_name")?,
            email: row.try_get("email")?,
            is_email_verified: row.try_get("is_email_verified")?,
            status,
        })
    }
}

// A struct to encapsulate user-related database operations
pub struct UserRepository<'a> {
    // We use a lifetime parameter 'a to show that this struct borrows the pool.
    pool: &'a PgPool,
}

impl<'a> UserRepository<'a> {
    /// Creates a new repository instance.
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    /// Finds a user by their public key.
    pub async fn find_by_pubkey(&self, pubkey: &str) -> Result<Option<User>> {
        let user = sqlx::query_as::<_, User>(
            "SELECT pubkey, lightning_address, ark_address, display_name, email, is_email_verified, status FROM users WHERE pubkey = $1",
        )
        .bind(pubkey)
        .fetch_optional(self.pool)
        .await?;

        Ok(user)
    }

    /// Finds a user's pubkey by their lightning address.
    pub async fn find_pubkey_by_lightning_address(
        &self,
        ln_address: &str,
    ) -> Result<Option<String>> {
        let pubkey = sqlx::query_scalar::<_, String>(
            "SELECT pubkey FROM users WHERE lightning_address = $1",
        )
        .bind(ln_address)
        .fetch_optional(self.pool)
        .await?;

        Ok(pubkey)
    }

    /// Finds a user by their lightning address.
    pub async fn find_by_lightning_address(&self, ln_address: &str) -> Result<Option<User>> {
        let user = sqlx::query_as::<_, User>(
            "SELECT pubkey, lightning_address, ark_address, display_name, email, is_email_verified, status FROM users WHERE lightning_address = $1",
        )
        .bind(ln_address)
        .fetch_optional(self.pool)
        .await?;

        Ok(user)
    }

    /// Returns lightning address autocomplete suggestions scoped to a domain.
    pub async fn search_lightning_address_suggestions(
        &self,
        username_query: &str,
        lnurl_domain: &str,
        limit: i64,
    ) -> Result<Vec<String>> {
        let normalized_username = username_query.to_lowercase();
        let normalized_domain = lnurl_domain.to_lowercase();
        let prefix_like = format!("{normalized_username}%");

        if normalized_username.len() < 3 {
            let addresses = sqlx::query_scalar::<_, String>(
                "SELECT lightning_address
                FROM users
                WHERE lightning_address IS NOT NULL
                  AND status = 'active'
                  AND lightning_address_domain = $1
                  AND lightning_address_username LIKE $2
                ORDER BY
                  CASE WHEN lightning_address_username = $3 THEN 0 ELSE 1 END,
                  lightning_address_username ASC
                LIMIT $4",
            )
            .bind(&normalized_domain)
            .bind(&prefix_like)
            .bind(&normalized_username)
            .bind(limit)
            .fetch_all(self.pool)
            .await?;

            return Ok(addresses);
        }

        let addresses = sqlx::query_scalar::<_, String>(
            "WITH candidates AS (
                SELECT
                    lightning_address,
                    lightning_address_username AS username,
                    CASE
                        WHEN lightning_address_username = $3 THEN 0
                        WHEN lightning_address_username LIKE $2 THEN 1
                        ELSE 2
                    END AS rank_group,
                    similarity(lightning_address_username, $3) AS similarity_score
                FROM users
                WHERE lightning_address IS NOT NULL
                  AND status = 'active'
                  AND lightning_address_domain = $1
                  AND (
                      lightning_address_username LIKE $2
                      OR lightning_address_username % $3
                  )
            )
            SELECT lightning_address
            FROM candidates
            ORDER BY
                rank_group ASC,
                CASE WHEN rank_group = 2 THEN similarity_score ELSE 0 END DESC,
                username ASC
            LIMIT $4",
        )
        .bind(&normalized_domain)
        .bind(&prefix_like)
        .bind(&normalized_username)
        .bind(limit)
        .fetch_all(self.pool)
        .await?;

        Ok(addresses)
    }

    /// Creates a new user within a transaction. This is a static method because
    // it operates on a transaction, not a connection owned by the repository instance.
    pub async fn create(
        tx: &mut Transaction<'_, Postgres>,
        pubkey: &str,
        ln_address: &str,
        ark_address: Option<&str>,
    ) -> Result<()> {
        match sqlx::query(
            "INSERT INTO users (pubkey, lightning_address, ark_address) VALUES ($1, $2, $3)",
        )
        .bind(pubkey)
        .bind(ln_address)
        .bind(ark_address)
        .execute(&mut **tx)
        .await
        {
            Ok(_) => Ok(()),
            Err(e) => {
                if is_lightning_address_conflict(&e) {
                    return Err(LightningAddressTakenError.into());
                }
                if is_ark_address_conflict(&e) {
                    return Err(DuplicateArkAddressError.into());
                }
                Err(e.into())
            }
        }
    }

    /// Updates a user's lightning address.
    pub async fn update_lightning_address(&self, pubkey: &str, ln_address: &str) -> Result<()> {
        match sqlx::query(
            "UPDATE users SET lightning_address = $1, updated_at = now() WHERE pubkey = $2",
        )
        .bind(ln_address)
        .bind(pubkey)
        .execute(self.pool)
        .await
        {
            Ok(_) => Ok(()),
            Err(e) => {
                if is_lightning_address_conflict(&e) {
                    return Err(LightningAddressTakenError.into());
                }
                Err(e.into())
            }
        }
    }

    /// Updates a user's ark address.
    pub async fn update_ark_address(&self, pubkey: &str, ark_address: &str) -> Result<()> {
        match sqlx::query("UPDATE users SET ark_address = $1, updated_at = now() WHERE pubkey = $2")
            .bind(ark_address)
            .bind(pubkey)
            .execute(self.pool)
            .await
        {
            Ok(_) => Ok(()),
            Err(e) => {
                if is_ark_address_conflict(&e) {
                    return Err(DuplicateArkAddressError.into());
                }
                Err(e.into())
            }
        }
    }

    /// Updates a user's optional display name. Empty strings are converted to NULL.
    pub async fn update_display_name(
        &self,
        pubkey: &str,
        display_name: Option<&str>,
    ) -> Result<()> {
        let normalized_display_name = display_name
            .map(str::trim)
            .filter(|value| !value.is_empty());

        sqlx::query("UPDATE users SET display_name = $1, updated_at = now() WHERE pubkey = $2")
            .bind(normalized_display_name)
            .bind(pubkey)
            .execute(self.pool)
            .await?;

        Ok(())
    }

    /// Checks if a user exists by their public key.
    pub async fn exists_by_pubkey(&self, pubkey: &str) -> Result<bool, sqlx::Error> {
        let exists =
            sqlx::query_scalar::<_, bool>("SELECT EXISTS(SELECT 1 FROM users WHERE pubkey = $1)")
                .bind(pubkey)
                .fetch_one(self.pool)
                .await?;

        Ok(exists)
    }

    pub async fn set_status(&self, pubkey: &str, status: UserStatus) -> Result<()> {
        sqlx::query(
            "UPDATE users
             SET status = $1, status_changed_at = now(), updated_at = now()
             WHERE pubkey = $2
               AND status <> $1",
        )
        .bind(status.as_str())
        .bind(pubkey)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn set_status_tx(
        tx: &mut Transaction<'_, Postgres>,
        pubkey: &str,
        status: UserStatus,
    ) -> Result<()> {
        sqlx::query(
            "UPDATE users
             SET status = $1, status_changed_at = now(), updated_at = now()
             WHERE pubkey = $2
               AND status <> $1",
        )
        .bind(status.as_str())
        .bind(pubkey)
        .execute(&mut **tx)
        .await?;

        Ok(())
    }

    /// Updates a user's email address. Empty strings are converted to NULL.
    pub async fn update_email(&self, pubkey: &str, email: &str) -> Result<()> {
        // Treat empty strings as NULL to keep semantics stable and avoid storing "".
        let email_value: Option<&str> = if email.is_empty() { None } else { Some(email) };

        sqlx::query("UPDATE users SET email = $1, updated_at = now() WHERE pubkey = $2")
            .bind(email_value)
            .bind(pubkey)
            .execute(self.pool)
            .await?;
        Ok(())
    }

    /// Marks a user's email as verified.
    pub async fn set_email_verified(&self, pubkey: &str) -> Result<()> {
        sqlx::query(
            "UPDATE users SET is_email_verified = true, updated_at = now() WHERE pubkey = $1",
        )
        .bind(pubkey)
        .execute(self.pool)
        .await?;
        Ok(())
    }

    /// Checks if a user's email is verified.
    pub async fn is_email_verified(&self, pubkey: &str) -> Result<bool> {
        let verified =
            sqlx::query_scalar::<_, bool>("SELECT is_email_verified FROM users WHERE pubkey = $1")
                .bind(pubkey)
                .fetch_optional(self.pool)
                .await?;
        Ok(verified.unwrap_or(false))
    }

    /// Updates the user's last login timestamp.
    pub async fn update_last_login(&self, pubkey: &str) -> Result<()> {
        sqlx::query("UPDATE users SET last_login_at = now(), updated_at = now() WHERE pubkey = $1")
            .bind(pubkey)
            .execute(self.pool)
            .await?;
        Ok(())
    }

    #[cfg(test)]
    pub async fn get_last_login_at(
        &self,
        pubkey: &str,
    ) -> Result<Option<chrono::DateTime<chrono::Utc>>> {
        let last_login = sqlx::query_scalar("SELECT last_login_at FROM users WHERE pubkey = $1")
            .bind(pubkey)
            .fetch_one(self.pool)
            .await?;
        Ok(last_login)
    }
}

fn is_lightning_address_conflict(error: &sqlx::Error) -> bool {
    if let sqlx::Error::Database(db_err) = error {
        return db_err.code().as_deref() == Some("23505")
            && db_err.constraint() == Some("users_lightning_address_key");
    }

    false
}

fn is_ark_address_conflict(error: &sqlx::Error) -> bool {
    if let sqlx::Error::Database(db_err) = error {
        return db_err.code().as_deref() == Some("23505")
            && db_err.constraint() == Some("users_ark_address_key");
    }

    false
}
