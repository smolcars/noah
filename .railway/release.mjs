import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout } from "node:timers/promises";
import { pathToFileURL } from "node:url";

const project = "8847a6a1-bf07-4321-a62b-9839e9954e46";
const environment = "37880684-30ae-4242-af12-ddba1ba620ad";
const services = {
  api: { name: "api", id: "2dac716c-bcd0-4c2c-ab77-6f3226548ca8", repository: "noah-server" },
  barkd: { name: "noah-barkd-signet", id: "0250c7b1-1916-44e0-81e7-afc947c4c5ad", repository: "noah-barkd" },
};

export function validateImage(target, image) {
  assert.ok(Object.hasOwn(services, target), "Choose api or barkd.");
  assert.match(image, new RegExp(`^niteshbalusu/${services[target].repository}@sha256:[a-f0-9]{64}$`), "A full image digest is required.");
}

export function selectServices(list) {
  return Object.fromEntries(Object.entries(services).map(([target, expected]) => {
    const matches = list.filter((item) => item.id === expected.id && item.name === expected.name);
    assert.equal(matches.length, 1, `Missing or ambiguous ${target} service.`);
    validateImage(target, matches[0].source?.image);
    assert.ok(!matches[0].source.repo, "GitHub deployment sources must be disconnected separately.");
    return [target, matches[0]];
  }));
}

export function validatePlan(plan, target, before, image) {
  assert.equal(plan.environmentId, environment, "Wrong environment in saved plan.");
  assert.equal(plan.destructive, false, "Destructive plans cannot be released from CI.");
  assert.ok(!plan.changeSet.partial, "Named partials cannot be released by this workflow.");
  assert.deepEqual([...plan.changeSet.declared].sort(), [
    "database.postgres", "database.redis", "service.api", "service.noah-barkd-signet", "volume.bark_data",
  ], "Release must keep the existing five resources.");
  const changes = plan.changeSet.changes;
  assert.ok(Array.isArray(changes), "Missing plan changes.");
  assert.equal(changes.length, before === image ? 0 : 1, "Release must change only the selected image; apply infrastructure changes separately.");
  if (!changes.length) return false;
  const change = changes[0];
  const address = `service.${services[target].name}`;
  assert.equal(change.kind, "resource.update");
  assert.equal(change.severity, "safe");
  assert.equal(change.address, address);
  assert.equal(change.path, `resources.${address}.source`);
  assert.equal(change.field, "source");
  assert.equal(change.before.type, "image");
  assert.equal(change.before.image, before, "Image changed since service discovery; re-plan.");
  assert.deepEqual(change.after, { ...change.before, image }, "Release cannot change other source settings.");
  return true;
}

export async function release(target, image) {
  validateImage(target, image);
  assert.ok(process.env.RAILWAY_TOKEN, "RAILWAY_TOKEN is required.");
  const directory = mkdtempSync(join(tmpdir(), "noah-signet-release-"));
  const planPath = join(directory, "plan.json");
  const railway = (...args) => {
    // CLI errors may contain configuration values; keep captured output out of CI logs.
    const output = execFileSync("railway", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      // The SDK uses `_` to check the invoking CLI version, even when launched by Node.
      env: { ...process.env, _: "railway" },
    });
    return JSON.parse(output);
  };
  const listServices = () => selectServices(railway("service", "list", "--project", project, "--environment", environment, "--json"));
  try {
    const current = listServices();
    process.env.SIGNET_API_IMAGE = target === "api" ? image : current.api.source.image;
    process.env.SIGNET_BARKD_IMAGE = target === "barkd" ? image : current.barkd.source.image;
    railway("config", "plan", "--file", ".railway/railway.ts", "--environment", environment, "--out", planPath, "--json");
    const changed = validatePlan(JSON.parse(readFileSync(planPath, "utf8")), target, current[target].source.image, image);
    if (changed) {
      console.log(`Applying image-only ${target} release: ${image}`);
      const applied = railway("config", "apply", "--environment", environment, "--plan", planPath, "--yes", "--json");
      assert.equal(applied.status, "applied", "Railway did not apply the release.");
      assert.ok(applied.changes?.every((change) => change.status !== "failed"), "Railway reported a failed change.");
    }
    const deadline = Date.now() + 10 * 60_000;
    while (Date.now() < deadline) {
      const latest = listServices();
      assert.equal(latest.api.source.image, process.env.SIGNET_API_IMAGE, "API image changed during release.");
      assert.equal(latest.barkd.source.image, process.env.SIGNET_BARKD_IMAGE, "Barkd image changed during release.");
      const deployed = latest[target];
      if (!changed || deployed.deploymentId !== current[target].deploymentId) {
        assert.ok(!["FAILED", "CRASHED", "REMOVED", "SKIPPED"].includes(deployed.status), "Railway deployment failed.");
        if (deployed.deploymentId && deployed.status === "SUCCESS" && !deployed.deploymentStopped) {
          console.log(`${target} deployment ${deployed.deploymentId} is running.`);
          return;
        }
      }
      await setTimeout(5_000);
    }
    throw new Error("Timed out waiting for the Railway deployment.");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  release(process.argv[2], process.argv[3]).catch(() => {
    console.error("Signet release failed. Check the plan and deployment in the authenticated Railway CLI; no automatic rollback was attempted.");
    process.exitCode = 1;
  });
}
