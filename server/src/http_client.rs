use std::time::Duration;

/// Starts every outbound HTTP client, so none can wait on a stalled peer forever.
/// `timeout` bounds each whole request, connect included.
#[allow(clippy::disallowed_methods)] // the one place a client may be built
pub fn outbound(timeout: Duration) -> reqwest::ClientBuilder {
    reqwest::Client::builder().timeout(timeout)
}
