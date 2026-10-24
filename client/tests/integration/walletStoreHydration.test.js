import { beforeEach, expect, mock, test } from "bun:test";
import * as React from "react";

const storage = new Map();
mock.module("../../src/lib/mmkv", () => ({
  mmkv: {
    getString: (name) => storage.get(name),
    set: (name, value) => storage.set(name, value),
    remove: (name) => storage.delete(name),
  },
}));
mock.module("../../src/lib/log", () => ({ default: () => ({ e() {}, w() {} }) }));

// Old installations persisted native readiness alongside the user's settings.
const savedState = {
  isInitialized: true,
  isWalletLoaded: true,
  isBiometricsEnabled: true,
  staticVtxoPubkey: "wallet-a",
  isDebugModeEnabled: false,
  isWalletSuspended: false,
  hasSeenBatteryOptimizationPrompt: true,
};
storage.set("wallet-storage", JSON.stringify({ state: savedState, version: 0 }));
const { useWalletStore } = await import("../../src/store/walletStore");
const coldStartState = useWalletStore.getState();

const effects = [];
let nativeLoaded = false;
const loadWallet = mock((_args, options) => {
  nativeLoaded = true;
  useWalletStore.getState().setWalletLoaded();
  options.onSettled();
});
mock.module("react", () => ({
  ...React,
  useEffect: (effect) => effects.push(effect),
  useRef: (current) => ({ current }),
}));
mock.module("react-native", () => ({ View: "view" }));
mock.module("gt-react-native", () => ({ T: "translation" }));
mock.module("react-native-nitro-ark", () => ({ isWalletLoaded: async () => nativeLoaded }));
mock.module("../../src/store/walletStore", () => ({
  useWalletStore: Object.assign(() => useWalletStore.getState(), {
    getState: useWalletStore.getState,
  }),
}));
mock.module("../../src/hooks/useWallet", () => ({
  useLoadWallet: () => ({ mutate: loadWallet, isPending: false }),
  useCloseWallet: () => ({ mutate() {} }),
}));
mock.module("../../src/hooks/useBackgroundJobCoordination", () => ({
  useBackgroundJobCoordination: () => ({ safelyExecuteWhenReady: async (callback) => callback() }),
}));
mock.module("../../src/components/ui/text", () => ({ Text: "text" }));
mock.module("../../src/components/ui/NoahActivityIndicator", () => ({
  NoahActivityIndicator: "spinner",
}));
mock.module("../../src/components/BiometricGate", () => ({ default: "biometric-gate" }));
const { default: WalletLoader } = await import("../../src/components/WalletLoader");

beforeEach(() => {
  effects.length = 0;
  nativeLoaded = false;
  loadWallet.mockClear();
  useWalletStore.getState().reset();
  storage.set("wallet-storage", JSON.stringify({ state: savedState, version: 0 }));
  useWalletStore.persist.rehydrate();
});

test("cold start waits for native wallet readiness while preserving biometric protection", () => {
  expect(coldStartState.isWalletLoaded).toBe(false);
  expect(coldStartState.isInitialized).toBe(true);
  expect(coldStartState.isBiometricsEnabled).toBe(true);
  expect(coldStartState.staticVtxoPubkey).toBe("wallet-a");
  expect(coldStartState.hasSeenBatteryOptimizationPrompt).toBe(true);
});

test("native loading updates readiness without persisting it across app restarts", () => {
  useWalletStore.getState().setWalletLoaded();
  expect(useWalletStore.getState().isWalletLoaded).toBe(true);
  const persisted = JSON.parse(storage.get("wallet-storage")).state;
  expect(persisted).not.toHaveProperty("isWalletLoaded");
  expect(persisted).toEqual({
    isInitialized: true,
    isBiometricsEnabled: true,
    staticVtxoPubkey: "wallet-a",
    isDebugModeEnabled: false,
    isWalletSuspended: false,
    hasSeenBatteryOptimizationPrompt: true,
  });
});

test("rehydration cannot overwrite readiness already established in this process", () => {
  useWalletStore.getState().setWalletLoaded();
  storage.set(
    "wallet-storage",
    JSON.stringify({ state: { ...savedState, isWalletLoaded: false }, version: 0 }),
  );
  useWalletStore.persist.rehydrate();
  expect(useWalletStore.getState().isWalletLoaded).toBe(true);
});

test.each([false, true])(
  "mounts the biometric gate only after readiness is confirmed (native loaded: %s)",
  async (alreadyLoaded) => {
    nativeLoaded = alreadyLoaded;
    expect(WalletLoader({ children: "wallet" }).type).toBe("view");
    effects[0]();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(loadWallet).toHaveBeenCalledTimes(1);
    expect(WalletLoader({ children: "wallet" }).type).toBe("biometric-gate");
  },
);
