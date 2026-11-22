use std::{
    net::{Ipv4Addr, SocketAddr},
    time::{Duration, Instant},
};

use axum::{
    Router,
    body::Body,
    extract::ConnectInfo,
    http::{Request, StatusCode},
};
use tower::ServiceExt;

use crate::tests::common::{TestUser, setup_test_app};

async fn status_from(
    app: &Router,
    peer: Ipv4Addr,
    method: &str,
    path: &str,
    token: Option<&str>,
) -> StatusCode {
    let mut request = Request::builder().method(method).uri(path);
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    let mut request = request.body(Body::empty()).unwrap();
    request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from((peer, 3000))));
    app.clone().oneshot(request).await.unwrap().status()
}

/// One peer is served a full `burst` and then refused; another peer is still served.
/// The limiter adds one slot per `refill`, so a slow loop earns extra successes. The cap
/// below grows with elapsed time at that rate, which means a pause can never fail the test,
/// while a route with no limiter passes the cap after about `burst` requests.
async fn assert_limited(
    method: &str,
    path: &str,
    burst: u32,
    refill: Duration,
    authenticated: bool,
) {
    let (_, state, _guard) = setup_test_app().await;
    let app = crate::app_router(state.clone());
    let token = authenticated.then(|| TestUser::new().access_token(&state));
    let token = token.as_deref();
    let first = Ipv4Addr::new(10, 0, 0, 1);
    let second = Ipv4Addr::new(10, 0, 0, 2);

    let probe = async {
        let start = Instant::now();
        let mut served = 0;
        while status_from(&app, first, method, path, token).await != StatusCode::TOO_MANY_REQUESTS {
            served += 1;
            let cap = burst + (start.elapsed().as_secs_f64() / refill.as_secs_f64()) as u32;
            assert!(
                served <= cap,
                "{method} {path} served {served} requests, quota allows {cap}"
            );
        }
        assert!(
            served >= burst,
            "{method} {path} refused request {served}, before its burst of {burst}"
        );
        assert_ne!(
            status_from(&app, second, method, path, token).await,
            StatusCode::TOO_MANY_REQUESTS,
            "{method} {path} from another peer"
        );
    };
    tokio::time::timeout(Duration::from_secs(60), probe)
        .await
        .unwrap_or_else(|_| panic!("{method} {path} did not use up its burst of {burst} in 60 s"));
}

#[tokio::test]
async fn getk1_is_rate_limited_per_peer() {
    assert_limited("GET", "/v0/getk1", 60, Duration::from_secs(5), false).await;
}

#[tokio::test]
async fn auth_login_is_rate_limited_per_peer() {
    assert_limited("POST", "/v0/auth/login", 60, Duration::from_secs(5), false).await;
}

#[tokio::test]
async fn nip05_is_rate_limited_per_peer() {
    assert_limited(
        "GET",
        "/.well-known/nostr.json?name=alice",
        60,
        Duration::from_secs(5),
        false,
    )
    .await;
}

#[tokio::test]
async fn lnurlp_is_rate_limited_per_peer() {
    assert_limited(
        "GET",
        "/.well-known/lnurlp/alice",
        60,
        Duration::from_secs(5),
        false,
    )
    .await;
}

#[tokio::test]
async fn bearer_routes_are_rate_limited_per_peer() {
    assert_limited("POST", "/v0/user_info", 120, Duration::from_secs(10), true).await;
}

#[tokio::test]
async fn fiat_routes_are_rate_limited_per_peer() {
    assert_limited("POST", "/v0/prices", 300, Duration::from_secs(20), true).await;
}
