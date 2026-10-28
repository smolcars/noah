import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import config from "./railway.ts";
import { selectServices, validateImage, validatePlan } from "./release.mjs";

const environment = "37880684-30ae-4242-af12-ddba1ba620ad";
const api = `niteshbalusu/noah-server@sha256:${"a".repeat(64)}`;
const barkd = `niteshbalusu/noah-barkd@sha256:${"b".repeat(64)}`;
const next = `niteshbalusu/noah-server@sha256:${"c".repeat(64)}`;
const list = [
  { id: "2dac716c-bcd0-4c2c-ab77-6f3226548ca8", name: "api", source: { image: api }, deploymentId: "old-api", status: "SUCCESS", deploymentStopped: false },
  { id: "0250c7b1-1916-44e0-81e7-afc947c4c5ad", name: "noah-barkd-signet", source: { image: barkd }, deploymentId: "old-barkd", status: "SUCCESS", deploymentStopped: false },
];
const source = { type: "image", image: api, autoUpdates: { type: "disabled" } };
const plan = {
  environmentId: environment,
  destructive: false,
  claim: true,
  changeSet: {
    declared: ["database.postgres", "database.redis", "volume.bark_data", "service.noah-barkd-signet", "service.api"],
    changes: [{ kind: "resource.update", severity: "safe", address: "service.api", path: "resources.service.api.source", field: "source", before: source, after: { ...source, image: next } }],
  },
};

test("IaC requires both pinned inputs and the exact signet environment", () => {
  const context = { projectId: "8847a6a1-bf07-4321-a62b-9839e9954e46", environmentId: environment, environment: "signet" };
  const original = { ...process.env };
  try {
    process.env.SIGNET_API_IMAGE = api;
    process.env.SIGNET_BARKD_IMAGE = barkd;
    assert.equal(config(context).resources.find((r) => r.name === "api").source.image, api);
    for (const key of ["SIGNET_API_IMAGE", "SIGNET_BARKD_IMAGE"]) {
      const value = process.env[key];
      delete process.env[key];
      assert.throws(() => config(context));
      process.env[key] = value;
    }
    assert.throws(() => config({ ...context, environmentId: "production" }));
  } finally {
    process.env = original;
  }
});

test("release refuses mutable pins, a wrong repository, and service/source ambiguity", () => {
  for (const image of ["niteshbalusu/noah-server:latest", barkd, next + "\n", undefined]) {
    assert.throws(() => validateImage("api", image));
  }
  assert.throws(() => validateImage("postgres", api));
  assert.equal(selectServices(list).barkd.source.image, barkd);
  for (const invalid of [list.slice(0, 1), [...list, list[0]], list.map((s) => ({ ...s, source: { image: s.source.image, repo: "smolcars/noah" } }))]) {
    assert.throws(() => selectServices(invalid));
  }
});

test("saved-plan gate accepts one pin and rejects unrelated or stale changes", () => {
  assert.equal(validatePlan(plan, "api", api, next), true);
  const invalid = [
    { ...plan, environmentId: "production" },
    { ...plan, destructive: true },
    { ...plan, changeSet: { ...plan.changeSet, partial: "other" } },
    { ...plan, changeSet: { ...plan.changeSet, declared: [] } },
    { ...plan, changeSet: { ...plan.changeSet, changes: [] } },
    { ...plan, changeSet: { ...plan.changeSet, changes: [...plan.changeSet.changes, { kind: "variable.delete" }] } },
  ];
  for (const patch of [
    { address: "service.noah-barkd-signet" }, { field: "deploy" }, { path: "resources.service.api.deploy" },
    { kind: "resource.delete" }, { before: { ...source, image: next } },
    { after: { ...source, image: next, autoUpdates: { type: "patch" } } },
  ]) invalid.push({ ...plan, changeSet: { ...plan.changeSet, changes: [{ ...plan.changeSet.changes[0], ...patch }] } });
  for (const value of invalid) assert.throws(() => validatePlan(value, "api", api, next));
  assert.equal(validatePlan({ ...plan, changeSet: { ...plan.changeSet, changes: [] } }, "api", api, api), false);
});

test("CLI execution preserves Barkd, applies the saved plan, and fails closed", () => {
  const directory = mkdtempSync(join(tmpdir(), "noah-release-test-"));
  const log = join(directory, "calls.jsonl");
  writeFileSync(join(directory, "railway"), `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.TEST_LOG, JSON.stringify({ args, api: process.env.SIGNET_API_IMAGE, barkd: process.env.SIGNET_BARKD_IMAGE }) + '\\n');
const list = ${JSON.stringify(list)};
const plan = ${JSON.stringify(plan)};
const barkdRelease = process.env.SCENARIO === 'barkd';
const index = barkdRelease ? 1 : 0;
const image = barkdRelease ? 'niteshbalusu/noah-barkd@sha256:' + 'c'.repeat(64) : ${JSON.stringify(next)};
if (barkdRelease) {
  const change = plan.changeSet.changes[0];
  change.address = 'service.noah-barkd-signet';
  change.path = 'resources.service.noah-barkd-signet.source';
  change.before.image = list[1].source.image;
  change.after.image = image;
}
if (args[0] === 'service') {
  if (fs.existsSync(process.env.TEST_LOG + '.applied')) {
    list[index].source.image = image;
    if (process.env.SCENARIO === 'delayed' && !fs.existsSync(process.env.TEST_LOG + '.seen-old')) {
      fs.writeFileSync(process.env.TEST_LOG + '.seen-old', 'yes');
    } else list[index].deploymentId = 'new-deployment';
    if (process.env.SCENARIO === 'crashed') list[index].status = 'CRASHED';
  }
  console.log(JSON.stringify(list));
} else if (args[1] === 'plan') {
  if (process.env.SCENARIO === 'drift') plan.changeSet.changes.push({kind:'variable.delete'});
  if (process.env.SCENARIO === 'noop') plan.changeSet.changes = [];
  fs.writeFileSync(args[args.indexOf('--out') + 1], JSON.stringify(plan));
  console.log('{}');
} else if (args[1] === 'apply') {
  if (process.env.SCENARIO === 'stale') { console.error('private-config-value'); process.exit(1); }
  fs.writeFileSync(process.env.TEST_LOG + '.applied', 'yes');
  console.log(JSON.stringify({status:process.env.SCENARIO === 'failed' ? 'failed' : 'applied',changes:[{status:process.env.SCENARIO === 'partial' ? 'failed' : 'applied'}]}));
} else process.exit(2);
`, { mode: 0o700 });
  try {
    for (const scenario of ["success", "barkd", "delayed", "noop", "drift", "stale", "failed", "partial", "crashed", "no-token"]) {
      rmSync(log, { force: true });
      rmSync(log + ".applied", { force: true });
      rmSync(log + ".seen-old", { force: true });
      const target = scenario === "barkd" ? "barkd" : "api";
      const image = scenario === "noop" ? api : scenario === "barkd" ? next.replace("noah-server", "noah-barkd") : next;
      const result = spawnSync(process.execPath, [resolve(".railway/release.mjs"), target, image], {
        encoding: "utf8",
        timeout: 15_000,
        env: { ...process.env, PATH: `${directory}:${process.env.PATH}`, RAILWAY_TOKEN: scenario === "no-token" ? "" : "test-only", SCENARIO: scenario, TEST_LOG: log },
      });
      assert.equal(result.status, ["success", "barkd", "delayed", "noop"].includes(scenario) ? 0 : 1, `${scenario}: ${result.stderr}`);
      assert.ok(!result.stderr.includes("private-config-value"));
      if (scenario === "no-token") continue;
      const calls = readFileSync(log, "utf8").trim().split("\n").map(JSON.parse);
      assert.equal(calls[1].api, target === "api" ? image : api);
      assert.equal(calls[1].barkd, target === "barkd" ? image : barkd);
      const apply = calls.find((c) => c.args[1] === "apply");
      assert.equal(Boolean(apply), !["drift", "noop"].includes(scenario));
      if (scenario === "delayed") assert.equal(calls.filter((c) => c.args[0] === "service").length, 3);
      if (apply) {
        assert.equal(apply.args[apply.args.indexOf("--plan") + 1], calls[1].args[calls[1].args.indexOf("--out") + 1]);
        assert.ok(!apply.args.includes("--confirm-destructive"));
      }
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
