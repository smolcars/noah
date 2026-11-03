import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { bumpNativeVersion, readNativeVersions } from "./bump_native_version";
import { checkNativeRelease } from "./check_native_release";

let root: string;
const androidFile = "apps/android/app/build.gradle.kts";
const iosFile = "apps/ios/project.yml";

beforeEach(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), "noah-native-release-"));
  for (const file of [androidFile, iosFile]) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    const source = fs.readFileSync(path.resolve(import.meta.dir, "..", file), "utf8");
    fs.writeFileSync(path.join(root, file), source
      .replace(/versionName = "[^"]+"/, 'versionName = "0.2.0"')
      .replace(/versionCode = \d+/, "versionCode = 100")
      .replace(/MARKETING_VERSION: \S+/, "MARKETING_VERSION: 0.2.0")
      .replace(/CURRENT_PROJECT_VERSION: \d+/, "CURRENT_PROJECT_VERSION: 100"));
  }
});
afterEach(() => fs.rmSync(root, { recursive: true, force: true }));

function replace(file: string, from: string, to: string) {
  const target = path.join(root, file);
  fs.writeFileSync(target, fs.readFileSync(target, "utf8").replace(from, to));
}

describe("native version bump", () => {
  test.each([
    [undefined, "0.2.1"], ["minor", "0.3.0"], ["major", "1.0.0"],
    ["0.2.0", "0.2.0"], ["1.2.3", "1.2.3"],
  ])("%s updates both platforms to %s", (mode, expected) => {
    const before = readNativeVersions(root);
    expect(bumpNativeVersion(root, mode)).toEqual({
      version: expected, androidBuild: before.androidBuild + 1, iosBuild: before.iosBuild + 1,
    });
    const after = readNativeVersions(root);
    expect(after.version).toBe(expected);
    expect(after.android).toBe(before.android.replace('versionName = "0.2.0"', `versionName = "${expected}"`)
      .replace(`versionCode = ${before.androidBuild}`, `versionCode = ${before.androidBuild + 1}`));
    expect(after.ios).toBe(before.ios.replace("MARKETING_VERSION: 0.2.0", `MARKETING_VERSION: ${expected}`)
      .replace(`CURRENT_PROJECT_VERSION: ${before.iosBuild}`, `CURRENT_PROJECT_VERSION: ${before.iosBuild + 1}`));
  });

  test("increments platform build numbers independently", () => {
    replace(iosFile, "CURRENT_PROJECT_VERSION: 100", "CURRENT_PROJECT_VERSION: 150");
    expect(bumpNativeVersion(root, "0.2.0")).toEqual({ version: "0.2.0", androidBuild: 101, iosBuild: 151 });
  });

  test.each(["0.1.9", "garbage", "v0.2.0", "01.2.0", "0.2.0-beta.1"])("rejects %s without writing", (mode) => {
    const before = readNativeVersions(root);
    expect(() => bumpNativeVersion(root, mode)).toThrow();
    expect(readNativeVersions(root)).toEqual(before);
  });

  test("rejects mismatched platforms before writing either file", () => {
    replace(iosFile, "MARKETING_VERSION: 0.2.0", "MARKETING_VERSION: 0.3.0");
    const android = fs.readFileSync(path.join(root, androidFile), "utf8");
    const ios = fs.readFileSync(path.join(root, iosFile), "utf8");
    expect(() => bumpNativeVersion(root)).toThrow("Native versions must match");
    expect(fs.readFileSync(path.join(root, androidFile), "utf8")).toBe(android);
    expect(fs.readFileSync(path.join(root, iosFile), "utf8")).toBe(ios);
  });

  test("rejects ambiguous fields", () => {
    replace(androidFile, 'versionName = "0.2.0"', 'versionName = "0.2.0"\nversionName = "0.2.0"');
    expect(() => bumpNativeVersion(root)).toThrow("exactly one Android versionName");
  });

  test("rejects a missing build number before writing", () => {
    replace(iosFile, "CURRENT_PROJECT_VERSION: 100", "CURRENT_PROJECT_VERSION: nope");
    const android = fs.readFileSync(path.join(root, androidFile), "utf8");
    expect(() => bumpNativeVersion(root)).toThrow("exactly one iOS CURRENT_PROJECT_VERSION");
    expect(fs.readFileSync(path.join(root, androidFile), "utf8")).toBe(android);
  });

  test("rejects an exhausted Android build number", () => {
    replace(androidFile, "versionCode = 100", "versionCode = 2100000000");
    expect(() => bumpNativeVersion(root)).toThrow("platform limit");
    expect(readNativeVersions(root).androidBuild).toBe(2100000000);
  });
});

test("release tag must exactly match the committed version on both platforms", () => {
  expect(checkNativeRelease(root, "v0.2.0").version).toBe("0.2.0");
  for (const tag of ["v0.2.1", "v0.2.0.signet", "v0.2.0-rc.1", "0.2.0"]) {
    expect(() => checkNativeRelease(root, tag)).toThrow("must match native version");
  }
  replace(iosFile, "MARKETING_VERSION: 0.2.0", "MARKETING_VERSION: 0.3.0");
  expect(() => checkNativeRelease(root, "v0.2.0")).toThrow("Native versions must match");
});

const workflow = Bun.YAML.parse(fs.readFileSync(path.resolve(import.meta.dir,
  "../.github/workflows/native-android-artifacts.yml"), "utf8")) as {
  jobs: { build: { steps: { name?: string; run?: string }[] } };
};

function runStep(name: string, env: Record<string, string>) {
  const script = workflow.jobs.build.steps.find((step) => step.name === name)?.run;
  if (!script) throw new Error(`Missing workflow step ${name}`);
  return spawnSync("bash", ["-e", "-o", "pipefail", "-c", script], {
    cwd: root, encoding: "utf8", env: { ...process.env, ...env },
  });
}

describe("Android artifact workflow", () => {
  test.each(["mainnet", "signet"])("validates and packages %s", (variant) => {
    const output = path.join(root, "outputs");
    const result = runStep("Validate build inputs", {
      BUILD_VARIANT: variant, BUILD_REF: "v0.2.0", GITHUB_OUTPUT: output,
    });
    expect(result.status).toBe(0);
    expect(fs.readFileSync(output, "utf8")).toBe(
      `flavor=${variant[0].toUpperCase() + variant.slice(1)}\nref=refs/tags/v0.2.0\n`,
    );
    for (const [file, content] of [
      [`apps/android/app/build/outputs/apk/${variant}/release/app-${variant}-release.apk`, "apk"],
      [`apps/android/app/build/outputs/bundle/${variant}Release/app-${variant}-release.aab`, "aab"],
    ]) {
      fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
      fs.writeFileSync(path.join(root, file), content);
    }
    expect(runStep("Collect release artifacts", {
      VARIANT: variant, GITHUB_STEP_SUMMARY: path.join(root, "summary"),
    }).status).toBe(0);
    expect(fs.readFileSync(path.join(root, `release-artifacts/noah-${variant}.apk`), "utf8")).toBe("apk");
    expect(fs.readFileSync(path.join(root, `release-artifacts/noah-${variant}.aab`), "utf8")).toBe("aab");
  });

  test.each(["feature/release", "v0.2.0.signet", "v0.2.0-rc.1", "v01.2.0"])("rejects ref %s", (ref) => {
    expect(runStep("Validate build inputs", {
      BUILD_VARIANT: "mainnet", BUILD_REF: ref, GITHUB_OUTPUT: path.join(root, "outputs"),
    }).status).not.toBe(0);
  });

  test("accepts master for manual builds and rejects unknown variants", () => {
    const env = { BUILD_VARIANT: "mainnet", BUILD_REF: "master", GITHUB_OUTPUT: path.join(root, "outputs") };
    expect(runStep("Validate build inputs", env).status).toBe(0);
    expect(fs.readFileSync(env.GITHUB_OUTPUT, "utf8")).toContain("ref=refs/heads/master");
    expect(runStep("Validate build inputs", { ...env, BUILD_VARIANT: "regtest" }).status).not.toBe(0);
  });

  test("prepares the existing signing properties and removes signing files", () => {
    const envFile = path.join(root, "env");
    const result = runStep("Prepare release signing", {
      RUNNER_TEMP: root, GITHUB_ENV: envFile,
      KEYSTORE_BASE64: Buffer.from("fixture keystore").toString("base64"),
      MYAPP_RELEASE_KEY_ALIAS: "fixture-alias",
      MYAPP_RELEASE_STORE_PASSWORD: "fixture password\\with\\slashes",
      MYAPP_RELEASE_KEY_PASSWORD: "fixture-key-password",
    });
    expect(result.status).toBe(0);
    const signingDir = fs.readFileSync(envFile, "utf8").trim().slice("SIGNING_DIR=".length);
    expect(fs.readFileSync(path.join(signingDir, "release.keystore"), "utf8")).toBe("fixture keystore");
    const properties = path.join(root, "apps/android/local.properties");
    expect(fs.readFileSync(properties, "utf8")).toContain("MYAPP_RELEASE_STORE_PASSWORD=fixture\\ password\\\\with\\\\slashes");
    expect(fs.statSync(properties).mode & 0o777).toBe(0o600);
    expect(runStep("Remove signing files", { SIGNING_DIR: signingDir }).status).toBe(0);
    expect(fs.existsSync(signingDir)).toBe(false);
    expect(fs.existsSync(properties)).toBe(false);
  });
});
