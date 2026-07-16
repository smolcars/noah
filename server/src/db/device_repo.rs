use anyhow::Result;
use sqlx::{Postgres, Transaction};

use crate::types::DeviceInfo;

/// A struct to encapsulate device-related database operations.
/// It's currently an empty struct because its methods operate on transactions
/// passed in from other functions, rather than holding its own connection.
pub struct DeviceRepository;

impl DeviceRepository {
    /// Inserts a new device record, or updates an existing one if the pubkey already exists.
    /// This operation is performed within a given transaction to ensure atomicity.
    pub async fn upsert(
        tx: &mut Transaction<'_, Postgres>,
        pubkey: &str,
        device_info: &DeviceInfo,
    ) -> Result<()> {
        sqlx::query(
            "INSERT INTO devices (
                 pubkey,
                 device_manufacturer,
                 device_model,
                 os_name,
                 os_version,
                 app_version,
                 app_build
             )
             VALUES ($1, $2, $3, $4, $5, $6, $7)
             ON CONFLICT(pubkey) DO UPDATE SET
                 device_manufacturer = excluded.device_manufacturer,
                 device_model = excluded.device_model,
                 os_name = excluded.os_name,
                 os_version = excluded.os_version,
                 app_version = excluded.app_version,
                 app_build = COALESCE(excluded.app_build, devices.app_build),
                 updated_at = now()",
        )
        .bind(pubkey)
        .bind(device_info.device_manufacturer.clone())
        .bind(device_info.device_model.clone())
        .bind(device_info.os_name.clone())
        .bind(device_info.os_version.clone())
        .bind(device_info.app_version.clone())
        .bind(device_info.app_build.clone())
        .execute(&mut **tx)
        .await?;
        Ok(())
    }

    #[cfg(test)]
    pub async fn find_by_pubkey(pool: &sqlx::PgPool, pubkey: &str) -> Result<Option<DeviceInfo>> {
        let row = sqlx::query_as::<
            _,
            (
                Option<String>,
                Option<String>,
                Option<String>,
                Option<String>,
                Option<String>,
                Option<String>,
            ),
        >(
            "SELECT
                 device_manufacturer,
                 device_model,
                 os_name,
                 os_version,
                 app_version,
                 app_build
             FROM devices
             WHERE pubkey = $1",
        )
        .bind(pubkey)
        .fetch_optional(pool)
        .await?;

        Ok(row.map(
            |(device_manufacturer, device_model, os_name, os_version, app_version, app_build)| {
                DeviceInfo {
                    device_manufacturer,
                    device_model,
                    os_name,
                    os_version,
                    app_version,
                    app_build,
                }
            },
        ))
    }
}
