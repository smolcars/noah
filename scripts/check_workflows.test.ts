// Each case is workflow text from the parent of a fix commit (`git show <sha>^:<path>`, trimmed),
// then the same text with that commit's fix. The rule must fire on the first and not on the second.
import { describe, expect, test } from "bun:test";
import { checkWorkflows, filterMatches } from "./check_workflows";

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

describe("GitHub path patterns", () => {
  const matches = (patterns: string[], file: string) =>
    filterMatches({ label: "on.push.paths", patterns, ignore: false, line: 1 }, file);
  // The examples in "Patterns to match file paths" of GitHub's workflow syntax reference.
  const documented: [string[], string[], string[]][] = [
    [["*"], ["README.md", "server.rb"], ["docs/README.md"]],
    [["*.jsx?"], ["page.js", "page.jsx"], ["page.jsxx"]],
    [["**"], ["all/the/files.md"], []],
    [["*.js"], ["app.js", "index.js"], ["js/index.js"]],
    [["**.js"], ["index.js", "js/index.js", "src/js/app.js"], ["index.ts"]],
    [["docs/*"], ["docs/README.md", "docs/file.txt"], ["docs/mona/octocat.txt"]],
    [["docs/**"], ["docs/README.md", "docs/mona/octocat.txt"], ["README.md"]],
    [["docs/**/*.md"], ["docs/README.md", "docs/mona/hello-world.md", "docs/a/markdown/file.md"], ["docs/a.txt"]],
    [["**/docs/**"], ["docs/hello.md", "dir/docs/my-file.txt", "space/docs/plan/space.doc"], ["mydocs/a.md"]],
    [["**/README.md"], ["README.md", "js/README.md"], ["js/README.mdx"]],
    [["**/*src/**"], ["a/src/app.js", "my-src/code/js/app.js"], ["src.js"]],
    [["**/*-post.md"], ["my-post.md", "path/their-post.md"], ["post.md"]],
    [["**/migrate-*.sql"], ["migrate-10909.sql", "db/migrate-v1.0.sql", "db/sept/migrate-v1.sql"], ["db/migrate.sql"]],
    [["*.md", "!README.md"], ["hello.md"], ["README.md", "docs/hello.md"]],
    [["*.md", "!README.md", "README*"], ["hello.md", "README.md", "README.doc"], []],
    [["[CB]at", "v[12].[0-9]+"], ["Cat", "Bat", "v1.10"], ["Rat", "v3.1"]],
  ];
  test.each(documented)("%p", (patterns, yes, no) => {
    for (const f of yes) expect([f, matches(patterns, f)]).toEqual([f, true]);
    for (const f of no) expect([f, matches(patterns, f)]).toEqual([f, false]);
  });

  test("**.yml crosses / in on: filters, in both directions", () => {
    const own = ".github/workflows/ci.yml";
    const excluded = `on:\n  push:\n    paths:\n      - ".github/workflows/**"\n      - "!**.yml"\n`;
    expect(rules({ [own]: excluded })).toEqual([`filter-misses-own-workflow@${own}:3`]);
    const correct = `on:\n  push:\n    paths:\n      - "**.ts"\n      - "**.yml"\n`;
    expect(rules({ [own]: correct }, ["scripts/a.ts"])).toEqual([]);
  });
});

describe("event-name-workflow-call reads conditions, not lines", () => {
  test("a comment does not fire it; a folded if: does", () => {
    const text = `on:
  workflow_call:
jobs:
  build:
    # Never test github.event_name == 'workflow_call': a called workflow sees its caller's event.
    runs-on: ubuntu-latest
    steps:
      - if: >-
          github.event_name ==
          'workflow_call'
        run: echo sign
`;
    expect(rules({ ".github/workflows/ci.yml": text })).toEqual(["event-name-workflow-call@.github/workflows/ci.yml:10"]);
  });
});

describe("concurrency-group-without-ref", () => {
  const workflow = (group: string, condition = "github.ref == 'refs/heads/master'") => `on:
  push:
  workflow_dispatch:
concurrency:
  group: ${group}
jobs:
  translate:
    if: ${condition}
    runs-on: ubuntu-latest
`;
  const check = (text: string) => rules({ ".github/workflows/t.yml": text });
  test("head_ref alone is empty outside pull requests, so it needs a fallback", () => {
    expect(check(workflow("${{ github.workflow }}-${{ github.head_ref }}"))).toEqual(["concurrency-group-without-ref@.github/workflows/t.yml:5"]);
    expect(check(workflow("${{ github.workflow }}-${{ github.head_ref || github.ref }}"))).toEqual([]);
  });
  test("ref_name is per ref", () => {
    expect(check(workflow("${{ github.workflow }}-${{ github.ref_name }}"))).toEqual([]);
  });
  test("a job that tests ref_name or ref_type has the same bug", () => {
    expect(check(workflow("${{ github.workflow }}", "github.ref_name == 'master'"))).toEqual(["concurrency-group-without-ref@.github/workflows/t.yml:5"]);
    expect(check(workflow("${{ github.workflow }}", "github.ref_type == 'tag'"))).toEqual(["concurrency-group-without-ref@.github/workflows/t.yml:5"]);
  });
});

describe("dorny/paths-filter forms", () => {
  const step = (filters: string) => `on:
  pull_request:
jobs:
  changes:
    steps:
      - uses: dorny/paths-filter@v3
        with:
          filters: ${filters}
`;
  test("a filters file is skipped, not read as patterns", () => {
    expect(rules({ ".github/workflows/ci.yml": step(".github/filters.yml") }, ["server/a.rs"])).toEqual([]);
  });
  test("change-type objects give their patterns", () => {
    const text = step(`|
            server:
              - added|modified: 'server/**'
              - '.github/workflows/ci.yml'
            gone:
              - deleted: 'client/**'
              - '.github/workflows/ci.yml'`);
    expect(rules({ ".github/workflows/ci.yml": text }, ["server/a.rs"])).toEqual(["filter-matches-no-file@.github/workflows/ci.yml:13"]);
  });
});

test("gate-behind-workflow-paths covers pull_request_target", () => {
  const text = `on:
  pull_request_target:
    paths:
      - "server/**"
      - ".github/workflows/ci.yml"
jobs:
  test:
    runs-on: ubuntu-latest
  gate:
    needs: test
    if: always()
    runs-on: ubuntu-latest
`;
  expect(rules({ ".github/workflows/ci.yml": text }, ["server/a.rs"])).toEqual(["gate-behind-workflow-paths@.github/workflows/ci.yml:3"]);
});
