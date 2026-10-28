import { defineRailway, image, postgres, preserve, project, redis, service, volume } from "railway/iac";

export default defineRailway((ctx) => {
  if (ctx.projectId !== "8847a6a1-bf07-4321-a62b-9839e9954e46" || ctx.environment !== "signet") {
    throw new Error("This configuration is only for the Noah signet migration project.");
  }

  const region = "iad";
  const db = postgres("postgres", { region });
  const cache = redis("redis", { region });
  cache.deploy = {
    ...cache.deploy,
    startCommand:
      '/bin/sh -c \'exec docker-entrypoint.sh redis-server --requirepass "$REDIS_PASSWORD" --save 60 1 --dir "$RAILWAY_VOLUME_MOUNT_PATH" --appendonly yes --appendfsync everysec --maxmemory-policy noeviction\'',
  };
  const barkData = volume("bark_data", { region, sizeMB: 1024 });

  // The complete stopped Fly wallet volume has been restored and verified.
  const barkd = service("noah-barkd-signet", {
    source: image(
      "niteshbalusu/noah-barkd@sha256:e1d9dff674ff1c548d27a5c6f2c84e0a690921867ec2b49aacc6a82cb5b78e6d",
      { autoUpdates: { type: "disabled" } },
    ),
    replicas: { [region]: 1 },
    deploy: {
      requiredMountPath: "/data",
      drainingSeconds: 30,
      restartPolicyType: "ALWAYS",
    },
    volumeMounts: { "/data": barkData },
    env: {
      BARKD_DATADIR: "/data/barkd",
      BARKD_BIND_HOST: "::",
      BARKD_BIND_PORT: "3000",
      BARKD_NO_UI: "true",
    },
  });

  // sha-c25730237 (#387, UnifiedPush visible receipts); updates are deployed explicitly.
  const api = service("api", {
    build: { builder: "DOCKERFILE", dockerfilePath: "Dockerfile" },
    source: image(
      "niteshbalusu/noah-server@sha256:9e8fdbf7359cf33bb2ed7bc33b4e75301e1f284e1b03a1c551ad4da7352dcd02",
      { autoUpdates: { type: "disabled" } },
    ),
    healthcheck: "/health",
    healthcheckTimeout: 120,
    domains: [{ domain: "signet.noahwallet.io", port: 3000 }],
    replicas: { [region]: 1 },
    deploy: { restartPolicyType: "ALWAYS" },
    env: {
      SERVER_NETWORK: "signet",
      LNURL_DOMAIN: "signet.noahwallet.io",
      HOST: "0.0.0.0",
      PORT: "3000",
      POSTGRES_URL: db.env.DATABASE_URL,
      REDIS_URL: cache.env.REDIS_URL,
      BARKD_URL: "http://noah-barkd-signet.railway.internal:3000",
      BARKD_FORWARDED_INVOICES_ENABLED: "true",
      POSTGRES_MAX_CONNECTIONS: "20",
      REDIS_POOL_SIZE: "64",
      MAILBOX_WORKER_CONCURRENCY_LIMIT: "1000",
      MAILBOX_WORKER_CLAIM_TTL_SECS: "120",
      MAILBOX_WORKER_CLAIM_RENEW_INTERVAL_SECS: "30",
      MAILBOX_WORKER_STREAM_IDLE_RECONNECT_SECS: "600",
      ARK_SERVER_URL: preserve(),
      AUTH_JWT_SECRET: preserve(),
      AWS_ACCESS_KEY_ID: preserve(),
      AWS_REGION: preserve(),
      AWS_SECRET_ACCESS_KEY: preserve(),
      BACKUP_CRON: preserve(),
      BARKD_AUTH_TOKEN: preserve(),
      EXPO_ACCESS_TOKEN: preserve(),
      MAINTENANCE_INTERVAL_ROUNDS: preserve(),
      MINIMUM_APP_VERSION: preserve(),
      NOTIFICATION_SPACING_MINUTES: preserve(),
      PRIVATE_PORT: preserve(),
      S3_BUCKET_NAME: preserve(),
      SENTRY_URL: preserve(),
      TELEGRAM_BOT_TOKEN: preserve(),
      TELEGRAM_SUPPORT_CHAT_ID: preserve(),
      TELEGRAM_SUPPORT_MESSAGE_THREAD_ID: preserve(),
      ZOHO_CLIENT_ID: preserve(),
      ZOHO_CLIENT_SECRET: preserve(),
      ZOHO_DEPARTMENT_ID: preserve(),
      ZOHO_ORG_ID: preserve(),
      ZOHO_REFRESH_TOKEN: preserve(),
    },
  });

  return project("noah", {
    resources: [db, cache, barkData, barkd, api],
  });
});
