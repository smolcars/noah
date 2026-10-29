import { expect, test } from "bun:test";
import { createRailwayContext } from "railway/iac";
import production from "../mainnet/railway.ts";

test("Metabase has a private independent database and capped resources, with the restored app healthchecked", async () => {
  const oldId = process.env.RAILWAY_MAINNET_ENVIRONMENT_ID;
  const oldMajor = process.env.RAILWAY_MAINNET_POSTGRES_MAJOR;
  try {
    process.env.RAILWAY_MAINNET_ENVIRONMENT_ID = "f39192f6-6cea-4c33-83cd-b62259cd10cb";
    process.env.RAILWAY_MAINNET_POSTGRES_MAJOR = "18";
    const definition = await production(createRailwayContext({ projectId: "8847a6a1-bf07-4321-a62b-9839e9954e46", environmentId: process.env.RAILWAY_MAINNET_ENVIRONMENT_ID, environment: "production" }));
    const app = definition.resources.find((r) => r.name === "metabase");
    const db = definition.resources.find((r) => r.name === "postgres-metabase");
    const volume = definition.resources.find((r) => r.name === "postgres-metabase-volume");
    expect(app.source.image).toMatch(/^metabase\/metabase@sha256:[a-f0-9]{64}$/);
    expect(app.deploy.limitOverride.containers).toEqual({ cpu: 1, memoryBytes: 1024 ** 3 });
    expect(app.deploy.startCommand).toBeUndefined();
    expect(app.deploy.healthcheckPath).toBe("/api/health");
    expect(app.deploy.healthcheckTimeout).toBe(600);
    expect(app.variables.MB_DB_PASS.resource).toBe(db.address);
    expect(app.variables.MB_ENCRYPTION_SECRET_KEY.type).toBe("preserve");
    expect(app.variables.PORT.value).toBe("3000");
    expect(app.variables.JAVA_OPTS.value).toBe("-Xms128m -Xmx512m");
    expect(db.deploy.limitOverride.containers).toEqual({ cpu: 1, memoryBytes: 2 * 1024 ** 3 });
    expect(db.networking?.tcpProxies).toBeUndefined();
    expect(db.volumeAttachments["postgres-metabase-volume"].volume).toBe(volume.address);
    expect(volume.config.sizeMB).toBe(5120);
    expect(Object.keys(app.volumeAttachments ?? {})).toHaveLength(0);
  } finally {
    if (oldId === undefined) delete process.env.RAILWAY_MAINNET_ENVIRONMENT_ID;
    else process.env.RAILWAY_MAINNET_ENVIRONMENT_ID = oldId;
    if (oldMajor === undefined) delete process.env.RAILWAY_MAINNET_POSTGRES_MAJOR;
    else process.env.RAILWAY_MAINNET_POSTGRES_MAJOR = oldMajor;
  }
});
