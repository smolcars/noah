import { expect, test } from "bun:test";
import { createRequire } from "node:module";

const appRequire = createRequire(new URL("../../package.json", import.meta.url));

test("Uniwind and Expo resolve the app's React Native runtime", () => {
  const reactNative = appRequire.resolve("react-native/package.json");
  for (const name of ["uniwind", "expo-modules-core"]) {
    const dependencyRequire = createRequire(appRequire.resolve(`${name}/package.json`));
    expect(dependencyRequire.resolve("react-native/package.json")).toBe(reactNative);
  }
});

test("Nitro libraries resolve the app's shared native bindings", () => {
  const nitro = appRequire.resolve("react-native-nitro-modules/package.json");
  for (const name of ["react-native-nitro-ark", "react-native-mmkv"]) {
    const dependencyRequire = createRequire(appRequire.resolve(`${name}/package.json`));
    expect(dependencyRequire.resolve("react-native-nitro-modules/package.json")).toBe(nitro);
  }
});
