use goose::prelude::*;
use rand::RngExt;
use serde::{Deserialize, Serialize};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU64, Ordering};

static USER_COUNTER: AtomicU64 = AtomicU64::new(0);
static TEST_USER_LN_ADDRESS: OnceLock<String> = OnceLock::new();

#[derive(Serialize, Deserialize, Debug)]
struct GetK1Response {
    k1: String,
    tag: String,
}

#[derive(Serialize, Deserialize, Debug)]
struct AuthLoginPayload {
    key: String,
    sig: String,
    k1: String,
}

#[derive(Serialize, Deserialize, Debug)]
struct AuthLoginResponse {
    access_token: String,
    token_type: String,
    expires_at: String,
    expires_in_seconds: u64,
}

#[derive(Serialize, Deserialize, Debug)]
struct AppVersionCheckPayload {
    client_version: String,
}

#[derive(Serialize, Deserialize, Debug)]
struct RegisterPayload {
    ln_address: Option<String>,
    ark_address: Option<String>,
    device_info: Option<DeviceInfo>,
}

#[derive(Serialize, Deserialize, Debug)]
struct DeviceInfo {
    device_manufacturer: Option<String>,
    device_model: Option<String>,
    os_name: Option<String>,
    os_version: Option<String>,
    app_version: Option<String>,
    app_build: Option<String>,
}

#[derive(Serialize, Deserialize, Debug)]
struct RegisterResponse {
    status: String,
    event: Option<String>,
    reason: Option<String>,
    lightning_address: Option<String>,
}

#[derive(Serialize, Deserialize, Debug)]
struct RegisterPushTokenPayload {
    push_token: String,
}

#[derive(Serialize, Deserialize, Debug)]
struct UpdateLnAddressPayload {
    ln_address: String,
}

#[derive(Serialize, Deserialize, Debug)]
struct ReportJobStatusPayload {
    notification_k1: String,
    report_type: String,
    status: String,
    error_message: Option<String>,
}

struct TestUser {
    keypair: bitcoin::key::Keypair,
    secp: bitcoin::secp256k1::Secp256k1<bitcoin::secp256k1::All>,
}

impl TestUser {
    fn new_random() -> Self {
        let secp = bitcoin::secp256k1::Secp256k1::new();
        let mut rng = rand::rng();
        let mut key_bytes = [0u8; 32];
        rng.fill(&mut key_bytes);
        let secret_key = bitcoin::secp256k1::SecretKey::from_slice(&key_bytes).unwrap();
        let keypair = bitcoin::key::Keypair::from_secret_key(&secp, &secret_key);
        Self { keypair, secp }
    }

    fn pubkey(&self) -> String {
        let pk: bitcoin::key::PublicKey = self.keypair.public_key().into();
        pk.to_string()
    }

    fn sign(&self, k1: &str) -> String {
        let hash = bitcoin::sign_message::signed_msg_hash(k1);
        let msg = bitcoin::secp256k1::Message::from_digest_slice(&hash[..]).unwrap();
        let sig = self.secp.sign_ecdsa(&msg, &self.keypair.secret_key());
        sig.to_string()
    }
}

fn leak_str(s: String) -> &'static str {
    Box::leak(s.into_boxed_str())
}

async fn get_k1_and_sign(
    user: &mut GooseUser,
    test_user: &TestUser,
    name_prefix: &'static str,
) -> Option<(String, String)> {
    let name = leak_str(format!("{}_get_k1", name_prefix));
    let response = user.get_named("/v0/getk1", name).await.ok()?;

    let k1_response: GetK1Response = match response.response {
        Ok(r) if r.status().is_success() => r.json().await.ok()?,
        _ => return None,
    };

    let sig = test_user.sign(&k1_response.k1);
    Some((k1_response.k1, sig))
}

async fn register_test_user(
    user: &mut GooseUser,
    test_user: &TestUser,
    name_prefix: &'static str,
) -> Option<String> {
    let access_token = login_test_user(user, test_user, name_prefix).await?;

    let user_num = USER_COUNTER.fetch_add(1, Ordering::SeqCst);
    let ln_address = format!("loadtest{}@localhost", user_num);

    let payload = RegisterPayload {
        ln_address: Some(ln_address.clone()),
        ark_address: None,
        device_info: Some(DeviceInfo {
            device_manufacturer: Some("LoadTest".to_string()),
            device_model: Some(format!("loadtest-device-{}", user_num)),
            os_name: Some("Android".to_string()),
            os_version: Some("14".to_string()),
            app_version: Some("1.0.0".to_string()),
            app_build: Some("1".to_string()),
        }),
    };

    let request_builder = user
        .get_request_builder(&GooseMethod::Post, "/v0/register")
        .ok()?
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {}", access_token))
        .body(serde_json::to_string(&payload).unwrap());

    let name = leak_str(format!("{}_register", name_prefix));
    let goose_request = GooseRequest::builder()
        .set_request_builder(request_builder)
        .name(name)
        .build();

    let response = user.request(goose_request).await.ok()?;

    match response.response {
        Ok(r) if r.status().is_success() => Some(ln_address),
        _ => None,
    }
}

async fn login_test_user(
    user: &mut GooseUser,
    test_user: &TestUser,
    name_prefix: &'static str,
) -> Option<String> {
    let (k1, sig) = get_k1_and_sign(user, test_user, name_prefix).await?;

    let payload = AuthLoginPayload {
        key: test_user.pubkey(),
        sig,
        k1,
    };

    let request_builder = user
        .get_request_builder(&GooseMethod::Post, "/v0/auth/login")
        .ok()?
        .header("Content-Type", "application/json")
        .body(serde_json::to_string(&payload).ok()?);

    let name = leak_str(format!("{}_auth_login", name_prefix));
    let goose_request = GooseRequest::builder()
        .set_request_builder(request_builder)
        .name(name)
        .build();

    let response = user.request(goose_request).await.ok()?;

    match response.response {
        Ok(r) if r.status().is_success() => {
            let login_response: AuthLoginResponse = r.json().await.ok()?;
            Some(login_response.access_token)
        }
        _ => None,
    }
}

async fn setup_test_user(host: &str) -> anyhow::Result<String> {
    let client = reqwest::Client::new();
    let test_user = TestUser::new_random();

    let k1_response: GetK1Response = client
        .get(format!("{}/v0/getk1", host))
        .send()
        .await?
        .json()
        .await?;

    let login_response: AuthLoginResponse = client
        .post(format!("{}/v0/auth/login", host))
        .header("Content-Type", "application/json")
        .json(&AuthLoginPayload {
            key: test_user.pubkey(),
            sig: test_user.sign(&k1_response.k1),
            k1: k1_response.k1,
        })
        .send()
        .await?
        .json()
        .await?;

    let payload = RegisterPayload {
        ln_address: None,
        ark_address: None,
        device_info: None,
    };

    let response: RegisterResponse = client
        .post(format!("{}/v0/register", host))
        .header("Content-Type", "application/json")
        .header(
            "Authorization",
            format!("Bearer {}", login_response.access_token),
        )
        .json(&payload)
        .send()
        .await?
        .json()
        .await?;

    let address = response
        .lightning_address
        .ok_or_else(|| anyhow::anyhow!("Server didn't return lightning address"))?;

    let username = address.split('@').next().unwrap_or("loadtest_user");

    println!(
        "Setup: Created test user with lightning address: {}",
        address
    );

    Ok(username.to_string())
}

// Public endpoint: GET /v0/getk1
async fn loadtest_get_k1(user: &mut GooseUser) -> TransactionResult {
    let _response = user.get_named("/v0/getk1", "get_k1").await?;
    Ok(())
}

// Public endpoint: POST /v0/app_version
async fn loadtest_check_app_version(user: &mut GooseUser) -> TransactionResult {
    let payload = AppVersionCheckPayload {
        client_version: "1.0.0".to_string(),
    };

    let request_builder = user
        .get_request_builder(&GooseMethod::Post, "/v0/app_version")?
        .header("Content-Type", "application/json")
        .body(serde_json::to_string(&payload).unwrap());

    let goose_request = GooseRequest::builder()
        .set_request_builder(request_builder)
        .name("app_version")
        .build();

    let _response = user.request(goose_request).await?;
    Ok(())
}

// Public endpoint: GET /.well-known/lnurlp/{username} (DB read)
async fn loadtest_lnurlp_request(user: &mut GooseUser) -> TransactionResult {
    let username = TEST_USER_LN_ADDRESS
        .get()
        .map(|s| s.as_str())
        .unwrap_or("loadtest_user");

    let path = format!("/.well-known/lnurlp/{}", username);
    let _response = user.get_named(&path, "lnurlp").await?;
    Ok(())
}

// Private endpoint: GET /health
async fn loadtest_health_check(user: &mut GooseUser) -> TransactionResult {
    let _response = user.get_named("/health", "health_check").await?;
    Ok(())
}

// Full registration flow: get k1 -> sign -> register (DB write)
async fn loadtest_registration_flow(user: &mut GooseUser) -> TransactionResult {
    let test_user = TestUser::new_random();
    let _ = register_test_user(user, &test_user, "registration").await;
    Ok(())
}

// Register + get user info (DB write + read)
async fn loadtest_get_user_info(user: &mut GooseUser) -> TransactionResult {
    let test_user = TestUser::new_random();
    let access_token = match login_test_user(user, &test_user, "userinfo").await {
        Some(token) => token,
        None => return Ok(()),
    };

    if register_test_user(user, &test_user, "userinfo_register")
        .await
        .is_none()
    {
        return Ok(());
    }

    let request_builder = user
        .get_request_builder(&GooseMethod::Post, "/v0/user_info")?
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {}", access_token))
        .body("{}");

    let goose_request = GooseRequest::builder()
        .set_request_builder(request_builder)
        .name("user_info")
        .build();

    let _response = user.request(goose_request).await?;
    Ok(())
}

// Register + register push token (DB write + write)
async fn loadtest_register_push_token(user: &mut GooseUser) -> TransactionResult {
    let test_user = TestUser::new_random();
    let access_token = match login_test_user(user, &test_user, "pushtoken").await {
        Some(token) => token,
        None => return Ok(()),
    };

    if register_test_user(user, &test_user, "pushtoken_register")
        .await
        .is_none()
    {
        return Ok(());
    }

    let user_num = USER_COUNTER.fetch_add(1, Ordering::SeqCst);
    let payload = RegisterPushTokenPayload {
        push_token: format!("loadtest_push_token_{}", user_num),
    };

    let request_builder = user
        .get_request_builder(&GooseMethod::Post, "/v0/register_push_token")?
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {}", access_token))
        .body(serde_json::to_string(&payload).unwrap());

    let goose_request = GooseRequest::builder()
        .set_request_builder(request_builder)
        .name("register_push_token")
        .build();

    let _response = user.request(goose_request).await?;
    Ok(())
}

// Register + update ln address (DB write + write)
async fn loadtest_update_ln_address(user: &mut GooseUser) -> TransactionResult {
    let test_user = TestUser::new_random();
    let access_token = match login_test_user(user, &test_user, "update_ln").await {
        Some(token) => token,
        None => return Ok(()),
    };

    if register_test_user(user, &test_user, "update_ln_register")
        .await
        .is_none()
    {
        return Ok(());
    }

    let user_num = USER_COUNTER.fetch_add(1, Ordering::SeqCst);
    let payload = UpdateLnAddressPayload {
        ln_address: format!("updated{}@localhost", user_num),
    };

    let request_builder = user
        .get_request_builder(&GooseMethod::Post, "/v0/update_ln_address")?
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {}", access_token))
        .body(serde_json::to_string(&payload).unwrap());

    let goose_request = GooseRequest::builder()
        .set_request_builder(request_builder)
        .name("update_ln_address")
        .build();

    let _response = user.request(goose_request).await?;
    Ok(())
}

// Register + report job status (DB write + write)
async fn loadtest_report_job_status(user: &mut GooseUser) -> TransactionResult {
    let test_user = TestUser::new_random();
    let access_token = match login_test_user(user, &test_user, "job_status").await {
        Some(token) => token,
        None => return Ok(()),
    };

    if register_test_user(user, &test_user, "job_status_register")
        .await
        .is_none()
    {
        return Ok(());
    }

    let payload = ReportJobStatusPayload {
        notification_k1: "loadtest-notification-k1".to_string(),
        report_type: "Maintenance".to_string(),
        status: "Success".to_string(),
        error_message: None,
    };

    let request_builder = user
        .get_request_builder(&GooseMethod::Post, "/v0/report_job_status")?
        .header("Content-Type", "application/json")
        .header("Authorization", format!("Bearer {}", access_token))
        .body(serde_json::to_string(&payload).unwrap());

    let goose_request = GooseRequest::builder()
        .set_request_builder(request_builder)
        .name("report_job_status")
        .build();

    let _response = user.request(goose_request).await?;
    Ok(())
}

fn build_public_scenario() -> Scenario {
    scenario!("Public Endpoints")
        .register_transaction(transaction!(loadtest_get_k1).set_weight(3).unwrap())
        .register_transaction(
            transaction!(loadtest_check_app_version)
                .set_weight(2)
                .unwrap(),
        )
        .register_transaction(transaction!(loadtest_lnurlp_request).set_weight(1).unwrap())
}

fn build_registration_scenario() -> Scenario {
    scenario!("Registration Flow").register_transaction(
        transaction!(loadtest_registration_flow)
            .set_weight(1)
            .unwrap(),
    )
}

fn build_authenticated_scenario() -> Scenario {
    scenario!("Authenticated Operations")
        .register_transaction(transaction!(loadtest_get_user_info).set_weight(1).unwrap())
}

fn build_health_scenario() -> Scenario {
    scenario!("Health Check").register_transaction(transaction!(loadtest_health_check))
}

fn build_db_stress_scenario() -> Scenario {
    scenario!("DB Stress Test")
        .register_transaction(
            transaction!(loadtest_registration_flow)
                .set_weight(3)
                .unwrap(),
        )
        .register_transaction(transaction!(loadtest_get_user_info).set_weight(2).unwrap())
        .register_transaction(
            transaction!(loadtest_register_push_token)
                .set_weight(2)
                .unwrap(),
        )
        .register_transaction(
            transaction!(loadtest_update_ln_address)
                .set_weight(2)
                .unwrap(),
        )
        .register_transaction(
            transaction!(loadtest_report_job_status)
                .set_weight(2)
                .unwrap(),
        )
        .register_transaction(transaction!(loadtest_lnurlp_request).set_weight(3).unwrap())
}

#[tokio::main]
async fn main() -> Result<(), GooseError> {
    // Available scenarios: public, registration, authenticated, health, dbstress, all
    let scenario_name = std::env::var("LOADTEST_SCENARIO").unwrap_or_else(|_| "public".to_string());
    let scenario_name = scenario_name.as_str();

    let host =
        std::env::var("LOADTEST_HOST").unwrap_or_else(|_| "http://localhost:3000".to_string());

    if scenario_name == "public" || scenario_name == "all" || scenario_name == "dbstress" {
        match setup_test_user(&host).await {
            Ok(username) => {
                let _ = TEST_USER_LN_ADDRESS.set(username);
            }
            Err(e) => {
                eprintln!("Warning: Failed to setup test user for lnurlp tests: {}", e);
                eprintln!("lnurlp tests will likely fail with 400 errors");
            }
        }
    }

    let mut attack = match scenario_name {
        "public" => GooseAttack::initialize()?.register_scenario(build_public_scenario()),
        "registration" => {
            GooseAttack::initialize()?.register_scenario(build_registration_scenario())
        }
        "authenticated" => {
            GooseAttack::initialize()?.register_scenario(build_authenticated_scenario())
        }
        "health" => GooseAttack::initialize()?.register_scenario(build_health_scenario()),
        "dbstress" => GooseAttack::initialize()?.register_scenario(build_db_stress_scenario()),
        "all" => GooseAttack::initialize()?
            .register_scenario(build_public_scenario())
            .register_scenario(build_registration_scenario())
            .register_scenario(build_db_stress_scenario()),
        _ => {
            eprintln!(
                "Unknown scenario: {}. Available: public, registration, authenticated, health, dbstress, all",
                scenario_name
            );
            std::process::exit(1);
        }
    };

    attack = *attack.set_default(GooseDefault::Host, host.as_str())?;

    attack.execute().await?;
    Ok(())
}
