use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};

use crate::types::ApiErrorResponse;

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("Invalid argument: {0}")]
    InvalidArgument(String),
    #[error("Serialize error: {0}")]
    SerializeErr(String),
    #[error("Server error: {0}")]
    ServerErr(String),
    #[error("Database error: {0}")]
    Database(#[from] sqlx::Error),
    #[error("Expo push notification error: {0}")]
    Expo(#[from] expo_push_notification_client::CustomError),
    #[error("Anyhow error: {0}")]
    Anyhow(#[from] anyhow::Error),
    #[error("secp256k1 error: {0}")]
    Secp256k1(#[from] bitcoin::secp256k1::Error),
    #[error("Invalid Signature")]
    InvalidSignature,
    #[error("Authentication required")]
    AuthRequired,
    #[error("Invalid token")]
    InvalidToken,
    #[error("Token expired")]
    TokenExpired,
    #[error("Not found: {0}")]
    NotFound(String),
    #[error("Conflict: {0}")]
    Conflict(String),
    #[error("K1 expired")]
    K1Expired,
    #[error("User not found")]
    UserNotFound,
}

const GENERIC_SERVER_MESSAGE: &str = "Something went wrong on our end. Please try again.";

impl ApiError {
    fn status_code(&self) -> StatusCode {
        match self {
            ApiError::InvalidArgument(_) => StatusCode::BAD_REQUEST,
            ApiError::SerializeErr(_) => StatusCode::INTERNAL_SERVER_ERROR,
            ApiError::ServerErr(_) => StatusCode::INTERNAL_SERVER_ERROR,
            ApiError::Database(_) => StatusCode::INTERNAL_SERVER_ERROR,
            ApiError::Expo(_) => StatusCode::INTERNAL_SERVER_ERROR,
            ApiError::Anyhow(_) => StatusCode::INTERNAL_SERVER_ERROR,
            ApiError::Secp256k1(_) => StatusCode::BAD_REQUEST,
            ApiError::InvalidSignature => StatusCode::UNAUTHORIZED,
            ApiError::AuthRequired => StatusCode::UNAUTHORIZED,
            ApiError::InvalidToken => StatusCode::UNAUTHORIZED,
            ApiError::TokenExpired => StatusCode::UNAUTHORIZED,
            ApiError::NotFound(_) => StatusCode::NOT_FOUND,
            ApiError::Conflict(_) => StatusCode::CONFLICT,
            ApiError::K1Expired => StatusCode::UNAUTHORIZED,
            ApiError::UserNotFound => StatusCode::UNAUTHORIZED,
        }
    }

    fn error_code(&self) -> &'static str {
        match self {
            ApiError::InvalidArgument(_) => "INVALID_ARGUMENT",
            ApiError::SerializeErr(_) => "SERVER_ERROR",
            ApiError::ServerErr(_) => "SERVER_ERROR",
            ApiError::Database(_) => "SERVER_ERROR",
            ApiError::Expo(_) => "SERVER_ERROR",
            ApiError::Anyhow(_) => "SERVER_ERROR",
            ApiError::Secp256k1(_) => "CRYPTO_ERROR",
            ApiError::InvalidSignature => "INVALID_SIGNATURE",
            ApiError::AuthRequired => "AUTH_REQUIRED",
            ApiError::InvalidToken => "INVALID_TOKEN",
            ApiError::TokenExpired => "TOKEN_EXPIRED",
            ApiError::NotFound(_) => "NOT_FOUND",
            ApiError::Conflict(_) => "CONFLICT",
            ApiError::K1Expired => "K1_EXPIRED",
            ApiError::UserNotFound => "USER_NOT_FOUND",
        }
    }

    fn user_message(&self) -> String {
        match self {
            ApiError::InvalidArgument(e) => e.to_string(),
            ApiError::NotFound(e) => e.to_string(),
            ApiError::Conflict(e) => e.to_string(),
            ApiError::ServerErr(e) => e.to_string(),
            ApiError::InvalidSignature => "Invalid signature".to_string(),
            ApiError::AuthRequired => "Authentication required".to_string(),
            ApiError::InvalidToken => "Invalid token".to_string(),
            ApiError::TokenExpired => "Token expired".to_string(),
            ApiError::K1Expired => "K1 expired".to_string(),
            ApiError::UserNotFound => "User not found".to_string(),
            ApiError::SerializeErr(_)
            | ApiError::Database(_)
            | ApiError::Expo(_)
            | ApiError::Anyhow(_)
            | ApiError::Secp256k1(_) => GENERIC_SERVER_MESSAGE.to_string(),
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let status = self.status_code();
        let message = self.user_message();
        let code = self.error_code();

        // Log the error with appropriate level based on status code
        match status {
            StatusCode::BAD_REQUEST
            | StatusCode::UNAUTHORIZED
            | StatusCode::NOT_FOUND
            | StatusCode::CONFLICT => {
                tracing::warn!(
                    error_type = ?self,
                    status = %status.as_u16(),
                    reason = %message,
                    "API error (client error)"
                );
            }
            _ => {
                tracing::error!(
                    error_type = ?self,
                    status = %status.as_u16(),
                    reason = %message,
                    "API error (server error)"
                );
            }
        }

        let body = Json(ApiErrorResponse {
            status: "ERROR".to_string(),
            code: code.to_string(),
            message: message.clone(),
            reason: message,
        });

        (status, body).into_response()
    }
}
