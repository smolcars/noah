use std::net::{Ipv4Addr, SocketAddr};

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

/// One peer gets `burst` requests through, then 429; another peer is still served.
async fn assert_limited(method: &str, path: &str, burst: usize, authenticated: bool) {
    let (_, state, _guard) = setup_test_app().await;
    let app = crate::app_router(state.clone());
    let token = authenticated.then(|| TestUser::new().access_token(&state));
    let token = token.as_deref();
    let first = Ipv4Addr::new(10, 0, 0, 1);
    let second = Ipv4Addr::new(10, 0, 0, 2);

    for attempt in 0..burst {
        let status = status_from(&app, first, method, path, token).await;
        assert_ne!(
            status,
            StatusCode::TOO_MANY_REQUESTS,
            "{method} {path} request {attempt}"
        );
    }
    assert_eq!(
        status_from(&app, first, method, path, token).await,
        StatusCode::TOO_MANY_REQUESTS,
        "{method} {path} past its quota"
    );
    assert_ne!(
        status_from(&app, second, method, path, token).await,
        StatusCode::TOO_MANY_REQUESTS,
        "{method} {path} from another peer"
    );
}

#[tokio::test]
async fn getk1_is_rate_limited_per_peer() {
    assert_limited("GET", "/v0/getk1", 60, false).await;
}

#[tokio::test]
async fn auth_login_is_rate_limited_per_peer() {
    assert_limited("POST", "/v0/auth/login", 60, false).await;
}

#[tokio::test]
async fn nip05_is_rate_limited_per_peer() {
    assert_limited("GET", "/.well-known/nostr.json?name=alice", 60, false).await;
}

#[tokio::test]
async fn lnurlp_is_rate_limited_per_peer() {
    assert_limited("GET", "/.well-known/lnurlp/alice", 60, false).await;
}

#[tokio::test]
async fn bearer_routes_are_rate_limited_per_peer() {
    assert_limited("POST", "/v0/user_info", 120, true).await;
}

#[tokio::test]
async fn fiat_routes_are_rate_limited_per_peer() {
    assert_limited("POST", "/v0/prices", 300, true).await;
}
