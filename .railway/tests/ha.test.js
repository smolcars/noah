import { expect, test } from "bun:test";
import { enableHa } from "../mainnet/enable-ha.mjs";

const environment = "f39192f6-6cea-4c33-83cd-b62259cd10cb";
const postgres = "421299ae-2646-42f4-8a23-155e47c15a43";
const api = "4f866c7b-6b84-4275-a9fb-aee6bceb1fa6";
const barkd = "679b8d67-f0db-43ba-8b02-213d81482d11";

function fixture(change = () => {}) {
  const status = {
    environment: { id: environment },
    root: { id: postgres },
    isCluster: false,
  };
  const held = () => ({
    source: { image: "debian:bookworm-slim" },
    deploy: { startCommand: "/bin/sleep infinity" },
  });
  const config = {
    services: {
      [api]: held(),
      [barkd]: held(),
      [postgres]: {
        source: { image: "ghcr.io/railwayapp-templates/postgres-ssl:18" },
        volumeMounts: { existing: {} },
        variables: {
          WAL_ARCHIVE_BUCKET: "present",
          WAL_ARCHIVE_KEY: "present",
          WAL_ARCHIVE_SECRET: "present",
        },
      },
    },
  };
  change({ status, config });
  const calls = [];
  return {
    calls,
    run(args) {
      calls.push(args);
      if (args[0] === "api")
        return {
          data: {
            environment: { id: environment, name: "production", config },
          },
        };
      if (args[2] === "convert") return { ...status, isCluster: true };
      return status;
    },
  };
}

test("preflight and repeated setup never convert without a standalone --apply", () => {
  for (const [args, change] of [
    [[], () => {}],
    [
      ["--apply"],
      ({ status }) => {
        status.isCluster = true;
      },
    ],
  ]) {
    const { calls, run } = fixture(change);
    enableHa(args, run);
    expect(calls.some((c) => c[2] === "convert")).toBe(false);
  }
});

test("conversion is fixed to production and verifies both applications remain held", () => {
  const { calls, run } = fixture();
  enableHa(["--apply"], run);
  expect(calls.find((c) => c[2] === "convert")).toEqual([
    "postgres",
    "ha",
    "convert",
    "--project",
    "8847a6a1-bf07-4321-a62b-9839e9954e46",
    "--environment",
    environment,
    "--service",
    postgres,
    "--replicas",
    "2",
    "--coordinators",
    "3",
    "--edge",
    "2",
    "--yes",
    "--json",
  ]);
  expect(calls.filter((c) => c[0] === "api")).toHaveLength(2);
});

test("wrong targets, active applications and missing recovery prerequisites fail before conversion", () => {
  for (const change of [
    ({ status }) => {
      status.environment.id = "signet";
    },
    ({ status }) => {
      status.root.id = "other";
    },
    ({ config }) => {
      config.services[api].deploy.startCommand = "/usr/local/bin/server";
    },
    ({ config }) => {
      config.services[barkd].source.image = "niteshbalusu/noah-barkd:0.8.0";
    },
    ({ config }) => {
      config.services[postgres].source.image = "postgres:18";
    },
    ({ config }) => {
      config.services[postgres].volumeMounts = {};
    },
    ({ config }) => {
      delete config.services[postgres].variables.WAL_ARCHIVE_BUCKET;
    },
  ]) {
    const { calls, run } = fixture(change);
    expect(() => enableHa(["--apply"], run)).toThrow();
    expect(calls.some((c) => c[2] === "convert")).toBe(false);
  }
});

test("a failed conversion propagates and is never retried", () => {
  const { calls, run } = fixture();
  expect(() =>
    enableHa(["--apply"], (args) => {
      if (args[2] === "convert") throw new Error("conversion failed");
      return run(args);
    }),
  ).toThrow("conversion failed");
  expect(calls.filter((c) => c[0] === "api")).toHaveLength(1);
});
