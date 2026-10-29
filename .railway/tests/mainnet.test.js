import { expect, test } from "bun:test";
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { createRailwayContext } from "railway/iac";
import bootstrap from "../mainnet/railway.ts";
import signet from "../railway.ts";

const root = new URL("../../", import.meta.url);
const workflow = (name) => Bun.YAML.parse(readFileSync(new URL(`.github/workflows/${name}`, root), "utf8"));
const releases = workflow("server-push.yml");
const railway = workflow("mainnet-railway-deploy.yml");
const deploy = railway.jobs.deploy.steps.find((step) => step.name === "Deploy the reviewed image").run;
const environmentId = "11111111-1111-1111-1111-111111111111";
const apiId = "22222222-2222-2222-2222-222222222222";
const barkdId = "33333333-3333-3333-3333-333333333333";
const digest = `sha256:${"a".repeat(64)}`;

test("production configuration rejects the wrong target and preserves active wallet applications", async () => {
  const originalId = process.env.RAILWAY_MAINNET_ENVIRONMENT_ID;
  const originalMajor = process.env.RAILWAY_MAINNET_POSTGRES_MAJOR;
  try {
    process.env.RAILWAY_MAINNET_ENVIRONMENT_ID = "f39192f6-6cea-4c33-83cd-b62259cd10cb";
    process.env.RAILWAY_MAINNET_POSTGRES_MAJOR = "18";
    const context = { projectId: "8847a6a1-bf07-4321-a62b-9839e9954e46", environmentId: process.env.RAILWAY_MAINNET_ENVIRONMENT_ID, environment: "production" };
    for (const override of [{ projectId: "other" }, { environmentId: "other" }, { environment: "signet" }]) {
      expect(() => bootstrap(createRailwayContext({ ...context, ...override }))).toThrow();
    }
    const definition = await bootstrap(createRailwayContext(context));
    const resources = definition.resources;
    const signetDefinition = await signet(createRailwayContext({ ...context, environment: "signet", environmentId: "37880684-30ae-4242-af12-ddba1ba620ad" }));
    const signetNames = new Set(signetDefinition.resources.map((resource) => resource.name));
    expect(resources.map((resource) => resource.name).filter((name) => signetNames.has(name))).toEqual([]);
    const members = resources.filter((r) => r.clusterRole);
    expect(members.filter((r) => ["root", "replica"].includes(r.clusterRole))).toHaveLength(3);
    expect(members.filter((r) => r.clusterRole === "internal")).toHaveLength(3);
    expect(resources.find((r) => r.clusterRole === "edge").deploy.multiRegionConfig).toEqual({ iad: { numReplicas: 2 } });
    expect(resources.find((r) => r.name === "postgres-mainnet").source.image).toEndWith("postgres-patroni:18");
    expect(resources.some((r) => r.type === "database")).toBe(false);
    for (const name of ["api-mainnet", "noah-barkd-mainnet"]) {
      const service = resources.find((r) => r.name === name);
      expect(service.source.image).toMatch(/^niteshbalusu\/noah-(server|barkd)@sha256:[a-f0-9]{64}$/);
      expect(service.deploy.startCommand).toBeUndefined();
      expect(service.build.builder).toBe("DOCKERFILE");
      expect(service.deploy.sleepApplication).not.toBe(true);
    }
    const wallet = resources.find((r) => r.name === "noah-barkd-mainnet");
    expect(wallet.deploy.requiredMountPath).toBe("/data");
    expect(wallet.deploy.drainingSeconds).toBe(30);
    expect(wallet.deploy.multiRegionConfig).toEqual({ iad: { numReplicas: 1 } });
    delete process.env.RAILWAY_MAINNET_POSTGRES_MAJOR;
    expect(() => bootstrap(createRailwayContext(context))).toThrow();
    process.env.RAILWAY_MAINNET_ENVIRONMENT_ID = "37880684-30ae-4242-af12-ddba1ba620ad";
    expect(() => bootstrap(createRailwayContext(context))).toThrow();
  } finally {
    for (const [key, value] of [["RAILWAY_MAINNET_ENVIRONMENT_ID", originalId], ["RAILWAY_MAINNET_POSTGRES_MAJOR", originalMajor]]) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});

function enabled(expression, ref, component = "api") {
  const github = { ref_type: ref.startsWith("refs/tags/") ? "tag" : "branch", ref_name: ref.split("/").slice(2).join("/"), ref };
  return Function("github", "inputs", "startsWith", `return (${expression});`)(
    github, { component }, (value, prefix) => value.startsWith(prefix),
  );
}

test("master deploys signet and v tags deploy mainnet after CI and publication, with inherited secrets", () => {
  for (const ref of ["refs/heads/master", "refs/heads/feature", "refs/tags/v1.0.0", "refs/tags/other"]) {
    expect(enabled(releases.jobs["mainnet-railway-deploy"].if, ref)).toBe(ref === "refs/tags/v1.0.0");
    expect(enabled(releases.jobs["railway-signet-deploy"].if, ref)).toBe(ref === "refs/heads/master");
  }
  expect(releases.jobs["mainnet-railway-deploy"].needs).toEqual(["server-checks", "create-manifest"]);
  expect(releases.jobs["railway-signet-deploy"].needs).toEqual(["server-checks", "create-manifest"]);
  expect(releases.jobs["server-checks"]).not.toHaveProperty("if");
  expect(releases.jobs["mainnet-railway-deploy"].secrets).toBe("inherit");
  expect(releases.jobs["mainnet-railway-deploy"].with.image_digest).toBe("${{ needs.create-manifest.outputs.image_digest }}");
  const signetDeploy = releases.jobs["railway-signet-deploy"].steps.find((step) => step.name === "Deploy the published signet image");
  expect(signetDeploy.env.SIGNET_IMAGE_DIGEST).toBe("${{ needs.create-manifest.outputs.image_digest }}");
});

test("manual mainnet releases keep production approval and shared concurrency; Barkd cannot deploy from tags", () => {
  for (const component of ["api", "barkd"]) {
    expect(enabled(railway.jobs.deploy.if, "refs/heads/master", component)).toBe(true);
    expect(enabled(railway.jobs.deploy.if, "refs/heads/feature", component)).toBe(false);
    expect(enabled(railway.jobs.deploy.if, "refs/tags/v1.0.0", component)).toBe(component === "api");
    expect(enabled(railway.jobs.deploy.if, "refs/tags/other", component)).toBe(false);
  }
  expect(railway.jobs.deploy.environment).toBe("production");
  expect(railway.jobs.deploy.concurrency).toEqual({ group: "railway-mainnet-deploy", "cancel-in-progress": false });
  expect(railway.on).not.toHaveProperty("push");
});

function runDeploy(overrides = {}) {
  const dir = mkdtempSync(join(tmpdir(), "noah-mainnet-test-"));
  try {
    const stub = (name, body) => writeFileSync(join(dir, name), `#!/bin/sh\n${body}\n`, { mode: 0o700 });
    stub("railway", 'printf "%s\\n" "$@" > "$PROOF/args"\ncp "$2/Dockerfile" "$PROOF/Dockerfile"\nls -A "$2" > "$PROOF/files"\nexit "${UPLOAD_EXIT:-0}"');
    stub("curl", ': > "$PROOF/health"');
    const result = spawnSync("bash", ["-e", "-o", "pipefail", "-c", deploy], {
      encoding: "utf8",
      env: { PATH: `${dir}:${process.env.PATH}`, PROOF: dir, RUNNER_TEMP: dir, RAILWAY_TOKEN: "test-only", RAILWAY_PROJECT: "8847a6a1-bf07-4321-a62b-9839e9954e46", RAILWAY_ENVIRONMENT: environmentId, API_SERVICE: apiId, BARKD_SERVICE: barkdId, COMPONENT: "api", IMAGE_DIGEST: digest, ...overrides },
    });
    const args = existsSync(join(dir, "args")) ? readFileSync(join(dir, "args"), "utf8").trim().split("\n") : [];
    const dockerfile = existsSync(join(dir, "Dockerfile")) ? readFileSync(join(dir, "Dockerfile"), "utf8") : null;
    const files = existsSync(join(dir, "files")) ? readFileSync(join(dir, "files"), "utf8") : null;
    return { status: result.status, args, dockerfile, files, health: existsSync(join(dir, "health")), contextRemoved: !args.length || !existsSync(args[1]) };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

for (const [component, service, image] of [["api", apiId, "noah-server"], ["barkd", barkdId, "noah-barkd"]]) {
  test(`${component} uploads only the pinned image to its service and cleans up`, () => {
    const result = runDeploy({ COMPONENT: component });
    expect(result.status).toBe(0);
    expect(result.args.slice(2)).toEqual(["--path-as-root", "--project", "8847a6a1-bf07-4321-a62b-9839e9954e46", "--environment", environmentId, "--service", service, "--ci", "--message", `${component} ${digest}`]);
    expect(result.dockerfile).toBe(`FROM niteshbalusu/${image}@${digest}\n`);
    expect(result.files).toBe("Dockerfile\n");
    expect(result.health).toBe(component === "api");
    expect(result.contextRemoved).toBe(true);
  });
}

test("invalid credentials, digests, service targets and signet IDs fail before upload", () => {
  for (const override of [
    { RAILWAY_TOKEN: "" }, { IMAGE_DIGEST: "latest" }, { IMAGE_DIGEST: `${digest}\nRUN anything` },
    { RAILWAY_ENVIRONMENT: "" }, { RAILWAY_ENVIRONMENT: "37880684-30ae-4242-af12-ddba1ba620ad" },
    { API_SERVICE: "2dac716c-bcd0-4c2c-ab77-6f3226548ca8" }, { BARKD_SERVICE: "0250c7b1-1916-44e0-81e7-afc947c4c5ad" },
    { API_SERVICE: barkdId }, { COMPONENT: "other" },
  ]) {
    const result = runDeploy(override);
    expect(result.status).not.toBe(0);
    expect(result.args).toEqual([]);
    expect(result.health).toBe(false);
  }
});

test("upload failure propagates, skips health and removes the temporary context", () => {
  const result = runDeploy({ UPLOAD_EXIT: "7" });
  expect(result.status).toBe(7);
  expect(result.health).toBe(false);
  expect(result.contextRemoved).toBe(true);
});
