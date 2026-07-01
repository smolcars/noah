use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use expo_push_notification_client::{Expo, ExpoClientOptions, ExpoPushMessage, Sound};
use server::config::Config;
use server::mailbox_auth::backfill_mailbox_authorizations;
use sqlx::postgres::PgPoolOptions;

#[derive(Parser)]
#[command(name = "noah-cli")]
#[command(about = "CLI tool for Noah server administration", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Send a push notification to all users
    Broadcast {
        /// Notification title
        #[arg(short, long)]
        title: String,

        /// Notification body
        #[arg(short, long)]
        body: String,

        /// Dry run - don't actually send, just show what would be sent
        #[arg(long, default_value = "false")]
        dry_run: bool,
    },

    /// Show statistics about registered users
    Stats,

    /// Seed deterministic users for local autocomplete testing
    SeedUsers {
        /// Number of users to attempt to seed
        #[arg(long, default_value_t = 50)]
        count: usize,

        /// Start index for deterministic generation (useful for multiple batches)
        #[arg(long, default_value_t = 0)]
        start_index: usize,

        /// Override domain for generated lightning addresses
        #[arg(long)]
        domain: Option<String>,

        /// Dry run - print generated users without inserting
        #[arg(long, default_value_t = false)]
        dry_run: bool,
    },

    /// Validate and normalize existing active mailbox authorizations
    BackfillMailboxAuth {
        /// Dry run - classify rows without updating the database
        #[arg(long, default_value_t = false)]
        dry_run: bool,
    },
}

fn is_expo_token(token: &str) -> bool {
    token.starts_with("ExponentPushToken[") && token.ends_with(']')
}

const SEED_BASE_USERNAMES: &[&str] = &[
    "alice", "alicia", "alina", "aline", "albert", "alex", "alexa", "alexis", "alfred", "alfie",
    "alvaro", "amanda", "amelia", "andrew", "andy", "anna", "annie", "arthur", "ben", "benji",
    "blake", "bob", "bobby", "bonnie", "carol", "caroline", "carl", "charlie", "chloe", "daisy",
    "dan", "dani", "david", "dora", "edward", "eli", "emma", "emily", "ethan", "fiona", "frank",
    "george", "gina", "harry", "helen", "ian", "ivy", "jack", "jane", "jason", "julia", "karen",
    "kevin", "leo", "liam", "lily", "maria", "mason", "mia", "noah", "olivia", "oscar", "peter",
    "quinn", "rachel", "ryan", "sara", "sophia", "thomas", "victor", "zoe",
];

fn seed_username_for_index(index: usize) -> String {
    if index < SEED_BASE_USERNAMES.len() {
        return SEED_BASE_USERNAMES[index].to_string();
    }

    let base = SEED_BASE_USERNAMES[index % SEED_BASE_USERNAMES.len()];
    let suffix = index / SEED_BASE_USERNAMES.len();
    format!("{base}{suffix:03}")
}

fn seed_pubkey_for_index(index: usize) -> String {
    format!("{:064x}", index.saturating_add(1))
}

async fn cmd_broadcast(config: &Config, title: String, body: String, dry_run: bool) -> Result<()> {
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&config.postgres_url)
        .await
        .context("Failed to connect to database")?;

    let tokens: Vec<String> = sqlx::query_scalar("SELECT push_token FROM push_tokens")
        .fetch_all(&pool)
        .await
        .context("Failed to fetch push tokens")?;

    let expo_tokens: Vec<_> = tokens.into_iter().filter(|t| is_expo_token(t)).collect();

    if expo_tokens.is_empty() {
        println!("No Expo push tokens registered. Nothing to send.");
        return Ok(());
    }

    println!("Found {} Expo tokens", expo_tokens.len());
    println!();
    println!("Title: {}", title);
    println!("Body: {}", body);

    if dry_run {
        println!();
        println!("Dry run - no notifications sent.");
        return Ok(());
    }

    println!();
    print!("Send notifications? [y/N]: ");
    use std::io::{self, Write};
    io::stdout().flush()?;

    let mut input = String::new();
    io::stdin().read_line(&mut input)?;

    if !input.trim().eq_ignore_ascii_case("y") {
        println!("Aborted.");
        return Ok(());
    }

    println!();
    println!("Sending...");

    let expo = Expo::new(ExpoClientOptions {
        access_token: Some(config.expo_access_token.clone()),
    });

    let mut success_count = 0;
    let mut error_count = 0;

    let chunks: Vec<Vec<String>> = expo_tokens.chunks(100).map(|c| c.to_vec()).collect();

    for (i, chunk) in chunks.iter().enumerate() {
        let message = ExpoPushMessage::builder(chunk.clone())
            .title(&title)
            .body(&body)
            .sound(Sound::Default)
            .build();

        match message {
            Ok(msg) => match expo.send_push_notifications(msg).await {
                Ok(_) => {
                    success_count += chunk.len();
                    println!(
                        "  Sent batch {}/{} ({} tokens)",
                        i + 1,
                        chunks.len(),
                        chunk.len()
                    );
                }
                Err(e) => {
                    error_count += chunk.len();
                    eprintln!("  Failed batch {}: {}", i + 1, e);
                }
            },
            Err(e) => {
                error_count += chunk.len();
                eprintln!("  Failed to build message for batch {}: {}", i + 1, e);
            }
        }
    }

    println!();
    println!("Done!");
    println!("  Successful: {}", success_count);
    println!("  Failed: {}", error_count);

    Ok(())
}

async fn cmd_stats(config: &Config) -> Result<()> {
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&config.postgres_url)
        .await
        .context("Failed to connect to database")?;

    let user_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM users")
        .fetch_one(&pool)
        .await
        .unwrap_or(0);

    let push_token_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM push_tokens")
        .fetch_one(&pool)
        .await
        .unwrap_or(0);

    let tokens: Vec<String> = sqlx::query_scalar("SELECT push_token FROM push_tokens")
        .fetch_all(&pool)
        .await
        .unwrap_or_default();

    let expo_count = tokens.iter().filter(|t| is_expo_token(t)).count();

    println!("Noah Server Statistics");
    println!("======================");
    println!("Total users: {}", user_count);
    println!("Push tokens: {}", push_token_count);
    println!("  - Expo: {}", expo_count);

    Ok(())
}

async fn cmd_seed_users(
    config: &Config,
    count: usize,
    start_index: usize,
    domain: Option<String>,
    dry_run: bool,
) -> Result<()> {
    if count == 0 {
        println!("No users requested (count=0). Nothing to seed.");
        return Ok(());
    }

    let effective_domain = domain
        .unwrap_or_else(|| config.lnurl_domain.clone())
        .trim()
        .to_lowercase();

    if effective_domain.is_empty() {
        anyhow::bail!("Effective domain cannot be empty");
    }

    println!(
        "Seeding users: count={}, start_index={}, domain={}, dry_run={}",
        count, start_index, effective_domain, dry_run
    );

    if dry_run {
        let preview_count = count.min(10);
        println!("Previewing first {} generated users:", preview_count);

        for offset in 0..preview_count {
            let index = start_index + offset;
            let username = seed_username_for_index(index);
            let pubkey = seed_pubkey_for_index(index);
            let lightning_address = format!("{username}@{effective_domain}");
            println!("  {} | {} | {}", index, pubkey, lightning_address);
        }

        if count > preview_count {
            println!("  ... and {} more", count - preview_count);
        }

        println!("Dry run complete. No database changes made.");
        return Ok(());
    }

    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&config.postgres_url)
        .await
        .context("Failed to connect to database")?;

    let mut inserted = 0usize;
    let mut skipped = 0usize;

    for offset in 0..count {
        let index = start_index + offset;
        let username = seed_username_for_index(index);
        let pubkey = seed_pubkey_for_index(index);
        let lightning_address = format!("{username}@{effective_domain}");

        let result = sqlx::query(
            "INSERT INTO users (pubkey, lightning_address) VALUES ($1, $2) ON CONFLICT DO NOTHING",
        )
        .bind(&pubkey)
        .bind(&lightning_address)
        .execute(&pool)
        .await
        .with_context(|| format!("Failed to insert seed user {}", lightning_address))?;

        if result.rows_affected() == 1 {
            inserted += 1;
        } else {
            skipped += 1;
        }
    }

    println!(
        "Seed complete: attempted={}, inserted={}, skipped={}",
        count, inserted, skipped
    );

    Ok(())
}

async fn cmd_backfill_mailbox_auth(config: &Config, dry_run: bool) -> Result<()> {
    let pool = PgPoolOptions::new()
        .max_connections(5)
        .connect(&config.postgres_url)
        .await
        .context("Failed to connect to database")?;

    let report = backfill_mailbox_authorizations(&pool, dry_run)
        .await
        .context("Failed to backfill mailbox authorizations")?;

    println!("Mailbox authorization backfill complete");
    println!("  dry_run: {}", dry_run);
    println!("  checked: {}", report.checked);
    println!("  valid: {}", report.valid);
    println!("  normalized: {}", report.normalized);
    println!("  expired: {}", report.expired);
    println!("  invalid: {}", report.invalid);

    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();

    let config = Config::load()?;

    match cli.command {
        Commands::Broadcast {
            title,
            body,
            dry_run,
        } => {
            cmd_broadcast(&config, title, body, dry_run).await?;
        }
        Commands::Stats => {
            cmd_stats(&config).await?;
        }
        Commands::SeedUsers {
            count,
            start_index,
            domain,
            dry_run,
        } => {
            cmd_seed_users(&config, count, start_index, domain, dry_run).await?;
        }
        Commands::BackfillMailboxAuth { dry_run } => {
            cmd_backfill_mailbox_auth(&config, dry_run).await?;
        }
    }

    Ok(())
}
