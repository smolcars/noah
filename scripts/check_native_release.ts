import path from "node:path";
import { readNativeVersions } from "./bump_native_version";

export function checkNativeRelease(root: string, tag: string) {
  const current = readNativeVersions(root);
  if (tag !== `v${current.version}`) {
    throw new Error(`Release tag ${tag} must match native version v${current.version}.`);
  }
  return current;
}

if (import.meta.main) {
  if (process.argv.length !== 3) throw new Error("Usage: bun scripts/check_native_release.ts <vX.Y.Z>");
  const current = checkNativeRelease(path.resolve(import.meta.dir, ".."), process.argv[2]);
  console.log(`Validated ${current.version}: Android ${current.androidBuild}, iOS ${current.iosBuild}.`);
}
