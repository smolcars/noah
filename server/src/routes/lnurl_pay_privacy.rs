use axum::{
    extract::{OriginalUri, Request},
    http::{Uri, uri::PathAndQuery},
    middleware::Next,
    response::{IntoResponse, Response},
};

use crate::errors::ApiError;

const LNURL_PAY_PATH_PREFIX: &str = "/.well-known/lnurlp/";
const MAX_QUERY_BYTES: usize = 7 * 1024;

#[derive(Debug, Clone, Default)]
pub struct LnurlPaySensitiveQuery {
    pub payer_data: Option<String>,
    pub comment: Option<String>,
}

pub async fn redact_lnurl_pay_query_middleware(
    mut request: Request,
    next: Next,
) -> Result<Response, Response> {
    if !request.uri().path().starts_with(LNURL_PAY_PATH_PREFIX) {
        return Ok(next.run(request).await);
    }

    let Some(raw_query) = request.uri().query() else {
        request
            .extensions_mut()
            .insert(LnurlPaySensitiveQuery::default());
        return Ok(next.run(request).await);
    };

    if raw_query.len() > MAX_QUERY_BYTES {
        return Err(
            ApiError::InvalidArgument("LNURL-pay callback query is too large".to_string())
                .into_response(),
        );
    }

    let mut sensitive = LnurlPaySensitiveQuery::default();
    let mut public_pairs = Vec::new();
    for (key, value) in form_urlencoded::parse(raw_query.as_bytes()) {
        if key.eq_ignore_ascii_case("payerdata") {
            if sensitive.payer_data.replace(value.into_owned()).is_some() {
                return Err(ApiError::InvalidArgument(
                    "Duplicate LNURL-pay payerdata parameter".to_string(),
                )
                .into_response());
            }
        } else if key.eq_ignore_ascii_case("comment") {
            if sensitive.comment.replace(value.into_owned()).is_some() {
                return Err(ApiError::InvalidArgument(
                    "Duplicate LNURL-pay comment parameter".to_string(),
                )
                .into_response());
            }
        } else {
            public_pairs.push((key.into_owned(), value.into_owned()));
        }
    }

    let sanitized_query = form_urlencoded::Serializer::new(String::new())
        .extend_pairs(public_pairs)
        .finish();
    let path_and_query = if sanitized_query.is_empty() {
        request.uri().path().to_string()
    } else {
        format!("{}?{}", request.uri().path(), sanitized_query)
    };
    let mut parts = request.uri().clone().into_parts();
    parts.path_and_query = Some(
        path_and_query
            .parse::<PathAndQuery>()
            .map_err(|_| ApiError::InvalidArgument("Invalid LNURL-pay callback query".to_string()))
            .map_err(IntoResponse::into_response)?,
    );
    let sanitized_uri = Uri::from_parts(parts)
        .map_err(|_| ApiError::InvalidArgument("Invalid LNURL-pay callback URI".to_string()))
        .map_err(IntoResponse::into_response)?;
    *request.uri_mut() = sanitized_uri.clone();
    request.extensions_mut().insert(OriginalUri(sanitized_uri));
    request.extensions_mut().insert(sensitive);

    Ok(next.run(request).await)
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Router, extract::Extension, http::StatusCode, routing::get};
    use tower::ServiceExt;

    #[tokio::test]
    async fn strips_sensitive_values_before_inner_layers() {
        async fn handler(
            Extension(sensitive): Extension<LnurlPaySensitiveQuery>,
            request: Request,
        ) -> String {
            format!(
                "{}|{}|{}",
                request.uri(),
                sensitive.payer_data.as_deref().unwrap_or_default(),
                sensitive.comment.as_deref().unwrap_or_default()
            )
        }

        let app = Router::new()
            .route("/.well-known/lnurlp/{username}", get(handler))
            .layer(axum::middleware::from_fn(redact_lnurl_pay_query_middleware));
        let response = app
            .oneshot(
                Request::builder()
                    .uri("/.well-known/lnurlp/test?amount=1000&PayerData=%7B%7D&CoMmEnT=private")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let body = http_body_util::BodyExt::collect(response.into_body())
            .await
            .unwrap()
            .to_bytes();
        assert_eq!(
            std::str::from_utf8(&body).unwrap(),
            "/.well-known/lnurlp/test?amount=1000|{}|private"
        );
    }

    #[tokio::test]
    async fn accepts_maximum_unicode_name_and_comment() {
        async fn handler(Extension(sensitive): Extension<LnurlPaySensitiveQuery>) -> StatusCode {
            assert!(sensitive.payer_data.is_some());
            assert!(sensitive.comment.is_some());
            StatusCode::NO_CONTENT
        }

        let payer_data = serde_json::json!({
            "name": "😀".repeat(80),
            "identifier": "alice@example.com",
        })
        .to_string();
        let comment = "😀".repeat(280);
        let raw_query = form_urlencoded::Serializer::new(String::new())
            .append_pair("amount", "1000")
            .append_pair("payerdata", &payer_data)
            .append_pair("comment", &comment)
            .finish();
        assert!(raw_query.len() > 4 * 1024);
        assert!(raw_query.len() <= MAX_QUERY_BYTES);

        let app = Router::new()
            .route("/.well-known/lnurlp/{username}", get(handler))
            .layer(axum::middleware::from_fn(redact_lnurl_pay_query_middleware));
        let response = app
            .oneshot(
                Request::builder()
                    .uri(format!("/.well-known/lnurlp/test?{raw_query}"))
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::NO_CONTENT);
    }

    #[tokio::test]
    async fn rejects_queries_larger_than_limit() {
        let prefix = "padding=";
        let raw_query = format!("{prefix}{}", "a".repeat(MAX_QUERY_BYTES + 1 - prefix.len()));
        assert_eq!(raw_query.len(), MAX_QUERY_BYTES + 1);

        let app = Router::new()
            .route(
                "/.well-known/lnurlp/{username}",
                get(|| async { StatusCode::NO_CONTENT }),
            )
            .layer(axum::middleware::from_fn(redact_lnurl_pay_query_middleware));
        let response = app
            .oneshot(
                Request::builder()
                    .uri(format!("/.well-known/lnurlp/test?{raw_query}"))
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();

        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }
}
