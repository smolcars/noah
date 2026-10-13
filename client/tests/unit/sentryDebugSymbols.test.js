import { expect, test } from "bun:test";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const projectPath = new URL("../../ios/Noah.xcodeproj/project.pbxproj", import.meta.url);

const loadPhases = (prebuild = false) => {
  // Run the config plugin in Node, the runtime used by Expo prebuild.
  const directory = mkdtempSync(join(tmpdir(), "noah-sentry-project-"));
  const output = join(directory, "phases.json");
  try {
    const result = Bun.spawnSync({
      cmd: [
        "node",
        "-e",
        `(async () => {
        const project = require("xcode").project(process.argv[1]).parseSync();
        const scripts = project.hash.project.objects.PBXShellScriptBuildPhase;
        const targets = Object.values(project.pbxNativeTargetSection())
          .filter(target => typeof target === "object");
        if (process.argv[2] === "true") {
          for (const target of targets) {
            target.buildPhases = target.buildPhases.filter(phase =>
              scripts[phase.value]?.name !== '\"Upload Debug Symbols to Sentry\"');
          }
          const config = require("../../plugins/withNoahIosPrebuildFix")({ name: "Noah", slug: "noah" });
          for (let iteration = 0; iteration < 2; iteration++) {
            await config.mods.ios.xcodeproj({ ...config, modResults: project, modRequest: {} });
          }
        }
        const phases = {};
        for (const name of ["Noah", "Noah-Signet", "Noah-Regtest"]) {
          const target = targets.find(target => target.name.replaceAll('"', "") === name);
          phases[name] = target.buildPhases.map(phase => scripts[phase.value])
            .filter(phase => phase?.name?.replaceAll('"', "") === "Upload Debug Symbols to Sentry");
        }
        require("node:fs").writeFileSync(process.argv[3], JSON.stringify(phases));
      })().catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });`,
        projectPath.pathname,
        String(prebuild),
        output,
      ],
      cwd: new URL(".", import.meta.url).pathname,
      stdout: "pipe",
      stderr: "pipe",
    });
    expect(result.exitCode).toBe(0);
    return JSON.parse(readFileSync(output, "utf8"));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
};

const runUploadPhase = (phase, settings = {}) => {
  const directory = mkdtempSync(join(tmpdir(), "noah-sentry-"));
  try {
    const node = join(directory, "node");
    const sdk = join(directory, "upload.sh");
    writeFileSync(node, '#!/bin/sh\nprintf "%s" "$PROBE_SDK_SCRIPT"\n');
    chmodSync(node, 0o755);
    writeFileSync(sdk, '#!/bin/sh\ntouch "$PROBE_UPLOAD_MARKER"\n');
    const marker = join(directory, "uploaded");
    const result = Bun.spawnSync({
      cmd: ["/bin/bash", "-c", JSON.parse(phase.shellScript)],
      stdout: "pipe",
      stderr: "pipe",
      env: {
        ...process.env,
        PODS_ROOT: join(directory, "Pods"),
        NODE_BINARY: node,
        PROBE_SDK_SCRIPT: sdk,
        PROBE_UPLOAD_MARKER: marker,
        CONFIGURATION: "Release",
        PLATFORM_NAME: "iphoneos",
        ACTION: "install",
        ARCHIVE_PATH: "",
        ...settings,
      },
    });
    expect(result.exitCode).toBe(0);
    return existsSync(marker);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
};

test.each(["Noah", "Noah-Signet"])("%s archive reaches native symbol upload", (name) => {
  const phases = loadPhases()[name];
  expect(phases).toHaveLength(1);
  expect(runUploadPhase(phases[0])).toBe(true);
});

test("Expo prebuild restores symbol upload once for each production target", () => {
  const generated = loadPhases(true);
  for (const name of ["Noah", "Noah-Signet"]) {
    const phases = generated[name];
    expect(phases).toHaveLength(1);
    expect(runUploadPhase(phases[0])).toBe(true);
    for (const settings of [
      { CONFIGURATION: "Debug" },
      { PLATFORM_NAME: "iphonesimulator" },
      { ACTION: "build" },
    ]) {
      expect(runUploadPhase(phases[0], settings)).toBe(false);
    }
  }
  expect(generated["Noah-Regtest"]).toHaveLength(0);
});
