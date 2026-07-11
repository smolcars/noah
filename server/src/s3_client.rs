use aws_config::BehaviorVersion;
use aws_config::meta::region::RegionProviderChain;
use aws_sdk_s3::Client;
use aws_sdk_s3::presigning::PresigningConfig;
use std::time::Duration;

pub struct S3BackupClient {
    client: Client,
    bucket: String,
}

pub struct S3ObjectInfo {
    pub size: u64,
    pub checksum_sha256: Option<String>,
}

impl S3BackupClient {
    pub async fn new(bucket_name: String) -> Result<Self, anyhow::Error> {
        let region_provider = RegionProviderChain::default_provider().or_else("us-east-2");
        let config = aws_config::defaults(BehaviorVersion::latest())
            .region(region_provider)
            .load()
            .await;
        let client = Client::new(&config);
        Ok(Self {
            client,
            bucket: bucket_name,
        })
    }

    pub async fn generate_upload_url(&self, key: &str) -> Result<String, anyhow::Error> {
        let presigning_config = PresigningConfig::expires_in(Duration::from_secs(900))?; // 15 minutes
        let presigned_request = self
            .client
            .put_object()
            .bucket(&self.bucket)
            .key(key)
            .presigned(presigning_config)
            .await?;
        Ok(presigned_request.uri().to_string())
    }

    pub async fn generate_checksummed_upload_url(
        &self,
        key: &str,
        checksum_sha256: &str,
        content_length: u64,
    ) -> Result<String, anyhow::Error> {
        let presigning_config = PresigningConfig::expires_in(Duration::from_secs(900))?;
        let content_length = i64::try_from(content_length)?;
        let presigned_request = self
            .client
            .put_object()
            .bucket(&self.bucket)
            .key(key)
            .content_length(content_length)
            .checksum_sha256(checksum_sha256)
            .presigned(presigning_config)
            .await?;
        Ok(presigned_request.uri().to_string())
    }

    pub async fn head_object(&self, key: &str) -> Result<S3ObjectInfo, anyhow::Error> {
        let response = self
            .client
            .head_object()
            .bucket(&self.bucket)
            .key(key)
            .checksum_mode(aws_sdk_s3::types::ChecksumMode::Enabled)
            .send()
            .await?;
        let size = response
            .content_length()
            .and_then(|size| u64::try_from(size).ok())
            .ok_or_else(|| anyhow::anyhow!("S3 object has an invalid content length"))?;
        Ok(S3ObjectInfo {
            size,
            checksum_sha256: response.checksum_sha256().map(ToOwned::to_owned),
        })
    }

    pub async fn generate_download_url(&self, key: &str) -> Result<String, anyhow::Error> {
        let presigning_config = PresigningConfig::expires_in(Duration::from_secs(300))?; // 5 minutes
        let presigned_request = self
            .client
            .get_object()
            .bucket(&self.bucket)
            .key(key)
            .presigned(presigning_config)
            .await?;
        Ok(presigned_request.uri().to_string())
    }

    pub async fn delete_object(&self, key: &str) -> Result<(), anyhow::Error> {
        self.client
            .delete_object()
            .bucket(&self.bucket)
            .key(key)
            .send()
            .await?;
        Ok(())
    }
}
