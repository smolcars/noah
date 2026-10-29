import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const project = "8847a6a1-bf07-4321-a62b-9839e9954e46";
const environment = "f39192f6-6cea-4c33-83cd-b62259cd10cb";
const postgres = "421299ae-2646-42f4-8a23-155e47c15a43";
const applications = [
  "4f866c7b-6b84-4275-a9fb-aee6bceb1fa6",
  "679b8d67-f0db-43ba-8b02-213d81482d11",
];
const selectors = [
  "--project",
  project,
  "--environment",
  environment,
  "--service",
  postgres,
];
const query =
  "query Preparation($id:String!){environment(id:$id){id name config(decryptVariables:false)}}";

function railway(args) {
  const result = spawnSync(process.env.RAILWAY_CLI || "railway", args, {
    encoding: "utf8",
    timeout: 180_000,
    maxBuffer: 16 * 1024 * 1024,
  });
  // API responses and CLI error output can contain credentials; never echo them.
  if (result.error || result.status !== 0)
    throw new Error(`railway ${args[0]} ${args[1]} failed; output withheld.`);
  try {
    return JSON.parse(result.stdout);
  } catch {
    throw new Error("Railway returned invalid JSON; output withheld.");
  }
}

function configuration(run) {
  const response = run(["api", query, "--raw-var", `id=${environment}`]);
  assert(!response.errors?.length, "Railway rejected the configuration query.");
  const selected = response.data?.environment;
  assert(
    selected?.id === environment && selected.name === "production",
    "Wrong Railway environment.",
  );
  assert(
    selected.config && typeof selected.config === "object",
    "Railway returned an invalid configuration.",
  );
  const config = selected.config;
  for (const id of applications) {
    const service = config?.services?.[id];
    assert(
      service?.source?.image === "debian:bookworm-slim" &&
        service?.deploy?.startCommand === "/bin/sleep infinity",
      "API and Barkd must remain on their held tools images before HA preparation.",
    );
  }
  return config;
}

export function enableHa(argv, run = railway) {
  assert(
    argv.length === 0 || (argv.length === 1 && argv[0] === "--apply"),
    "Usage: enable-ha.mjs [--apply]",
  );
  const status = run(["postgres", "ha", "status", ...selectors, "--json"]);
  assert(
    status.environment?.id === environment && status.root?.id === postgres,
    "Wrong HA target.",
  );
  const config = configuration(run);
  if (status.isCluster)
    return "Postgres is already an HA cluster; no changes made. Inspect live health separately.";

  const db = config.services[postgres];
  assert(
    db?.source?.image === "ghcr.io/railwayapp-templates/postgres-ssl:18",
    "Expected the official Postgres 18 image.",
  );
  assert(
    !db.deploy?.startCommand,
    "Remove the database start override through Railway's supported flow first.",
  );
  assert(
    Object.keys(db.volumeMounts || {}).length === 1,
    "Expected the existing database volume.",
  );
  assert(
    db.variables?.WAL_ARCHIVE_BUCKET &&
      db.variables?.WAL_ARCHIVE_KEY &&
      db.variables?.WAL_ARCHIVE_SECRET,
    "PITR must be configured before conversion.",
  );
  if (!argv.length)
    return "Preflight passed: one primary, two replicas, three coordinators, two proxy instances. Run with --apply to convert.";

  const converted = run([
    "postgres",
    "ha",
    "convert",
    ...selectors,
    "--replicas",
    "2",
    "--coordinators",
    "3",
    "--edge",
    "2",
    "--yes",
    "--json",
  ]);
  assert(
    converted.isCluster && converted.root?.id === postgres,
    "Conversion did not report the expected cluster; inspect before retrying.",
  );
  configuration(run);
  return "HA conversion accepted. API and Barkd remain held. Verify deployments, replication, routing and PITR before cutover.";
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  try {
    process.stdout.write(`${enableHa(process.argv.slice(2))}\n`);
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
