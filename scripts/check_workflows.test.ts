// Each case is workflow text from the parent of a fix commit (`git show <sha>^:<path>`, trimmed),
// then the same text with that commit's fix. The rule must fire on the first and not on the second.
import { describe, expect, test } from "bun:test";
import { checkWorkflows } from "./check_workflows";

const rules = (workflows: Record<string, string>, files: string[] = [], triggers?: string) =>
  checkWorkflows({ workflows, files, triggers }).map(p => `${p.rule}@${p.file}:${p.line}`);

const serverFiles = ["server/src/main.rs", "Cargo.toml", "Cargo.lock", "scripts/ark-dev.sh", "Dockerfile"];

describe("historical mistakes", () => {
  test("0badadef7: server.yml's pull_request paths did not list server.yml", () => {
    const before = `name: Server CI
on:
  pull_request:
    paths:
      - "server/**"
      - "Cargo.toml"
      - "Cargo.lock"
`;
    const after = before.replace(`      - "Cargo.lock"\n`, `      - "Cargo.lock"\n      - ".github/workflows/server.yml"\n`);
    expect(rules({ ".github/workflows/server.yml": before }, serverFiles))
      .toEqual(["filter-misses-own-workflow@.github/workflows/server.yml:4"]);
    expect(rules({ ".github/workflows/server.yml": after }, serverFiles)).toEqual([]);
  });

  test("6cb6db2e4: server.yml's push paths did not list server.yml", () => {
    const before = `name: Server CI
on:
  pull_request:
    paths:
      - "server/**"
      - ".github/workflows/server.yml"
  push:
    branches:
      - master
    paths:
      - "server/**"
      - "Cargo.toml"
      - "Cargo.lock"
`;
    const after = `${before}      - ".github/workflows/server.yml"\n`;
    expect(rules({ ".github/workflows/server.yml": before }, serverFiles))
      .toEqual(["filter-misses-own-workflow@.github/workflows/server.yml:10"]);
    expect(rules({ ".github/workflows/server.yml": after }, serverFiles)).toEqual([]);
  });

  test("0b6dfe311: a negated pattern excluded the workflow from its own filter", () => {
    const before = `name: Telegram Notification
on:
  push:
    branches:
      - master
    paths:
      - "client/**"
      - "!**/*.md"
      - "!**/.github/workflows/*"
`;
    const after = before.replace(`      - "!**/.github/workflows/*"\n`, "").replace(`      - "!**/*.md"`, `      - ".github/workflows/**"\n      - "!**/*.md"`);
    const files = ["client/package.json"];
    expect(rules({ ".github/workflows/client-telegram.yml": before }, files))
      .toEqual(["filter-misses-own-workflow@.github/workflows/client-telegram.yml:6"]);
    expect(rules({ ".github/workflows/client-telegram.yml": after }, files)).toEqual([]);
  });

  test("7f63705da: a path filter still named the removed client/gt.config.json", () => {
    const before = `name: Native API drift
on:
  pull_request:
    paths:
      - "apps/i18n/**"
      - "client/gt.config.json"
      - ".github/workflows/native-generated.yml"
      - "!**/*.md"
`;
    const after = before.replace(`      - "client/gt.config.json"\n`, "");
    const files = ["apps/i18n/en/native.json", "README.md"];
    expect(rules({ ".github/workflows/native-generated.yml": before }, files))
      .toEqual(["filter-matches-no-file@.github/workflows/native-generated.yml:6"]);
    expect(rules({ ".github/workflows/native-generated.yml": after }, files)).toEqual([]);
  });

  test("d2ef12862: pull_request paths skipped the workflow, so its gate job never reported", () => {
    const before = `name: Server CI
on:
  pull_request:
    paths:
      - "server/**"
      - ".github/workflows/server.yml"
jobs:
  server_build_and_test:
    runs-on: ubuntu-latest
    steps:
      - run: cargo test
  server_gate:
    runs-on: ubuntu-latest
    needs: server_build_and_test
    if: always()
    steps:
      - run: test "\${{ needs.server_build_and_test.result }}" != failure
`;
    const after = before.replace(/ {4}paths:\n( {6}- .*\n)+/, "").replace("jobs:\n", `jobs:
  changes:
    runs-on: ubuntu-latest
    steps:
      - uses: dorny/paths-filter@v3
        with:
          filters: |
            server:
              - 'server/**'
              - '.github/workflows/server.yml'
`);
    expect(rules({ ".github/workflows/server.yml": before }, serverFiles))
      .toEqual(["gate-behind-workflow-paths@.github/workflows/server.yml:4"]);
    expect(rules({ ".github/workflows/server.yml": after }, serverFiles)).toEqual([]);
  });

  test("f0b1546f8: server-push.yml did not run when only the Dockerfile changed", () => {
    const before = `name: Master Server Build and Push
on:
  push:
    branches:
      - master
    paths:
      - "server/**"
      - "Cargo.toml"
      - "Cargo.lock"
      - ".github/workflows/**"
      - "!**/*.md"
`;
    const after = before.replace(`      - ".github/workflows/**"`, `      - "Dockerfile"\n      - ".github/workflows/**"`);
    const files = ["server/src/main.rs", "Cargo.toml", "Cargo.lock", "Dockerfile"];
    const table = `{
  ".github": ["server-push.yml"],
  "Cargo.lock": ["server-push.yml"],
  "Cargo.toml": ["server-push.yml"],
  "Dockerfile": ["server-push.yml"],
  "server": ["server-push.yml"]
}`;
    expect(rules({ ".github/workflows/server-push.yml": before }, files, table))
      .toEqual(["filter-misses-input@.github/workflows/server-push.yml:6"]);
    expect(rules({ ".github/workflows/server-push.yml": after }, files, table)).toEqual([]);
  });

  test("0eb5a07c7: signing steps tested github.event_name == 'workflow_call'", () => {
    const before = `name: CI
on:
  pull_request:
  workflow_call:
jobs:
  android_build:
    runs-on: ubuntu-latest
    steps:
      - name: Decode keystore
        if: github.event_name == 'workflow_call'
        run: echo "$KEYSTORE_BASE64" | base64 -d > release.keystore
`;
    const after = before.replace("== 'workflow_call'", "!= 'pull_request'");
    expect(rules({ ".github/workflows/client.yml": before }))
      .toEqual(["event-name-workflow-call@.github/workflows/client.yml:10"]);
    expect(rules({ ".github/workflows/client.yml": after })).toEqual([]);
  });

  test("4a1c032f3 (in 3a4dea331): a dispatch on another branch dropped master's queued run", () => {
    const before = `name: Native translations
on:
  push:
    branches: [master]
  workflow_dispatch:
concurrency:
  group: \${{ github.workflow }}
  cancel-in-progress: false
jobs:
  translate:
    if: github.ref == 'refs/heads/master'
    runs-on: ubuntu-latest
    steps:
      - run: bun apps/tools/translations.mjs translate
`;
    const after = before.replace("group: ${{ github.workflow }}", "group: ${{ github.workflow }}-${{ github.ref }}");
    expect(rules({ ".github/workflows/native-translations.yml": before }))
      .toEqual(["concurrency-group-without-ref@.github/workflows/native-translations.yml:7"]);
    expect(rules({ ".github/workflows/native-translations.yml": after })).toEqual([]);
  });
});

describe("trigger table", () => {
  const workflow = `on:
  pull_request:
    paths:
      - "server/**"
      - ".github/workflows/server.yml"
`;
  test("every tracked top-level path is listed", () => {
    expect(checkWorkflows({
      workflows: { ".github/workflows/server.yml": workflow },
      files: ["server/a.rs", "Dockerfile"],
      triggers: `{ ".github": [], "server": ["server.yml"] }`,
    }).map(p => p.message)).toEqual([
      `top-level path "Dockerfile" is not listed. Add "Dockerfile": [] if no workflow reads it, or the workflows whose filters must match it.`,
    ]);
  });
  test("stale entries and unknown workflows fail", () => {
    expect(rules({ ".github/workflows/server.yml": workflow }, ["server/a.rs"],
      `{\n  ".github": [],\n  "server": ["gone.yml"],\n  "fly": []\n}`))
      .toEqual(["trigger-table@.github/workflow-triggers.json:3", "trigger-table@.github/workflow-triggers.json:4"]);
  });
  test("one matching dorny filter is enough", () => {
    const dorny = `on:
  pull_request:
jobs:
  changes:
    steps:
      - uses: dorny/paths-filter@v3
        with:
          filters: |
            ios:
              - 'apps/ios/**'
              - '.github/workflows/ci.yml'
            android:
              - 'apps/android/**'
              - '.github/workflows/ci.yml'
`;
    expect(rules({ ".github/workflows/ci.yml": dorny }, ["apps/ios/a.swift", "apps/android/a.kt"],
      `{ ".github": [], "apps": ["ci.yml"] }`)).toEqual([]);
  });
});
