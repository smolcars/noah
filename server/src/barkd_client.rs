use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use async_trait::async_trait;
use bitcoin::Network;
use lightning_invoice::Bolt11Invoice;
use reqwest::header::{AUTHORIZATION, HeaderValue};
use serde::{Deserialize, Serialize};

use crate::config::Config;

const CREATE_INVOICE_FOR_ADDRESS_PATH: &str = "api/v1/lightning/receives/invoice/for-address";
const ARK_INFO_PATH: &str = "api/v1/wallet/ark-info";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ForwardingInvoice {
    pub invoice: String,
}

#[async_trait]
pub trait ForwardingInvoiceProvider: Send + Sync {
    async fn check_readiness(&self) -> Result<()>;

    async fn create_invoice_for_address(
        &self,
        address: &str,
        amount_sat: u64,
        description: Option<&str>,
    ) -> Result<ForwardingInvoice>;
}

#[derive(Clone)]
pub struct BarkdClient {
    client: reqwest::Client,
    base_url: reqwest::Url,
    authorization: HeaderValue,
    expected_network: Network,
}

impl BarkdClient {
    pub fn new(
        base_url: &str,
        auth_token: &str,
        expected_network: Network,
        request_timeout: Duration,
    ) -> Result<Self> {
        if request_timeout.is_zero() {
            anyhow::bail!("barkd request timeout must be greater than zero");
        }
        if auth_token.trim().is_empty() {
            anyhow::bail!("barkd auth token must not be empty");
        }

        let mut base_url = reqwest::Url::parse(base_url).context("invalid barkd base URL")?;
        if !matches!(base_url.scheme(), "http" | "https") || base_url.cannot_be_a_base() {
            anyhow::bail!("barkd base URL must use HTTP or HTTPS");
        }
        if !base_url.path().ends_with('/') {
            let path = format!("{}/", base_url.path());
            base_url.set_path(&path);
        }

        let mut authorization = HeaderValue::from_str(&format!("Bearer {auth_token}"))
            .context("invalid barkd auth token")?;
        authorization.set_sensitive(true);

        let client = crate::http_client::outbound(request_timeout)
            .connect_timeout(request_timeout)
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .context("failed to build barkd HTTP client")?;

        Ok(Self {
            client,
            base_url,
            authorization,
            expected_network,
        })
    }

    fn endpoint(&self, path: &str) -> Result<reqwest::Url> {
        self.base_url
            .join(path)
            .context("failed to build barkd endpoint URL")
    }
}

pub fn build_forwarding_invoice_provider(
    config: &Config,
) -> Result<Option<Arc<dyn ForwardingInvoiceProvider>>> {
    if !config.barkd_forwarded_invoices_enabled {
        return Ok(None);
    }

    let base_url = config
        .barkd_url
        .as_deref()
        .context("BARKD_URL is required when forwarded invoices are enabled")?;
    let auth_token = config
        .barkd_auth_token
        .as_deref()
        .context("BARKD_AUTH_TOKEN is required when forwarded invoices are enabled")?;
    let client = BarkdClient::new(
        base_url,
        auth_token,
        config.network()?,
        Duration::from_secs(config.barkd_request_timeout_seconds),
    )?;

    Ok(Some(Arc::new(client)))
}

#[derive(Debug, Serialize)]
struct CreateInvoiceForAddressRequest<'a> {
    address: &'a str,
    amount_sat: u64,
    description: Option<&'a str>,
}

#[derive(Debug, Deserialize)]
struct InvoiceResponse {
    invoice: String,
}

#[derive(Debug, Deserialize)]
struct ArkInfoResponse {
    network: Network,
}

#[async_trait]
impl ForwardingInvoiceProvider for BarkdClient {
    async fn check_readiness(&self) -> Result<()> {
        let response = self
            .client
            .get(self.endpoint(ARK_INFO_PATH)?)
            .header(AUTHORIZATION, self.authorization.clone())
            .send()
            .await
            .context("barkd readiness request failed")?;

        let status = response.status();
        if !status.is_success() {
            anyhow::bail!("barkd readiness request returned HTTP {status}");
        }

        let response: ArkInfoResponse = response
            .json()
            .await
            .context("barkd readiness response was invalid")?;
        if response.network != self.expected_network {
            anyhow::bail!(
                "barkd network {} did not match expected network {}",
                response.network,
                self.expected_network
            );
        }

        Ok(())
    }

    async fn create_invoice_for_address(
        &self,
        address: &str,
        amount_sat: u64,
        description: Option<&str>,
    ) -> Result<ForwardingInvoice> {
        let response = self
            .client
            .post(self.endpoint(CREATE_INVOICE_FOR_ADDRESS_PATH)?)
            .header(AUTHORIZATION, self.authorization.clone())
            .json(&CreateInvoiceForAddressRequest {
                address,
                amount_sat,
                description,
            })
            .send()
            .await
            .context("barkd invoice request failed")?;

        let status = response.status();
        if !status.is_success() {
            anyhow::bail!("barkd invoice request returned HTTP {status}");
        }

        let response: InvoiceResponse = response
            .json()
            .await
            .context("barkd invoice response was invalid")?;
        if response.invoice.trim().is_empty() {
            anyhow::bail!("barkd invoice response was empty");
        }

        let invoice: Bolt11Invoice = response
            .invoice
            .parse()
            .context("barkd invoice response was not a valid BOLT11 invoice")?;
        if invoice.network() != self.expected_network {
            anyhow::bail!(
                "barkd invoice network {} did not match expected network {}",
                invoice.network(),
                self.expected_network
            );
        }
        let expected_amount_msat = amount_sat
            .checked_mul(1000)
            .context("barkd invoice amount exceeded the BOLT11 range")?;
        if invoice.amount_milli_satoshis() != Some(expected_amount_msat) {
            anyhow::bail!(
                "barkd invoice amount did not match requested amount of {expected_amount_msat} msat"
            );
        }

        Ok(ForwardingInvoice {
            invoice: response.invoice,
        })
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use axum::Router;
    use axum::http::{HeaderMap, StatusCode};
    use axum::response::IntoResponse;
    use axum::routing::{get, post};
    use bitcoin::Network;
    use bitcoin::hashes::{Hash, sha256};
    use bitcoin::secp256k1::{Secp256k1, SecretKey};
    use lightning_invoice::{Currency, InvoiceBuilder, PaymentSecret};
    use serde::Deserialize;
    use serde_json::json;

    use super::{BarkdClient, ForwardingInvoiceProvider};

    #[derive(Deserialize)]
    struct ReceivedInvoiceRequest {
        address: String,
        amount_sat: u64,
        description: Option<String>,
    }

    async fn spawn_server(app: Router) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        format!("http://{address}")
    }

    fn test_invoice(network: Network, amount_msat: u64) -> String {
        let private_key = SecretKey::from_slice(&[3; 32]).unwrap();
        InvoiceBuilder::new(Currency::from(network))
            .description("Test invoice".to_string())
            .payment_hash(sha256::Hash::from_byte_array([1; 32]))
            .payment_secret(PaymentSecret([2; 32]))
            .current_timestamp()
            .min_final_cltv_expiry_delta(18)
            .amount_milli_satoshis(amount_msat)
            .build_signed(|hash| Secp256k1::new().sign_ecdsa_recoverable(hash, &private_key))
            .unwrap()
            .to_string()
    }

    #[tokio::test]
    async fn checks_barkd_readiness_with_authentication() {
        let app = Router::new().route(
            "/api/v1/wallet/ark-info",
            get(|headers: HeaderMap| async move {
                if headers
                    .get("authorization")
                    .and_then(|value| value.to_str().ok())
                    != Some("Bearer secret-token")
                {
                    return StatusCode::UNAUTHORIZED.into_response();
                }

                axum::Json(json!({ "network": "signet" })).into_response()
            }),
        );
        let base_url = spawn_server(app).await;
        let client = BarkdClient::new(
            &base_url,
            "secret-token",
            Network::Signet,
            Duration::from_secs(1),
        )
        .unwrap();

        client.check_readiness().await.unwrap();
    }

    #[tokio::test]
    async fn reports_unready_barkd_without_exposing_the_response_body() {
        let app = Router::new().route(
            "/api/v1/wallet/ark-info",
            get(|| async {
                (
                    StatusCode::SERVICE_UNAVAILABLE,
                    "sensitive upstream details",
                )
            }),
        );
        let base_url = spawn_server(app).await;
        let client = BarkdClient::new(
            &base_url,
            "secret-token",
            Network::Signet,
            Duration::from_secs(1),
        )
        .unwrap();

        let error = client.check_readiness().await.unwrap_err().to_string();

        assert!(error.contains("HTTP 503 Service Unavailable"));
        assert!(!error.contains("sensitive upstream details"));
        assert!(!error.contains("secret-token"));
    }

    #[tokio::test]
    async fn rejects_barkd_for_the_wrong_network() {
        let app = Router::new().route(
            "/api/v1/wallet/ark-info",
            get(|| async { axum::Json(json!({ "network": "bitcoin" })) }),
        );
        let base_url = spawn_server(app).await;
        let client = BarkdClient::new(
            &base_url,
            "secret-token",
            Network::Signet,
            Duration::from_secs(1),
        )
        .unwrap();

        let error = client.check_readiness().await.unwrap_err().to_string();

        assert!(error.contains("did not match expected network"));
    }

    #[tokio::test]
    async fn creates_an_authenticated_invoice_for_the_supplied_address() {
        let invoice = test_invoice(Network::Signet, 330_000);
        let response_invoice = invoice.clone();
        let app = Router::new().route(
            "/api/v1/lightning/receives/invoice/for-address",
            post(
                move |headers: HeaderMap, axum::Json(body): axum::Json<ReceivedInvoiceRequest>| {
                    let response_invoice = response_invoice.clone();
                    async move {
                        if headers
                            .get("authorization")
                            .and_then(|value| value.to_str().ok())
                            != Some("Bearer secret-token")
                        {
                            return StatusCode::UNAUTHORIZED.into_response();
                        }
                        if body.address != "ark-address"
                            || body.amount_sat != 330
                            || body.description.as_deref() != Some("Paying test@noahwallet.io")
                        {
                            return StatusCode::BAD_REQUEST.into_response();
                        }
                        axum::Json(json!({ "invoice": response_invoice })).into_response()
                    }
                },
            ),
        );
        let base_url = spawn_server(app).await;
        let client = BarkdClient::new(
            &base_url,
            "secret-token",
            Network::Signet,
            Duration::from_secs(1),
        )
        .unwrap();

        let result = client
            .create_invoice_for_address("ark-address", 330, Some("Paying test@noahwallet.io"))
            .await
            .unwrap();

        assert_eq!(result.invoice, invoice);
    }

    #[tokio::test]
    async fn reports_status_without_exposing_the_response_body() {
        let app = Router::new().route(
            "/api/v1/lightning/receives/invoice/for-address",
            post(|| async {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    "sensitive upstream details",
                )
            }),
        );
        let base_url = spawn_server(app).await;
        let client = BarkdClient::new(
            &base_url,
            "secret-token",
            Network::Signet,
            Duration::from_secs(1),
        )
        .unwrap();

        let error = client
            .create_invoice_for_address("ark-address", 330, None)
            .await
            .unwrap_err()
            .to_string();

        assert!(error.contains("HTTP 500 Internal Server Error"));
        assert!(!error.contains("sensitive upstream details"));
        assert!(!error.contains("secret-token"));
    }

    #[tokio::test]
    async fn rejects_invalid_or_empty_invoice_responses() {
        for response in [
            "not-json",
            r#"{"invoice":""}"#,
            r#"{"invoice":"not-a-bolt11"}"#,
        ] {
            let body = response.to_string();
            let app = Router::new().route(
                "/api/v1/lightning/receives/invoice/for-address",
                post(move || {
                    let body = body.clone();
                    async move { body }
                }),
            );
            let base_url = spawn_server(app).await;
            let client = BarkdClient::new(
                &base_url,
                "secret-token",
                Network::Signet,
                Duration::from_secs(1),
            )
            .unwrap();

            assert!(
                client
                    .create_invoice_for_address("ark-address", 330, None)
                    .await
                    .is_err()
            );
        }
    }

    #[tokio::test]
    async fn rejects_invoices_for_the_wrong_amount_or_network() {
        for (invoice, expected_error) in [
            (test_invoice(Network::Signet, 329_000), "amount"),
            (test_invoice(Network::Bitcoin, 330_000), "network"),
        ] {
            let app = Router::new().route(
                "/api/v1/lightning/receives/invoice/for-address",
                post(move || {
                    let invoice = invoice.clone();
                    async move { axum::Json(json!({ "invoice": invoice })) }
                }),
            );
            let base_url = spawn_server(app).await;
            let client = BarkdClient::new(
                &base_url,
                "secret-token",
                Network::Signet,
                Duration::from_secs(1),
            )
            .unwrap();

            let error = client
                .create_invoice_for_address("ark-address", 330, None)
                .await
                .unwrap_err();

            assert!(error.to_string().contains(expected_error));
        }
    }

    #[tokio::test]
    async fn enforces_the_total_request_timeout() {
        let app = Router::new().route(
            "/api/v1/lightning/receives/invoice/for-address",
            post(|| async {
                tokio::time::sleep(Duration::from_millis(100)).await;
                axum::Json(json!({ "invoice": "late-invoice" }))
            }),
        );
        let base_url = spawn_server(app).await;
        let client = BarkdClient::new(
            &base_url,
            "secret-token",
            Network::Signet,
            Duration::from_millis(10),
        )
        .unwrap();

        let error = client
            .create_invoice_for_address("ark-address", 330, None)
            .await
            .unwrap_err();

        assert!(error.to_string().contains("barkd invoice request failed"));
    }

    #[test]
    fn validates_constructor_inputs() {
        assert!(
            BarkdClient::new(
                "not a URL",
                "token",
                Network::Signet,
                Duration::from_secs(1)
            )
            .is_err()
        );
        assert!(
            BarkdClient::new(
                "http://localhost",
                "bad\ntoken",
                Network::Signet,
                Duration::from_secs(1)
            )
            .is_err()
        );
        assert!(
            BarkdClient::new(
                "ftp://localhost",
                "token",
                Network::Signet,
                Duration::from_secs(1)
            )
            .is_err()
        );
        assert!(
            BarkdClient::new(
                "http://localhost",
                " ",
                Network::Signet,
                Duration::from_secs(1)
            )
            .is_err()
        );
        assert!(
            BarkdClient::new("http://localhost", "token", Network::Signet, Duration::ZERO).is_err()
        );
    }
}
