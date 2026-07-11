use axum::body::Body;
use axum::http::{self, Request, StatusCode};
use http_body_util::BodyExt;
use serde_json::json;
use tower::ServiceExt;
use uuid::Uuid;

use crate::db::backup_repo::BackupRepository;
use crate::tests::common::{TestUser, create_test_user, setup_test_app};
use crate::types::{BackupInfo, DownloadUrlResponse, UploadUrlResponse};

#[tracing_test::traced_test]
#[tokio::test]
async fn test_get_upload_url() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/upload_url")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_version": 1
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    // Note: This test may fail in CI without proper AWS credentials
    // In a real test environment, you'd want to mock the S3 client
    if response.status() == StatusCode::OK {
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let res: UploadUrlResponse = serde_json::from_slice(&body).unwrap();
        assert!(!res.upload_url.is_empty());
        assert!(!res.s3_key.is_empty());
        assert!(res.s3_key.contains(&user.pubkey().to_string()));
        assert!(res.s3_key.contains("backup_v1.db"));
    } else {
        // If S3 is not available, we expect an internal server error
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
    }
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_v2_upload_rejects_unsupported_format_before_s3() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/v2/upload")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", user.access_token(&app_state)),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "format_version": 1,
                        "encrypted_size": 1024,
                        "encrypted_sha256": "ab".repeat(32)
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_v2_upload_rejects_oversized_object_before_s3() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/v2/upload")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", user.access_token(&app_state)),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "format_version": 2,
                        "encrypted_size": 256_u64 * 1024 * 1024 + 1,
                        "encrypted_sha256": "ab".repeat(32)
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_v2_upload_rejects_a_different_pending_object_before_s3() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;

    let backup_id = Uuid::new_v4();
    let pubkey = user.pubkey().to_string();
    BackupRepository::new(&app_state.db_pool)
        .create_pending_object(
            backup_id,
            &pubkey,
            &format!("{pubkey}/backups/{backup_id}.noahbackup"),
            2,
            1024,
            &"ab".repeat(32),
        )
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/v2/upload")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", user.access_token(&app_state)),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "format_version": 2,
                        "encrypted_size": 2048,
                        "encrypted_sha256": "cd".repeat(32)
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::CONFLICT);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_v2_repository_only_lists_completed_objects_for_owner() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let owner = TestUser::new();
    let other = TestUser::new_with_key(&[0xce; 32]);
    create_test_user(&app_state, &owner, None).await;

    let repo = BackupRepository::new(&app_state.db_pool);
    let backup_id = Uuid::new_v4();
    let owner_pubkey = owner.pubkey().to_string();
    repo.create_pending_object(
        backup_id,
        &owner_pubkey,
        &format!("{owner_pubkey}/backups/{backup_id}.noahbackup"),
        2,
        1024,
        &"ab".repeat(32),
    )
    .await
    .unwrap();

    assert!(
        repo.list_completed_objects(&owner_pubkey)
            .await
            .unwrap()
            .is_empty()
    );
    assert!(
        repo.complete_object(&owner_pubkey, backup_id)
            .await
            .unwrap()
    );

    let completed = repo.list_completed_objects(&owner_pubkey).await.unwrap();
    assert_eq!(completed.len(), 1);
    assert_eq!(completed[0].backup_id, backup_id.to_string());
    assert_eq!(completed[0].format_version, 2);
    assert_eq!(completed[0].encrypted_size, 1024);
    assert_eq!(completed[0].encrypted_sha256, "ab".repeat(32));

    assert!(
        repo.find_completed_object(&other.pubkey().to_string(), Some(backup_id))
            .await
            .unwrap()
            .is_none()
    );
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_v2_repository_reuses_one_pending_object_per_owner() {
    let (_app, app_state, _guard) = setup_test_app().await;
    let owner = TestUser::new();
    create_test_user(&app_state, &owner, None).await;

    let repo = BackupRepository::new(&app_state.db_pool);
    let owner_pubkey = owner.pubkey().to_string();
    let first_id = Uuid::new_v4();
    let first = repo
        .create_pending_object(
            first_id,
            &owner_pubkey,
            &format!("{owner_pubkey}/backups/{first_id}.noahbackup"),
            2,
            1024,
            &"ab".repeat(32),
        )
        .await
        .unwrap();

    let second_id = Uuid::new_v4();
    let second = repo
        .create_pending_object(
            second_id,
            &owner_pubkey,
            &format!("{owner_pubkey}/backups/{second_id}.noahbackup"),
            2,
            2048,
            &"cd".repeat(32),
        )
        .await
        .unwrap();

    assert_eq!(first.backup_id, first_id);
    assert_eq!(second.backup_id, first_id);
    assert_eq!(second.encrypted_size, 1024);
    assert_eq!(second.encrypted_sha256, "ab".repeat(32));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_complete_upload() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let s3_key = format!("{}/backup_v1.db", user.pubkey());

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/complete_upload")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "s3_key": s3_key,
                        "backup_version": 1,
                        "backup_size": 1024
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Verify the backup metadata was stored
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    let metadata = backup_repo
        .find_by_pubkey_and_version(&user.pubkey().to_string(), 1)
        .await
        .unwrap()
        .unwrap();

    assert_eq!(metadata.s3_key, s3_key);
    assert_eq!(metadata.backup_size, 1024);
    assert_eq!(metadata.backup_version, 1);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_complete_upload_upsert() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let s3_key = format!("{}/backup_v1.db", user.pubkey());

    // First upload
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/complete_upload")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "s3_key": s3_key,
                        "backup_version": 1,
                        "backup_size": 1024
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Second upload with same version (should update)
    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/complete_upload")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "s3_key": s3_key,
                        "backup_version": 1,
                        "backup_size": 2048
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Verify the record was updated
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    let metadata = backup_repo
        .find_by_pubkey_and_version(&user.pubkey().to_string(), 1)
        .await
        .unwrap()
        .unwrap();

    assert_eq!(metadata.backup_size, 2048);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_list_backups_empty() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/list")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: Vec<BackupInfo> = serde_json::from_slice(&body).unwrap();
    assert_eq!(res.len(), 0);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_list_backups_with_data() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // Insert test backup metadata
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    backup_repo
        .upsert_metadata(&user.pubkey().to_string(), "test/backup_v1.db", 1024, 1)
        .await
        .unwrap();
    backup_repo
        .upsert_metadata(&user.pubkey().to_string(), "test/backup_v2.db", 2048, 2)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/list")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    let body = response.into_body().collect().await.unwrap().to_bytes();
    let res: Vec<BackupInfo> = serde_json::from_slice(&body).unwrap();
    assert_eq!(res.len(), 2);

    // Check that both backups are present
    let versions: Vec<i32> = res.iter().map(|b| b.backup_version).collect();
    assert!(versions.contains(&1));
    assert!(versions.contains(&2));

    let sizes: Vec<u64> = res.iter().map(|b| b.backup_size).collect();
    assert!(sizes.contains(&1024));
    assert!(sizes.contains(&2048));
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_get_download_url_specific_version() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // Insert test backup metadata
    let s3_key = format!("{}/backup_v1.db", user.pubkey());
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    backup_repo
        .upsert_metadata(&user.pubkey().to_string(), &s3_key, 1024, 1)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/download_url")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_version": 1
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    // Note: This test may fail in CI without proper AWS credentials
    if response.status() == StatusCode::OK {
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let res: DownloadUrlResponse = serde_json::from_slice(&body).unwrap();
        assert!(!res.download_url.is_empty());
        assert_eq!(res.backup_size, 1024);
    } else {
        // If S3 is not available, we expect an internal server error
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
    }
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_get_download_url_latest() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // Insert test backup metadata with different timestamps
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    use chrono::{Duration, Utc};
    let now = Utc::now().to_rfc3339();
    let one_hour_ago = (Utc::now() - Duration::hours(1)).to_rfc3339();
    backup_repo
        .upsert_metadata_with_timestamp(
            &user.pubkey().to_string(),
            "test/backup_v1.db",
            1024,
            1,
            &one_hour_ago,
        )
        .await
        .unwrap();
    backup_repo
        .upsert_metadata_with_timestamp(
            &user.pubkey().to_string(),
            "test/backup_v2.db",
            2048,
            2,
            &now,
        )
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/download_url")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(serde_json::to_vec(&json!({})).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    // Note: This test may fail in CI without proper AWS credentials
    if response.status() == StatusCode::OK {
        let body = response.into_body().collect().await.unwrap().to_bytes();
        let res: DownloadUrlResponse = serde_json::from_slice(&body).unwrap();
        assert!(!res.download_url.is_empty());
        assert_eq!(res.backup_size, 2048); // Should get the latest (version 2)
    } else {
        // If S3 is not available, we expect an internal server error
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
    }
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_get_download_url_not_found() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/download_url")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_version": 999
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_delete_backup() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // Insert test backup metadata
    let s3_key = format!("{}/backup_v1.db", user.pubkey());
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    backup_repo
        .upsert_metadata(&user.pubkey().to_string(), &s3_key, 1024, 1)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/delete")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_version": 1
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    // Note: This test may fail in CI without proper AWS credentials
    // The S3 delete operation might fail, but the database deletion should succeed
    if response.status() == StatusCode::OK {
        // Verify the backup metadata was deleted from database
        let backup_repo = BackupRepository::new(&app_state.db_pool);
        let metadata = backup_repo
            .find_by_pubkey_and_version(&user.pubkey().to_string(), 1)
            .await
            .unwrap();
        assert!(metadata.is_none());
    } else {
        // If S3 is not available, we expect an internal server error
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
    }
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_delete_backup_not_found() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/delete")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_version": 999
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_update_backup_settings_enable() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/settings")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_enabled": true
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Verify the backup settings were stored
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    let backup_enabled = backup_repo
        .get_settings(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert!(backup_enabled);
}

#[tracing_test::traced_test]
#[tokio::test]
async fn test_update_backup_settings_disable() {
    let (app, app_state, _guard) = setup_test_app().await;
    let user = TestUser::new();
    create_test_user(&app_state, &user, None).await;
    let access_token = user.access_token(&app_state);

    // First enable backup
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    backup_repo
        .upsert_settings(&user.pubkey().to_string(), true)
        .await
        .unwrap();

    let response = app
        .oneshot(
            Request::builder()
                .method(http::Method::POST)
                .uri("/backup/settings")
                .header(http::header::CONTENT_TYPE, "application/json")
                .header(
                    http::header::AUTHORIZATION,
                    format!("Bearer {}", access_token),
                )
                .body(Body::from(
                    serde_json::to_vec(&json!({
                        "backup_enabled": false
                    }))
                    .unwrap(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);

    // Verify the backup settings were updated
    let backup_repo = BackupRepository::new(&app_state.db_pool);
    let backup_enabled = backup_repo
        .get_settings(&user.pubkey().to_string())
        .await
        .unwrap()
        .unwrap();
    assert!(!backup_enabled);
}
