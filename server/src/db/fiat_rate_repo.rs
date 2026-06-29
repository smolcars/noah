use anyhow::Result;
use chrono::{DateTime, NaiveDate, Utc};
use sqlx::{PgPool, Row};
use std::collections::BTreeMap;

#[derive(Debug, Clone)]
pub struct FiatRate {
    pub currency: String,
    pub rate_date: NaiveDate,
    pub btc_price: f64,
    pub observed_at: DateTime<Utc>,
    pub source: String,
}

pub struct FiatRateRepository<'a> {
    pool: &'a PgPool,
}

impl<'a> FiatRateRepository<'a> {
    pub fn new(pool: &'a PgPool) -> Self {
        Self { pool }
    }

    pub async fn upsert_rate(
        &self,
        currency: &str,
        rate_date: NaiveDate,
        btc_price: f64,
        observed_at: DateTime<Utc>,
        source: &str,
    ) -> Result<()> {
        sqlx::query(
            "INSERT INTO fiat_rates (currency, rate_date, btc_price, observed_at, source)
             VALUES ($1, $2, $3, $4, $5)
             ON CONFLICT (currency, rate_date)
             DO UPDATE SET
                btc_price = excluded.btc_price,
                observed_at = excluded.observed_at,
                source = excluded.source,
                updated_at = now()",
        )
        .bind(currency)
        .bind(rate_date)
        .bind(btc_price)
        .bind(observed_at)
        .bind(source)
        .execute(self.pool)
        .await?;

        Ok(())
    }

    pub async fn get_rate(&self, currency: &str, rate_date: NaiveDate) -> Result<Option<FiatRate>> {
        let row = sqlx::query(
            "SELECT currency, rate_date, btc_price, observed_at, source
             FROM fiat_rates
             WHERE currency = $1 AND rate_date = $2",
        )
        .bind(currency)
        .bind(rate_date)
        .fetch_optional(self.pool)
        .await?;

        Ok(row.map(|row| FiatRate {
            currency: row.try_get("currency").expect("currency selected"),
            rate_date: row.try_get("rate_date").expect("rate_date selected"),
            btc_price: row.try_get("btc_price").expect("btc_price selected"),
            observed_at: row.try_get("observed_at").expect("observed_at selected"),
            source: row.try_get("source").expect("source selected"),
        }))
    }

    pub async fn get_latest_rates(&self) -> Result<Vec<FiatRate>> {
        let rows = sqlx::query(
            "SELECT DISTINCT ON (currency) currency, rate_date, btc_price, observed_at, source
             FROM fiat_rates
             ORDER BY currency, observed_at DESC",
        )
        .fetch_all(self.pool)
        .await?;

        Ok(rows
            .into_iter()
            .map(|row| FiatRate {
                currency: row.try_get("currency").expect("currency selected"),
                rate_date: row.try_get("rate_date").expect("rate_date selected"),
                btc_price: row.try_get("btc_price").expect("btc_price selected"),
                observed_at: row.try_get("observed_at").expect("observed_at selected"),
                source: row.try_get("source").expect("source selected"),
            })
            .collect())
    }

    pub async fn get_latest_rate_map(&self) -> Result<BTreeMap<String, f64>> {
        Ok(self
            .get_latest_rates()
            .await?
            .into_iter()
            .map(|rate| (rate.currency, rate.btc_price))
            .collect())
    }

    pub async fn count_rates_since(&self, currency: &str, start_date: NaiveDate) -> Result<i64> {
        let count = sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM fiat_rates WHERE currency = $1 AND rate_date >= $2",
        )
        .bind(currency)
        .bind(start_date)
        .fetch_one(self.pool)
        .await?;

        Ok(count)
    }
}
