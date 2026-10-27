use std::net::{IpAddr, SocketAddr};

use axum::{body::Body, extract::ConnectInfo, http::Request};
use tower_governor::{
    GovernorLayer,
    errors::GovernorError,
    governor::GovernorConfigBuilder,
    key_extractor::{KeyExtractor, SmartIpKeyExtractor},
};

const FLY_CLIENT_IP_HEADER: &str = "fly-client-ip";
const RAILWAY_CLIENT_IP_HEADER: &str = "x-real-ip";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProxyClientIpKeyExtractor {
    header: &'static str,
}

impl KeyExtractor for ProxyClientIpKeyExtractor {
    type Key = IpAddr;

    fn extract<T>(&self, request: &Request<T>) -> Result<Self::Key, GovernorError> {
        if let Some(value) = request.headers().get(self.header) {
            return value
                .to_str()
                .ok()
                .and_then(|value| value.trim().parse().ok())
                .ok_or(GovernorError::UnableToExtractKey);
        }

        request
            .extensions()
            .get::<ConnectInfo<SocketAddr>>()
            .map(|address| address.0.ip())
            .or_else(|| request.extensions().get::<SocketAddr>().map(SocketAddr::ip))
            .ok_or(GovernorError::UnableToExtractKey)
    }
}

// Type alias to simplify the return type
type RateLimiter = GovernorLayer<
    SmartIpKeyExtractor,
    governor::middleware::NoOpMiddleware<governor::clock::QuantaInstant>,
    Body,
>;

type ProxyRateLimiter = GovernorLayer<
    ProxyClientIpKeyExtractor,
    governor::middleware::NoOpMiddleware<governor::clock::QuantaInstant>,
    Body,
>;

/// Creates a rate limiting layer for public endpoints like getk1
/// This is more restrictive to prevent abuse
pub fn create_public_rate_limiter() -> RateLimiter {
    let config = GovernorConfigBuilder::default()
        .per_second(5)
        .burst_size(60)
        .key_extractor(SmartIpKeyExtractor)
        .finish()
        .expect("Failed to create rate limiter config");

    GovernorLayer::new(config)
}

/// Creates a strict public limiter using the hosting proxy's client-IP header.
/// Local requests fall back to their peer address so development remains usable.
pub fn create_lnurl_rate_limiter() -> ProxyRateLimiter {
    let header = if std::env::var_os("RAILWAY_SERVICE_ID").is_some() {
        RAILWAY_CLIENT_IP_HEADER
    } else {
        FLY_CLIENT_IP_HEADER
    };
    let config = GovernorConfigBuilder::default()
        .per_second(5)
        .burst_size(60)
        .key_extractor(ProxyClientIpKeyExtractor { header })
        .finish()
        .expect("Failed to create LNURL rate limiter config");

    GovernorLayer::new(config)
}

/// Creates a rate limiting layer for authenticated endpoints
/// This is less restrictive as users are already authenticated
pub fn create_auth_rate_limiter() -> RateLimiter {
    let config = GovernorConfigBuilder::default()
        .per_second(10)
        .burst_size(120)
        .key_extractor(SmartIpKeyExtractor)
        .finish()
        .expect("Failed to create rate limiter config");

    GovernorLayer::new(config)
}

/// Creates a rate limiting layer for authenticated fiat price endpoints.
/// These endpoints read cached server-side data, so allow a larger burst than the
/// general authenticated API without loosening unrelated protected routes.
pub fn create_fiat_rate_limiter() -> RateLimiter {
    let config = GovernorConfigBuilder::default()
        .per_second(20)
        .burst_size(300)
        .key_extractor(SmartIpKeyExtractor)
        .finish()
        .expect("Failed to create rate limiter config");

    GovernorLayer::new(config)
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};

    use axum::{
        body::Body,
        extract::ConnectInfo,
        http::{HeaderValue, Request},
    };
    use tower_governor::{errors::GovernorError, key_extractor::KeyExtractor};

    use super::{FLY_CLIENT_IP_HEADER, ProxyClientIpKeyExtractor, RAILWAY_CLIENT_IP_HEADER};

    #[test]
    fn trusts_fly_client_ip_instead_of_forwarded_headers() {
        let mut request = Request::new(Body::empty());
        request
            .headers_mut()
            .insert("fly-client-ip", HeaderValue::from_static("203.0.113.10"));
        request
            .headers_mut()
            .insert("x-forwarded-for", HeaderValue::from_static("198.51.100.20"));
        request
            .extensions_mut()
            .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 3000))));

        assert_eq!(
            ProxyClientIpKeyExtractor {
                header: FLY_CLIENT_IP_HEADER
            }
            .extract(&request)
            .unwrap(),
            IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10))
        );
    }

    #[test]
    fn falls_back_to_peer_ip_without_a_fly_header() {
        let mut request = Request::new(Body::empty());
        request
            .extensions_mut()
            .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 3000))));

        assert_eq!(
            ProxyClientIpKeyExtractor {
                header: FLY_CLIENT_IP_HEADER
            }
            .extract(&request)
            .unwrap(),
            IpAddr::V4(Ipv4Addr::LOCALHOST)
        );
    }

    #[test]
    fn rejects_a_malformed_fly_client_ip() {
        let mut request = Request::new(Body::empty());
        request
            .headers_mut()
            .insert("fly-client-ip", HeaderValue::from_static("not-an-ip"));
        request
            .extensions_mut()
            .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 3000))));

        assert!(matches!(
            ProxyClientIpKeyExtractor {
                header: FLY_CLIENT_IP_HEADER
            }
            .extract(&request),
            Err(GovernorError::UnableToExtractKey)
        ));
    }

    #[test]
    fn trusts_railway_real_ip_instead_of_fly_or_forwarded_headers() {
        let mut request = Request::new(Body::empty());
        request
            .headers_mut()
            .insert("x-real-ip", HeaderValue::from_static("2001:db8::42"));
        request
            .headers_mut()
            .insert("fly-client-ip", HeaderValue::from_static("203.0.113.10"));
        request
            .headers_mut()
            .insert("x-forwarded-for", HeaderValue::from_static("198.51.100.20"));
        request
            .extensions_mut()
            .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 3000))));

        assert_eq!(
            ProxyClientIpKeyExtractor {
                header: RAILWAY_CLIENT_IP_HEADER
            }
            .extract(&request)
            .unwrap(),
            "2001:db8::42".parse::<IpAddr>().unwrap()
        );
    }

    #[test]
    fn railway_does_not_trust_a_fly_header_when_real_ip_is_missing() {
        let mut request = Request::new(Body::empty());
        request
            .headers_mut()
            .insert("fly-client-ip", HeaderValue::from_static("203.0.113.10"));
        request
            .extensions_mut()
            .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 3000))));

        assert_eq!(
            ProxyClientIpKeyExtractor {
                header: RAILWAY_CLIENT_IP_HEADER
            }
            .extract(&request)
            .unwrap(),
            IpAddr::V4(Ipv4Addr::LOCALHOST)
        );
    }

    #[test]
    fn rejects_a_malformed_railway_real_ip() {
        let mut request = Request::new(Body::empty());
        request
            .headers_mut()
            .insert("x-real-ip", HeaderValue::from_static("not-an-ip"));
        request
            .headers_mut()
            .insert("fly-client-ip", HeaderValue::from_static("203.0.113.10"));
        request
            .extensions_mut()
            .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 3000))));

        assert!(matches!(
            ProxyClientIpKeyExtractor {
                header: RAILWAY_CLIENT_IP_HEADER
            }
            .extract(&request),
            Err(GovernorError::UnableToExtractKey)
        ));
    }
}
