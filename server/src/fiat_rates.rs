use std::{collections::BTreeMap, time::Duration};

use anyhow::{Context, Result};
use chrono::{DateTime, Datelike, Days, NaiveDate, TimeZone, Utc};
use serde::Deserialize;

use crate::{config::Config, db::fiat_rate_repo::FiatRateRepository};

pub const SUPPORTED_FIAT_CURRENCIES: &[&str] = &[
    "USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY", "BRL", "KRW", "INR", "MXN", "SGD",
];

const COINGECKO_BASE_URL: &str = "https://api.coingecko.com/api/v3";
const COINGECKO_REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
const COINGECKO_USER_AGENT: &str = "NoahWallet/0.1 server fiat-rate cache (https://noahwallet.io)";
const SOURCE_COINGECKO: &str = "coingecko";

#[derive(Debug, Clone)]
pub struct FetchedFiatRate {
    pub currency: String,
    pub rate_date: NaiveDate,
    pub btc_price: f64,
    pub observed_at: DateTime<Utc>,
    pub source: String,
}

#[derive(Deserialize)]
struct CoinGeckoSimplePrice {
    bitcoin: BTreeMap<String, serde_json::Value>,
}

#[derive(Deserialize)]
struct CoinGeckoHistory {
    market_data: Option<CoinGeckoMarketData>,
}

#[derive(Deserialize)]
struct CoinGeckoMarketData {
    current_price: BTreeMap<String, f64>,
}

#[derive(Deserialize)]
struct CoinGeckoMarketChartRange {
    prices: Vec<(i64, f64)>,
}

pub fn is_supported_currency(currency: &str) -> bool {
    SUPPORTED_FIAT_CURRENCIES.contains(&currency)
}

pub struct CoinGeckoFiatRateProvider {
    client: reqwest::Client,
    api_key: Option<String>,
}

impl CoinGeckoFiatRateProvider {
    pub fn new(config: &Config) -> Self {
        Self {
            client: reqwest::Client::builder()
                .timeout(COINGECKO_REQUEST_TIMEOUT)
                .user_agent(COINGECKO_USER_AGENT)
                .build()
                .expect("valid CoinGecko HTTP client"),
            api_key: config.coingecko_demo_api_key.clone(),
        }
    }

    pub async fn fetch_latest_rates(&self) -> Result<Vec<FetchedFiatRate>> {
        let currencies = SUPPORTED_FIAT_CURRENCIES
            .iter()
            .map(|currency| currency.to_ascii_lowercase())
            .collect::<Vec<_>>()
            .join(",");

        tracing::debug!(
            source = SOURCE_COINGECKO,
            currencies = %currencies,
            "fetching latest fiat rates"
        );

        let request = self
            .client
            .get(format!("{COINGECKO_BASE_URL}/simple/price"))
            .query(&[
                ("ids", "bitcoin"),
                ("vs_currencies", currencies.as_str()),
                ("include_last_updated_at", "true"),
            ]);

        let response = self.apply_api_key(request).send().await?;
        let data = response
            .error_for_status()?
            .json::<CoinGeckoSimplePrice>()
            .await?;

        let observed_at = data
            .bitcoin
            .get("last_updated_at")
            .and_then(serde_json::Value::as_i64)
            .and_then(|timestamp| Utc.timestamp_opt(timestamp, 0).single())
            .unwrap_or_else(Utc::now);
        let rate_date = observed_at.date_naive();

        let mut rates = Vec::new();
        for currency in SUPPORTED_FIAT_CURRENCIES {
            let key = currency.to_ascii_lowercase();
            if let Some(price) = data.bitcoin.get(&key).and_then(serde_json::Value::as_f64) {
                rates.push(FetchedFiatRate {
                    currency: (*currency).to_string(),
                    rate_date,
                    btc_price: price,
                    observed_at,
                    source: SOURCE_COINGECKO.to_string(),
                });
            }
        }

        tracing::debug!(
            source = SOURCE_COINGECKO,
            rate_count = rates.len(),
            observed_at = %observed_at,
            "fetched latest fiat rates"
        );

        Ok(rates)
    }

    pub async fn fetch_historical_rate(
        &self,
        currency: &str,
        rate_date: NaiveDate,
    ) -> Result<FetchedFiatRate> {
        let date = rate_date.format("%d-%m-%Y").to_string();
        tracing::debug!(
            source = SOURCE_COINGECKO,
            currency = %currency,
            rate_date = %rate_date,
            "fetching historical fiat rate"
        );

        let request = self
            .client
            .get(format!("{COINGECKO_BASE_URL}/coins/bitcoin/history"))
            .query(&[("date", date.as_str()), ("localization", "false")]);

        let response = self.apply_api_key(request).send().await?;
        let data = response
            .error_for_status()?
            .json::<CoinGeckoHistory>()
            .await?;
        let key = currency.to_ascii_lowercase();
        let price = data
            .market_data
            .and_then(|market_data| market_data.current_price.get(&key).copied())
            .context("CoinGecko historical response did not contain requested currency")?;

        let observed_at = Utc
            .with_ymd_and_hms(
                rate_date.year(),
                rate_date.month(),
                rate_date.day(),
                0,
                0,
                0,
            )
            .single()
            .unwrap_or_else(Utc::now);

        tracing::debug!(
            source = SOURCE_COINGECKO,
            currency = %currency,
            rate_date = %rate_date,
            "fetched historical fiat rate"
        );

        Ok(FetchedFiatRate {
            currency: currency.to_string(),
            rate_date,
            btc_price: price,
            observed_at,
            source: SOURCE_COINGECKO.to_string(),
        })
    }

    pub async fn fetch_historical_range(
        &self,
        currency: &str,
        start_date: NaiveDate,
        end_date: NaiveDate,
    ) -> Result<Vec<FetchedFiatRate>> {
        let start = Utc
            .with_ymd_and_hms(
                start_date.year(),
                start_date.month(),
                start_date.day(),
                0,
                0,
                0,
            )
            .single()
            .context("invalid start date")?;
        let end = Utc
            .with_ymd_and_hms(
                end_date.year(),
                end_date.month(),
                end_date.day(),
                23,
                59,
                59,
            )
            .single()
            .context("invalid end date")?;

        tracing::debug!(
            source = SOURCE_COINGECKO,
            currency = %currency,
            start_date = %start_date,
            end_date = %end_date,
            "fetching historical fiat rate range"
        );

        let request = self
            .client
            .get(format!(
                "{COINGECKO_BASE_URL}/coins/bitcoin/market_chart/range"
            ))
            .query(&[
                ("vs_currency", currency.to_ascii_lowercase()),
                ("from", start.timestamp().to_string()),
                ("to", end.timestamp().to_string()),
            ]);

        let response = self.apply_api_key(request).send().await?;
        let data = response
            .error_for_status()?
            .json::<CoinGeckoMarketChartRange>()
            .await?;

        let mut daily_rates: BTreeMap<NaiveDate, FetchedFiatRate> = BTreeMap::new();
        for (timestamp_ms, price) in data.prices {
            let Some(observed_at) = Utc.timestamp_millis_opt(timestamp_ms).single() else {
                continue;
            };
            daily_rates.insert(
                observed_at.date_naive(),
                FetchedFiatRate {
                    currency: currency.to_string(),
                    rate_date: observed_at.date_naive(),
                    btc_price: price,
                    observed_at,
                    source: SOURCE_COINGECKO.to_string(),
                },
            );
        }

        let rates = daily_rates.into_values().collect::<Vec<_>>();
        tracing::debug!(
            source = SOURCE_COINGECKO,
            currency = %currency,
            start_date = %start_date,
            end_date = %end_date,
            rate_count = rates.len(),
            "fetched historical fiat rate range"
        );

        Ok(rates)
    }

    fn apply_api_key(&self, request: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        match &self.api_key {
            Some(api_key) if !api_key.is_empty() => request.header("x-cg-demo-api-key", api_key),
            _ => request,
        }
    }
}

pub async fn refresh_latest_rates(config: &Config, repo: &FiatRateRepository<'_>) -> Result<()> {
    let provider = CoinGeckoFiatRateProvider::new(config);
    let rates = provider.fetch_latest_rates().await?;
    let rate_count = rates.len();

    for rate in rates {
        repo.upsert_rate(
            &rate.currency,
            rate.rate_date,
            rate.btc_price,
            rate.observed_at,
            &rate.source,
        )
        .await?;
    }

    tracing::info!(
        job = "fiat_rates",
        rate_count,
        "refreshed latest fiat rates"
    );

    Ok(())
}

pub async fn backfill_recent_rates(config: &Config, repo: &FiatRateRepository<'_>) -> Result<()> {
    let provider = CoinGeckoFiatRateProvider::new(config);
    let today = Utc::now().date_naive();
    let days = config.fiat_rate_backfill_days;
    let start = today
        .checked_sub_days(Days::new(days.saturating_sub(1)))
        .unwrap_or(today);

    for currency in SUPPORTED_FIAT_CURRENCIES {
        if repo.count_rates_since(currency, start).await? >= days as i64 {
            tracing::debug!(
                job = "fiat_rates",
                currency = %currency,
                start_date = %start,
                end_date = %today,
                "skipping fiat rate backfill; cached range is complete"
            );
            continue;
        }

        let rates = provider
            .fetch_historical_range(currency, start, today)
            .await
            .with_context(|| format!("failed to fetch historical range for {currency}"))?;

        let mut inserted_count = 0;
        for rate in rates {
            if repo
                .get_rate(&rate.currency, rate.rate_date)
                .await?
                .is_some()
            {
                continue;
            }

            repo.upsert_rate(
                &rate.currency,
                rate.rate_date,
                rate.btc_price,
                rate.observed_at,
                &rate.source,
            )
            .await?;
            inserted_count += 1;
        }

        tracing::info!(
            job = "fiat_rates",
            currency = %currency,
            start_date = %start,
            end_date = %today,
            inserted_count,
            "backfilled fiat rates"
        );
    }

    Ok(())
}
