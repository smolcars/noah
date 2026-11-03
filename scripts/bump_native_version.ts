import fs from "node:fs";
import path from "node:path";

const androidPath = "apps/android/app/build.gradle.kts";
const iosPath = "apps/ios/project.yml";
const semver = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const usage = "Usage: just bump-native [patch|minor|major|<x.y.z>]";

function field(content: string, pattern: RegExp, name: string): string {
  const matches = [...content.matchAll(new RegExp(pattern, "gm"))];
  if (matches.length !== 1) throw new Error(`Expected exactly one ${name}.`);
  return matches[0][1];
}

export function readNativeVersions(root: string) {
  const android = fs.readFileSync(path.join(root, androidPath), "utf8");
  const ios = fs.readFileSync(path.join(root, iosPath), "utf8");
  const androidVersion = field(android, /^\s*versionName = "([^"]+)"\s*$/, "Android versionName");
  const iosVersion = field(ios, /^\s*MARKETING_VERSION: (\S+)\s*$/, "iOS MARKETING_VERSION");
  if (!semver.test(androidVersion) || androidVersion !== iosVersion) {
    throw new Error(`Native versions must match: Android ${androidVersion}, iOS ${iosVersion}.`);
  }
  const androidBuild = Number(field(android, /^\s*versionCode = (\d+)\s*$/, "Android versionCode"));
  const iosBuild = Number(field(ios, /^\s*CURRENT_PROJECT_VERSION: (\d+)\s*$/, "iOS CURRENT_PROJECT_VERSION"));
  for (const build of [androidBuild, iosBuild]) {
    if (!Number.isSafeInteger(build) || build < 1) throw new Error("Build numbers must be positive safe integers.");
  }
  return { android, ios, version: androidVersion, androidBuild, iosBuild };
}

export function bumpNativeVersion(root: string, mode = "patch") {
  const current = readNativeVersions(root);
  let version: string;
  if (semver.test(mode)) {
    version = mode;
  } else {
    const parts = current.version.split(".").map(Number);
    const index = ["major", "minor", "patch"].indexOf(mode);
    if (index === -1) throw new Error(`Invalid version argument. ${usage}`);
    parts[index] += 1;
    parts.fill(0, index + 1);
    version = parts.join(".");
  }
  const previous = current.version.split(".").map(Number);
  const next = version.split(".").map(Number);
  if (next.some((part) => !Number.isSafeInteger(part))) throw new Error("Version components must be safe integers.");
  const firstDifference = next.findIndex((part, i) => part !== previous[i]);
  if (firstDifference !== -1 && next[firstDifference] < previous[firstDifference]) {
    throw new Error("Native release versions cannot decrease.");
  }
  const androidBuild = current.androidBuild + 1;
  const iosBuild = current.iosBuild + 1;
  if (androidBuild > 2_100_000_000 || !Number.isSafeInteger(iosBuild)) {
    throw new Error("Next build number exceeds the platform limit.");
  }
  // Validate everything before touching either platform's source of truth.
  const android = current.android
    .replace(/^(\s*versionName = ")[^"]+("\s*)$/m, `$1${version}$2`)
    .replace(/^(\s*versionCode = )\d+(\s*)$/m, `$1${androidBuild}$2`);
  const ios = current.ios
    .replace(/^(\s*MARKETING_VERSION: )\S+(\s*)$/m, `$1${version}$2`)
    .replace(/^(\s*CURRENT_PROJECT_VERSION: )\d+(\s*)$/m, `$1${iosBuild}$2`);
  fs.writeFileSync(path.join(root, androidPath), android);
  fs.writeFileSync(path.join(root, iosPath), ios);
  return { version, androidBuild, iosBuild };
}

if (import.meta.main) {
  const args = process.argv.slice(2);
  if (args.includes("--help") || args.includes("-h")) {
    console.log(usage);
  } else {
    if (args.length > 1) throw new Error(`Too many arguments. ${usage}`);
    const result = bumpNativeVersion(path.resolve(import.meta.dir, ".."), args[0]);
    console.log(`Bumped native version to ${result.version} (Android ${result.androidBuild}, iOS ${result.iosBuild}).`);
  }
}
