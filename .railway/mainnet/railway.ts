import { database, defineRailway, image, preserve, project, redis, service, volume } from "railway/iac";

// Bootstrap only. Never reapply over an activated wallet or an HA database cluster.
export default defineRailway((ctx) => {
  const environmentId = process.env.RAILWAY_MAINNET_ENVIRONMENT_ID;
  const postgresMajor = process.env.RAILWAY_MAINNET_POSTGRES_MAJOR;
  if (
    !environmentId ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(environmentId) ||
    environmentId === "37880684-30ae-4242-af12-ddba1ba620ad" ||
    ctx.projectId !== "8847a6a1-bf07-4321-a62b-9839e9954e46" ||
    ctx.environmentId !== environmentId ||
    ctx.environment !== "production"
  ) {
    throw new Error("Select the Noah production environment and set RAILWAY_MAINNET_ENVIRONMENT_ID.");
  }
  if (!postgresMajor || !/^(16|17|18)$/.test(postgresMajor)) {
    throw new Error("Set the rehearsed RAILWAY_MAINNET_POSTGRES_MAJOR (16, 17 or 18) before planning.");
  }

  const region = "iad";
  const db = database("postgres-mainnet", "postgres", {
    image: `ghcr.io/railwayapp-templates/postgres-ssl:${postgresMajor}`,
    defaultMountPath: "/var/lib/postgresql/data",
    region,
  });
  const cache = redis("redis-mainnet", { region });
  cache.deploy = {
    ...cache.deploy,
    startCommand:
      '/bin/sh -c \'exec docker-entrypoint.sh redis-server --requirepass "$REDIS_PASSWORD" --save 60 1 --dir "$RAILWAY_VOLUME_MOUNT_PATH" --appendonly yes --appendfsync everysec --maxmemory-policy noeviction\'',
  };
  const barkData = volume("bark_data_mainnet", { region, sizeMB: 1024 });
  const tools = image("debian:bookworm-slim", { autoUpdates: { type: "disabled" } });
  const barkd = service("noah-barkd-mainnet", {
    source: tools,
    replicas: { [region]: 1 },
    deploy: {
      startCommand: "/bin/sleep infinity",
      requiredMountPath: "/data",
      drainingSeconds: 30,
      restartPolicyType: "ALWAYS",
      sleepApplication: false,
    },
    volumeMounts: { "/data": barkData },
    env: {
      BARKD_DATADIR: "/data/barkd",
      BARKD_BIND_HOST: "::",
      BARKD_BIND_PORT: "3000",
      BARKD_NO_UI: "true",
    },
  });
  const api = service("api-mainnet", {
    source: tools,
    replicas: { [region]: 1 },
    deploy: {
      startCommand: "/bin/sleep infinity",
      drainingSeconds: 30,
      restartPolicyType: "ALWAYS",
      sleepApplication: false,
    },
    env: {
      SERVER_NETWORK: "bitcoin",
      LNURL_DOMAIN: "noahwallet.io",
      HOST: "0.0.0.0",
      PORT: "3000",
      POSTGRES_URL: db.env.DATABASE_URL,
      REDIS_URL: cache.env.REDIS_URL,
      BARKD_URL: "http://noah-barkd-mainnet.railway.internal:3000",
      BARKD_FORWARDED_INVOICES_ENABLED: "true",
      POSTGRES_MAX_CONNECTIONS: "20",
      REDIS_POOL_SIZE: "64",
      MAILBOX_WORKER_CONCURRENCY_LIMIT: "1000",
      MAILBOX_WORKER_CLAIM_TTL_SECS: "120",
      MAILBOX_WORKER_CLAIM_RENEW_INTERVAL_SECS: "30",
      MAILBOX_WORKER_STREAM_IDLE_RECONNECT_SECS: "600",
      ARK_SERVER_URL: preserve(),
      AUTH_JWT_SECRET: preserve(),
      AUTH_JWT_TTL_HOURS: preserve(),
      AWS_ACCESS_KEY_ID: preserve(),
      AWS_REGION: preserve(),
      AWS_SECRET_ACCESS_KEY: preserve(),
      BACKUP_CRON: preserve(),
      BARKD_AUTH_TOKEN: preserve(),
      BARKD_REQUEST_TIMEOUT_SECONDS: preserve(),
      COINGECKO_DEMO_API_KEY: preserve(),
      DEREGISTER_CRON: preserve(),
      EMAIL_DEV_MODE: preserve(),
      EXPO_ACCESS_TOKEN: preserve(),
      FIAT_RATE_BACKFILL_DAYS: preserve(),
      FIAT_RATE_REFRESH_CRON: preserve(),
      HEARTBEAT_CRON: preserve(),
      MAILBOX_AUTH_CLEANUP_CRON: preserve(),
      MAINTENANCE_INTERVAL_ROUNDS: preserve(),
      MAINTENANCE_NOTIFICATION_ADVANCE_SECS: preserve(),
      MINIMUM_APP_VERSION: preserve(),
      NOTIFICATION_SPACING_MINUTES: preserve(),
      POSTGRES_MIN_CONNECTIONS: preserve(),
      PRIVATE_PORT: preserve(),
      RUN_MAILBOX_WORKER: preserve(),
      S3_BUCKET_NAME: preserve(),
      SENTRY_URL: preserve(),
      SES_FROM_ADDRESS: preserve(),
      TELEGRAM_BOT_TOKEN: preserve(),
      TELEGRAM_SUPPORT_CHAT_ID: preserve(),
      TELEGRAM_SUPPORT_MESSAGE_THREAD_ID: preserve(),
      ZOHO_ACCOUNTS_URL: preserve(),
      ZOHO_AGENT_TICKET_BASE_URL: preserve(),
      ZOHO_API_DOMAIN: preserve(),
      ZOHO_CLIENT_ID: preserve(),
      ZOHO_CLIENT_SECRET: preserve(),
      ZOHO_DEPARTMENT_ID: preserve(),
      ZOHO_ORG_ID: preserve(),
      ZOHO_REFRESH_TOKEN: preserve(),
    },
  });

  return project("noah", { resources: [db, cache, barkData, barkd, api] });
});
