import {
  bucket,
  defineRailway,
  image,
  preserve,
  project,
  service,
  volume,
} from "railway/iac";

// Live production snapshot: use Railway's native HA commands for cluster lifecycle.
// Use service(): CLI 5.71.0 database() supplies standalone defaults and drops HA settings.
export default defineRailway((ctx) => {
  if (
    process.env.RAILWAY_MAINNET_ENVIRONMENT_ID !==
      "f39192f6-6cea-4c33-83cd-b62259cd10cb" ||
    ctx.projectId !== "8847a6a1-bf07-4321-a62b-9839e9954e46" ||
    ctx.environmentId !== "f39192f6-6cea-4c33-83cd-b62259cd10cb" ||
    ctx.environment !== "production"
  )
    throw new Error(
      "Select the existing Noah production environment and set RAILWAY_MAINNET_ENVIRONMENT_ID.",
    );
  if (process.env.RAILWAY_MAINNET_POSTGRES_MAJOR !== "18") {
    throw new Error(
      "This snapshot preserves the existing Postgres 18 HA cluster.",
    );
  }
  const imageUpdates = (startHour: number, endHour: number) => ({
    autoUpdates: {
      type: "minor" as const,
      tagMode: "sha" as const,
      schedule: Array.from({ length: 7 }, (_, day) => ({
        day,
        startHour,
        endHour,
      })),
    },
  });
  const postgres3Volume = volume("postgres-3-volume", {
    region: "iad",
    sizeMB: 50000,
  });
  const postgresMainnetVolume = volume("postgres-mainnet-volume", {
    region: "iad",
    sizeMB: 50000,
  });
  const postgres2Volume = volume("postgres-2-volume", {
    region: "iad",
    sizeMB: 50000,
  });
  const etcd3Volume = volume("etcd-3-volume", {
    region: "iad",
    sizeMB: 50000,
  });
  const bark_data_mainnet = volume("bark_data_mainnet", {
    region: "iad",
    sizeMB: 1024,
  });
  const etcd2Volume = volume("etcd-2-volume", {
    region: "iad",
    sizeMB: 50000,
  });
  const redisMainnetVolume = volume("redis-mainnet-volume", {
    region: "iad",
    sizeMB: 50000,
  });
  const etcd1Volume = volume("etcd-1-volume", {
    region: "iad",
    sizeMB: 50000,
  });
  const PostgresPITR = bucket("Postgres-PITR", { region: "iad" });

  const Postgres3 = service("Postgres-3", {
    source: image(
      "ghcr.io/railwayapp-templates/postgres-ha/postgres-patroni:18",
    ),
    build: { builder: "RAILPACK", buildEnvironment: "V3" },
    replicas: { iad: 1 },
    deploy: {
      restartPolicyType: "ALWAYS",
      requiredMountPath: "/var/lib/postgresql/data",
    },
    networking: { privateNetworkEndpoint: "postgres-3" },
    parentServiceId: "421299ae-2646-42f4-8a23-155e47c15a43",
    clusterRole: "replica",
    volumeMounts: {
      "1d3977a1-c172-4f3e-a829-aea96d625983": {
        mountPath: "/var/lib/postgresql/data",
      },
    },
    env: {
      DATABASE_URL: preserve(),
      PATRONI_API_PORT: preserve(),
      PATRONI_ENABLED: preserve(),
      PATRONI_ETCD3_HOSTS: preserve(),
      PATRONI_LOOP_WAIT: preserve(),
      PATRONI_NAME: preserve(),
      PATRONI_REPLICATION_PASSWORD: preserve(),
      PATRONI_REPLICATION_USERNAME: preserve(),
      PATRONI_RESTAPI_PASSWORD: preserve(),
      PATRONI_SCOPE: preserve(),
      PATRONI_SUPERUSER_PASSWORD: preserve(),
      PATRONI_SUPERUSER_USERNAME: preserve(),
      PATRONI_TTL: preserve(),
      PATRONI_WAIT_FOR_LEADER: preserve(),
      PGDATA: preserve(),
      PGDATABASE: preserve(),
      PGHOST: preserve(),
      PGPASSWORD: preserve(),
      PGPORT: preserve(),
      PGUSER: preserve(),
      POSTGRES_DB: preserve(),
      POSTGRES_PASSWORD: preserve(),
      POSTGRES_USER: preserve(),
      RAILWAY_DEPLOYMENT_DRAINING_SECONDS: preserve(),
      SSL_CERT_DAYS: preserve(),
      WAL_ARCHIVE_BUCKET: preserve(),
      WAL_ARCHIVE_ENDPOINT: preserve(),
      WAL_ARCHIVE_KEY: preserve(),
      WAL_ARCHIVE_PATH: preserve(),
      WAL_ARCHIVE_REGION: preserve(),
      WAL_ARCHIVE_SECRET: preserve(),
      WATCHDOG_CURL_CONNECT_TIMEOUT: preserve(),
      WATCHDOG_CURL_TIMEOUT: preserve(),
    },
  });
  const postgresMainnet = service("postgres-mainnet", {
    source: image(
      "ghcr.io/railwayapp-templates/postgres-ha/postgres-patroni:18",
    ),
    build: { builder: "RAILPACK", buildEnvironment: "V3" },
    replicas: { iad: 1 },
    deploy: {
      restartPolicyType: "ALWAYS",
      requiredMountPath: "/var/lib/postgresql/data",
    },
    networking: { privateNetworkEndpoint: "postgres-1" },
    clusterRole: "root",
    volumeMounts: {
      "53b7e132-3ab5-4f6e-9a10-79682ac397ac": {
        mountPath: "/var/lib/postgresql/data",
      },
    },
    env: {
      DATABASE_URL: preserve(),
      PATRONI_ADOPT_EXISTING_DATA: preserve(),
      PATRONI_API_PORT: preserve(),
      PATRONI_ENABLED: preserve(),
      PATRONI_ETCD3_HOSTS: preserve(),
      PATRONI_LOOP_WAIT: preserve(),
      PATRONI_NAME: preserve(),
      PATRONI_REPLICATION_PASSWORD: preserve(),
      PATRONI_REPLICATION_USERNAME: preserve(),
      PATRONI_RESTAPI_PASSWORD: preserve(),
      PATRONI_SCOPE: preserve(),
      PATRONI_SUPERUSER_PASSWORD: preserve(),
      PATRONI_SUPERUSER_USERNAME: preserve(),
      PATRONI_TTL: preserve(),
      PGDATA: preserve(),
      PGDATABASE: preserve(),
      PGHOST: preserve(),
      PGPASSWORD: preserve(),
      PGPORT: preserve(),
      PGUSER: preserve(),
      POSTGRES_DB: preserve(),
      POSTGRES_PASSWORD: preserve(),
      POSTGRES_USER: preserve(),
      RAILWAY_DEPLOYMENT_DRAINING_SECONDS: preserve(),
      SSL_CERT_DAYS: preserve(),
      WAL_ARCHIVE_BUCKET: preserve(),
      WAL_ARCHIVE_ENDPOINT: preserve(),
      WAL_ARCHIVE_KEY: preserve(),
      WAL_ARCHIVE_PATH: preserve(),
      WAL_ARCHIVE_REGION: preserve(),
      WAL_ARCHIVE_SECRET: preserve(),
      WATCHDOG_CURL_CONNECT_TIMEOUT: preserve(),
      WATCHDOG_CURL_TIMEOUT: preserve(),
    },
  });
  const Postgres2 = service("Postgres-2", {
    source: image(
      "ghcr.io/railwayapp-templates/postgres-ha/postgres-patroni:18",
    ),
    build: { builder: "RAILPACK", buildEnvironment: "V3" },
    replicas: { iad: 1 },
    deploy: {
      restartPolicyType: "ALWAYS",
      requiredMountPath: "/var/lib/postgresql/data",
    },
    networking: { privateNetworkEndpoint: "postgres-2" },
    parentServiceId: "421299ae-2646-42f4-8a23-155e47c15a43",
    clusterRole: "replica",
    volumeMounts: {
      "607581c7-a0ec-4fdd-9dbb-da08c60db92e": {
        mountPath: "/var/lib/postgresql/data",
      },
    },
    env: {
      DATABASE_URL: preserve(),
      PATRONI_API_PORT: preserve(),
      PATRONI_ENABLED: preserve(),
      PATRONI_ETCD3_HOSTS: preserve(),
      PATRONI_LOOP_WAIT: preserve(),
      PATRONI_NAME: preserve(),
      PATRONI_REPLICATION_PASSWORD: preserve(),
      PATRONI_REPLICATION_USERNAME: preserve(),
      PATRONI_RESTAPI_PASSWORD: preserve(),
      PATRONI_SCOPE: preserve(),
      PATRONI_SUPERUSER_PASSWORD: preserve(),
      PATRONI_SUPERUSER_USERNAME: preserve(),
      PATRONI_TTL: preserve(),
      PATRONI_WAIT_FOR_LEADER: preserve(),
      PGDATA: preserve(),
      PGDATABASE: preserve(),
      PGHOST: preserve(),
      PGPASSWORD: preserve(),
      PGPORT: preserve(),
      PGUSER: preserve(),
      POSTGRES_DB: preserve(),
      POSTGRES_PASSWORD: preserve(),
      POSTGRES_USER: preserve(),
      RAILWAY_DEPLOYMENT_DRAINING_SECONDS: preserve(),
      SSL_CERT_DAYS: preserve(),
      WAL_ARCHIVE_BUCKET: preserve(),
      WAL_ARCHIVE_ENDPOINT: preserve(),
      WAL_ARCHIVE_KEY: preserve(),
      WAL_ARCHIVE_PATH: preserve(),
      WAL_ARCHIVE_REGION: preserve(),
      WAL_ARCHIVE_SECRET: preserve(),
      WATCHDOG_CURL_CONNECT_TIMEOUT: preserve(),
      WATCHDOG_CURL_TIMEOUT: preserve(),
    },
  });
  const redisMainnet = service("redis-mainnet", {
    source: image("redis:8.2"),
    build: { builder: "RAILPACK", buildEnvironment: "V3" },
    replicas: { iad: 1 },
    deploy: {
      startCommand:
        '/bin/sh -c \'exec docker-entrypoint.sh redis-server --requirepass "$REDIS_PASSWORD" --save 60 1 --dir "$RAILWAY_VOLUME_MOUNT_PATH" --appendonly yes --appendfsync everysec --maxmemory-policy noeviction\'',
    },
    volumeMounts: {
      "bdabf26e-39d2-435b-b858-907ae97dfe70": { mountPath: "/data" },
    },
    env: {
      REDISHOST: preserve(),
      REDISPASSWORD: preserve(),
      REDISPORT: preserve(),
      REDISUSER: preserve(),
      REDIS_PASSWORD: preserve(),
      REDIS_URL: preserve(),
    },
  });

  const etcd2 = service("etcd-2", {
    parentServiceId: "421299ae-2646-42f4-8a23-155e47c15a43",
    clusterRole: "internal",
    source: image(
      "ghcr.io/railwayapp-templates/postgres-ha/etcd:3",
      imageUpdates(2, 4),
    ),
    build: { buildEnvironment: "V3", builder: "RAILPACK" },
    replicas: { iad: 1 },
    deploy: { requiredMountPath: "/var/lib/etcd", restartPolicyType: "ALWAYS" },
    volumeMounts: { "/var/lib/etcd": etcd2Volume },
    env: {
      ETCD_ADVERTISE_CLIENT_URLS: preserve(),
      ETCD_DATA_DIR: preserve(),
      ETCD_INITIAL_ADVERTISE_PEER_URLS: preserve(),
      ETCD_INITIAL_CLUSTER: preserve(),
      ETCD_INITIAL_CLUSTER_TOKEN: preserve(),
      ETCD_LISTEN_CLIENT_URLS: preserve(),
      ETCD_LISTEN_PEER_URLS: preserve(),
      ETCD_NAME: preserve(),
      ETCD_ROOT_PASSWORD: preserve(),
    },
  });
  const PostgresHA = service("Postgres HA", {
    parentServiceId: "421299ae-2646-42f4-8a23-155e47c15a43",
    clusterRole: "edge",
    source: image(
      "ghcr.io/railwayapp-templates/postgres-ha/haproxy:3",
      imageUpdates(0, 24),
    ),
    build: { buildEnvironment: "V3", builder: "RAILPACK" },
    replicas: { iad: 2 },
    deploy: { numReplicas: 2, restartPolicyType: "ALWAYS" },
    networking: { privateNetworkEndpoint: "postgres-ha" },
    env: {
      DATABASE_URL: preserve(),
      HAPROXY_CHECK_DOWNINTER: preserve(),
      HAPROXY_CHECK_FASTINTER: preserve(),
      HAPROXY_CHECK_INTERVAL: preserve(),
      HAPROXY_HEALTH_PORT: preserve(),
      HAPROXY_MAX_CONN: preserve(),
      HAPROXY_TIMEOUT_CHECK: preserve(),
      HAPROXY_TIMEOUT_CLIENT: preserve(),
      HAPROXY_TIMEOUT_CONNECT: preserve(),
      HAPROXY_TIMEOUT_SERVER: preserve(),
      PGDATABASE: preserve(),
      PGHOST: preserve(),
      PGPASSWORD: preserve(),
      PGPORT: preserve(),
      PGUSER: preserve(),
      POSTGRES_DB: preserve(),
      POSTGRES_NODES: preserve(),
      POSTGRES_PASSWORD: preserve(),
      POSTGRES_USER: preserve(),
    },
  });
  const apiMainnet = service("api-mainnet", {
    source: image("niteshbalusu/noah-server@sha256:4b60519ea9ea1868773c1824b441dcdf3b0b6c7e259c29d135131e4cd54bff8d", {
      autoUpdates: { type: "disabled" },
    }),
    build: { buildEnvironment: "V3", builder: "DOCKERFILE", dockerfilePath: "Dockerfile" },
    replicas: { iad: 1 },
    healthcheck: "/health",
    healthcheckTimeout: 120,
    domains: [{ domain: "noahwallet.io", port: 3000 }],
    deploy: { drainingSeconds: 30, overlapSeconds: 0, restartPolicyType: "ALWAYS" },
    env: {
      ARK_SERVER_URL: preserve(),
      AUTH_JWT_SECRET: preserve(),
      AWS_ACCESS_KEY_ID: preserve(),
      AWS_REGION: preserve(),
      AWS_SECRET_ACCESS_KEY: preserve(),
      BACKUP_CRON: preserve(),
      BARKD_AUTH_TOKEN: preserve(),
      BARKD_FORWARDED_INVOICES_ENABLED: preserve(),
      BARKD_URL: preserve(),
      EXPO_ACCESS_TOKEN: preserve(),
      APNS_KEY_ID: preserve(),
      APNS_KEY_P8: preserve(),
      APNS_TEAM_ID: preserve(),
      APNS_TOPIC: preserve(),
      FCM_SERVICE_ACCOUNT_JSON: preserve(),
      HOST: preserve(),
      LNURL_DOMAIN: preserve(),
      MAILBOX_WORKER_CLAIM_RENEW_INTERVAL_SECS: preserve(),
      MAILBOX_WORKER_CLAIM_TTL_SECS: preserve(),
      MAILBOX_WORKER_CONCURRENCY_LIMIT: preserve(),
      MAILBOX_WORKER_STREAM_IDLE_RECONNECT_SECS: preserve(),
      MAINTENANCE_INTERVAL_ROUNDS: preserve(),
      MINIMUM_APP_VERSION: preserve(),
      PORT: preserve(),
      POSTGRES_MAX_CONNECTIONS: preserve(),
      POSTGRES_URL: preserve(),
      REDIS_POOL_SIZE: preserve(),
      REDIS_URL: preserve(),
      RUST_LOG: preserve(),
      S3_BUCKET_NAME: preserve(),
      SENTRY_URL: preserve(),
      SERVER_NETWORK: preserve(),
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
  const etcd3 = service("etcd-3", {
    parentServiceId: "421299ae-2646-42f4-8a23-155e47c15a43",
    clusterRole: "internal",
    source: image(
      "ghcr.io/railwayapp-templates/postgres-ha/etcd:3",
      imageUpdates(4, 6),
    ),
    build: { buildEnvironment: "V3", builder: "RAILPACK" },
    replicas: { iad: 1 },
    deploy: { requiredMountPath: "/var/lib/etcd", restartPolicyType: "ALWAYS" },
    volumeMounts: { "/var/lib/etcd": etcd3Volume },
    env: {
      ETCD_ADVERTISE_CLIENT_URLS: preserve(),
      ETCD_DATA_DIR: preserve(),
      ETCD_INITIAL_ADVERTISE_PEER_URLS: preserve(),
      ETCD_INITIAL_CLUSTER: preserve(),
      ETCD_INITIAL_CLUSTER_TOKEN: preserve(),
      ETCD_LISTEN_CLIENT_URLS: preserve(),
      ETCD_LISTEN_PEER_URLS: preserve(),
      ETCD_NAME: preserve(),
      ETCD_ROOT_PASSWORD: preserve(),
    },
  });
  const etcd1 = service("etcd-1", {
    parentServiceId: "421299ae-2646-42f4-8a23-155e47c15a43",
    clusterRole: "internal",
    source: image(
      "ghcr.io/railwayapp-templates/postgres-ha/etcd:3",
      imageUpdates(0, 2),
    ),
    build: { buildEnvironment: "V3", builder: "RAILPACK" },
    replicas: { iad: 1 },
    deploy: { requiredMountPath: "/var/lib/etcd", restartPolicyType: "ALWAYS" },
    volumeMounts: { "/var/lib/etcd": etcd1Volume },
    env: {
      ETCD_ADVERTISE_CLIENT_URLS: preserve(),
      ETCD_DATA_DIR: preserve(),
      ETCD_INITIAL_ADVERTISE_PEER_URLS: preserve(),
      ETCD_INITIAL_CLUSTER: preserve(),
      ETCD_INITIAL_CLUSTER_TOKEN: preserve(),
      ETCD_LISTEN_CLIENT_URLS: preserve(),
      ETCD_LISTEN_PEER_URLS: preserve(),
      ETCD_NAME: preserve(),
      ETCD_ROOT_PASSWORD: preserve(),
    },
  });
  const noahBarkdMainnet = service("noah-barkd-mainnet", {
    source: image("niteshbalusu/noah-barkd@sha256:e1d9dff674ff1c548d27a5c6f2c84e0a690921867ec2b49aacc6a82cb5b78e6d", {
      autoUpdates: { type: "disabled" },
    }),
    build: { buildEnvironment: "V3", builder: "DOCKERFILE", dockerfilePath: "Dockerfile" },
    replicas: { iad: 1 },
    deploy: {
      drainingSeconds: 30,
      overlapSeconds: 0,
      requiredMountPath: "/data",
      restartPolicyType: "ALWAYS",
    },
    volumeMounts: { "/data": bark_data_mainnet },
    env: {
      BARKD_BIND_HOST: preserve(),
      BARKD_BIND_PORT: preserve(),
      BARKD_DATADIR: preserve(),
      BARKD_NO_UI: preserve(),
    },
  });

  const metabaseData = volume("postgres-metabase-volume", {
    region: "iad",
    sizeMB: 5120,
  });
  // Use service(): the native database compiler drops limits and custom mounts.
  const metabaseDb = service("postgres-metabase", {
    source: image("ghcr.io/railwayapp-templates/postgres-ssl:18"),
    replicas: { iad: 1 },
    deploy: {
      requiredMountPath: "/var/lib/postgresql/data",
      restartPolicyType: "ALWAYS",
      drainingSeconds: 60,
      limitOverride: { containers: { cpu: 1, memoryBytes: 2 * 1024 ** 3 } },
    },
    volumeMounts: { "/var/lib/postgresql/data": metabaseData },
    env: {
      POSTGRES_DB: "metabase",
      POSTGRES_USER: "metabase",
      POSTGRES_PASSWORD: preserve(),
      PGDATA: "/var/lib/postgresql/data/pgdata",
      PGHOST: "${{RAILWAY_PRIVATE_DOMAIN}}",
      PGPORT: "5432",
      PGUSER: "${{POSTGRES_USER}}",
      PGDATABASE: "${{POSTGRES_DB}}",
      PGPASSWORD: "${{POSTGRES_PASSWORD}}",
      DATABASE_URL: "postgresql://${{PGUSER}}:${{POSTGRES_PASSWORD}}@${{RAILWAY_PRIVATE_DOMAIN}}:5432/${{PGDATABASE}}",
      SSL_CERT_DAYS: "820",
    },
  });
  const metabase = service("metabase", {
    source: image("metabase/metabase@sha256:9ee98fcaf46d7a1c21cd1a35a7162361dbeb5dc3be6b93ef75135fd6d97ef64b", {
      autoUpdates: { type: "disabled" },
    }),
    domains: [{ domain: "metabase.noahwallet.io", port: 3000 }],
    healthcheck: "/api/health",
    healthcheckTimeout: 600,
    replicas: { iad: 1 },
    deploy: {
      restartPolicyType: "ALWAYS",
      drainingSeconds: 30,
      overlapSeconds: 0,
      limitOverride: { containers: { cpu: 1, memoryBytes: 1536 * 1024 ** 2 } },
    },
    env: {
      MB_DB_TYPE: "postgres",
      MB_DB_HOST: metabaseDb.env.PGHOST,
      MB_DB_PORT: metabaseDb.env.PGPORT,
      MB_DB_DBNAME: metabaseDb.env.PGDATABASE,
      MB_DB_USER: metabaseDb.env.PGUSER,
      MB_DB_PASS: metabaseDb.env.PGPASSWORD,
      MB_ENCRYPTION_SECRET_KEY: preserve(),
      MB_JETTY_HOST: "0.0.0.0",
      MB_JETTY_PORT: "3000",
      PORT: "3000",
      MB_LOAD_SAMPLE_CONTENT: "false",
      MB_SITE_URL: "https://metabase.noahwallet.io",
      JAVA_OPTS: "-Xms128m -Xmx512m",
      MB_APPLICATION_DB_MAX_CONNECTION_POOL_SIZE: "5",
      MB_JDBC_DATA_WAREHOUSE_MAX_CONNECTION_POOL_SIZE: "5",
      MB_DISABLE_SCHEDULER: "false",
    },
  });

  return project("noah", {
    resources: [
      metabase,
      metabaseDb,
      metabaseData,
      Postgres3,
      etcd2,
      PostgresHA,
      postgresMainnet,
      apiMainnet,
      etcd3,
      etcd1,
      noahBarkdMainnet,
      redisMainnet,
      Postgres2,
      postgres3Volume,
      postgresMainnetVolume,
      postgres2Volume,
      etcd3Volume,
      bark_data_mainnet,
      etcd2Volume,
      redisMainnetVolume,
      etcd1Volume,
      PostgresPITR,
    ],
  });
});
