import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, mock, test } from "bun:test";

const DOCUMENT_PATH = process.env.NOAH_TEST_DOCUMENT_PATH ?? "/documents";
const PLATFORM = process.env.NOAH_TEST_PLATFORM ?? "ios";
const ARK_PATH = `${DOCUMENT_PATH}/wallet`;
let mnemonic;
let files = new Set();
let loaded;
let createError;
let loadError;
let keychainError;
let filesystemError;
let creationGate;
let mnemonicCount;
let deriveError;
let suspended;
let documentEntries;
const calls = [];
const unlinked = [];
const clearAll = mock(() => {});
const createMMKV = mock(() => ({ clearAll }));
mock.module("react-native", () => ({ Platform: { OS: PLATFORM } }));
mock.module("react-native-mmkv", () => ({ createMMKV }));

const source = readFileSync(new URL("../../src/lib/walletApi.ts", import.meta.url), "utf8");
const nativeNames = source
  .match(/import\s*\{([^}]+)\}\s*from "react-native-nitro-ark"/)[1]
  .split(",")
  .map((name) => name.trim().split(/\s+as\s+/)[0])
  .filter((name) => name && !name.startsWith("type "));
const native = Object.fromEntries(
  nativeNames.map((name) => [
    name,
    async () => {
      throw new Error(`Unexpected native call: ${name}`);
    },
  ]),
);
Object.assign(native, {
  signMessage: async () => {
    throw new Error("Unexpected signing during onboarding");
  },
  createMnemonic: async () => {
    calls.push("generate");
    return `test-seed-${++mnemonicCount}`;
  },
  isWalletLoaded: async () => loaded,
  closeWallet: async () => {
    calls.push("close");
    loaded = false;
  },
  createWallet: async (path) => {
    calls.push("create");
    if (creationGate) await creationGate;
    if (files.has(path)) throw new Error("cannot overwrite already existing config");
    files.add(path);
    if (createError) throw createError;
  },
  loadWallet: async (_path, options) => {
    calls.push("load");
    if (loadError) throw loadError;
    expect(options.mnemonic).toBe(mnemonic);
    loaded = true;
  },
  deriveStoreNextKeypair: async () => {
    calls.push("derive");
    if (deriveError) throw deriveError;
    return { public_key: "test-public-key" };
  },
  peekKeyPair: async () => ({ public_key: "test-public-key" }),
});
mock.module("react-native-nitro-ark", () => native);
mock.module("react-native-keychain", () => ({
  getGenericPassword: async () => {
    if (keychainError) throw keychainError;
    return mnemonic ? { password: mnemonic } : false;
  },
  setGenericPassword: async (_username, value) => {
    calls.push("store-seed");
    mnemonic = value;
    return true;
  },
  resetGenericPassword: async () => {
    calls.push("clear-seed");
    mnemonic = null;
    return true;
  },
}));
mock.module("react-native-fs-turbo", () => ({
  default: {
    DocumentDirectoryPath: DOCUMENT_PATH,
    exists: (path) => {
      if (filesystemError) throw filesystemError;
      return files.has(path) || path === DOCUMENT_PATH;
    },
    mkdir() {},
    readdir: (path) => (path === DOCUMENT_PATH ? documentEntries : ["config.toml"]),
    unlink: (path) => {
      calls.push("delete-data");
      unlinked.push(path);
      files.delete(path);
    },
  },
}));
mock.module("../../src/constants", () => ({
  ARK_DATA_PATH: ARK_PATH,
  DOCUMENT_DIRECTORY_PATH: DOCUMENT_PATH,
  CACHES_DIRECTORY_PATH: "/cache",
  AUTH_TOKEN_KEYCHAIN_SERVICE: "auth",
  KEYCHAIN_USERNAME: "test",
  shouldUseUnifiedPush: () => false,
}));
mock.module("../../src/config", () => ({ APP_VARIANT: "regtest" }));
mock.module("../../src/lib/log", () => ({
  default: () => ({ error() {}, e() {}, w() {}, i() {}, d() {} }),
}));
mock.module("noah-tools", () => ({
  clearNativeEsploraEndpoint: async () => {},
  clearNativeMnemonic: async () => {},
  storeNativeMnemonic: async () => {},
  storeNativeEsploraEndpoint: async () => {},
}));
mock.module("../../src/store/walletStore", () => ({
  useWalletStore: {
    getState: () => ({
      isWalletSuspended: suspended,
      setWalletLoaded() {},
    }),
  },
}));
mock.module("../../src/store/esploraStore", () => ({
  useEsploraStore: { getState: () => ({ reset() {} }) },
}));
mock.module("../../src/lib/esplora", () => ({
  getEffectiveEsploraEndpoint: () => null,
  validateEsploraEndpoint: () => {},
}));
mock.module("../../src/lib/walletConfig", () => ({
  getDefaultEsploraEndpoint: () => null,
  getEffectiveWalletConfig: () => ({}),
  getWalletRefreshExpiryThreshold: () => 24,
}));
const { createWallet, deleteWallet } = await import("../../src/lib/walletApi");
await import("../../src/lib/mmkv");

beforeEach(() => {
  mnemonic = null;
  files = new Set();
  loaded = false;
  createError = null;
  loadError = null;
  keychainError = null;
  filesystemError = null;
  creationGate = null;
  mnemonicCount = 0;
  deriveError = null;
  suspended = false;
  documentEntries = ["wallet"];
  calls.length = 0;
  unlinked.length = 0;
  clearAll.mockClear();
});

test("deletion keeps the open MMKV directory and clears its contents through MMKV", async () => {
  documentEntries = ["wallet", "mmkv", "backup.json"];
  files.add("/cache");
  const expectedMMKVPath =
    PLATFORM === "ios" ? `${DOCUMENT_PATH.replace(/\/files$/, "")}/mmkv` : `${DOCUMENT_PATH}/mmkv`;

  expect(createMMKV).toHaveBeenCalledWith({
    id: "noah-wallet-storage",
    path: expectedMMKVPath,
  });
  expect((await deleteWallet()).isOk()).toBe(true);
  expect(unlinked).not.toContain(expectedMMKVPath);
  expect(clearAll).toHaveBeenCalledTimes(1);
  expect(unlinked).toEqual([
    `${DOCUMENT_PATH}/wallet`,
    ...(expectedMMKVPath === `${DOCUMENT_PATH}/mmkv` ? [] : [`${DOCUMENT_PATH}/mmkv`]),
    `${DOCUMENT_PATH}/backup.json`,
    "/cache/config.toml",
  ]);
});

describe("onboarding wallet creation", () => {
  test("accepting again keeps the original seed and creates only once", async () => {
    expect((await createWallet()).isOk()).toBe(true);
    const original = mnemonic;
    expect((await createWallet()).isOk()).toBe(true);
    expect(mnemonic).toBe(original);
    expect(calls.filter((call) => call === "generate")).toHaveLength(1);
    expect(calls.filter((call) => call === "create")).toHaveLength(1);
    expect(calls).not.toContain("close");
    expect(calls).not.toContain("delete-data");
  });

  test("resumes an existing unloaded wallet without generating a seed", async () => {
    mnemonic = "existing-seed";
    files.add(ARK_PATH);
    expect((await createWallet()).isOk()).toBe(true);
    expect(mnemonic).toBe("existing-seed");
    expect(calls).toEqual(["load"]);
  });

  test("a failed resume preserves the seed and wallet data", async () => {
    mnemonic = "existing-seed";
    files.add(ARK_PATH);
    loadError = new Error("server unavailable");
    expect((await createWallet()).isErr()).toBe(true);
    expect(mnemonic).toBe("existing-seed");
    expect(files.has(ARK_PATH)).toBe(true);
    expect(calls).toEqual(["load"]);
  });

  test("a suspended wallet is preserved and cannot report a successful resume", async () => {
    mnemonic = "existing-seed";
    files.add(ARK_PATH);
    suspended = true;
    expect((await createWallet()).isErr()).toBe(true);
    expect(mnemonic).toBe("existing-seed");
    expect(files.has(ARK_PATH)).toBe(true);
    expect(calls).toEqual([]);
  });

  test.each(["seed", "data"])("preserves inconsistent storage with only %s", async (existing) => {
    if (existing === "seed") mnemonic = "existing-seed";
    else files.add(ARK_PATH);
    expect((await createWallet()).isErr()).toBe(true);
    expect(calls).toEqual([]);
    expect(mnemonic).toBe(existing === "seed" ? "existing-seed" : null);
    expect(files.has(ARK_PATH)).toBe(existing === "data");
  });

  test.each(["keychain", "filesystem"])(
    "a %s read failure does not create or delete",
    async (storage) => {
      if (storage === "keychain") keychainError = new Error("keychain locked");
      else filesystemError = new Error("storage unavailable");
      expect((await createWallet()).isErr()).toBe(true);
      expect(calls).toEqual([]);
    },
  );

  test("cleans up a failed fresh attempt so the next acceptance can retry", async () => {
    createError = new Error("server unavailable");
    expect((await createWallet()).isErr()).toBe(true);
    expect(files.has(ARK_PATH)).toBe(false);
    expect(mnemonic).toBeNull();
    expect(calls).toContain("delete-data");
    createError = null;
    expect((await createWallet()).isOk()).toBe(true);
    expect(files.has(ARK_PATH)).toBe(true);
  });

  test("cleans up a fresh failure after the new seed has been stored", async () => {
    deriveError = new Error("key derivation failed");
    expect((await createWallet()).isErr()).toBe(true);
    expect(files.has(ARK_PATH)).toBe(false);
    expect(mnemonic).toBeNull();
    deriveError = null;
    expect((await createWallet()).isOk()).toBe(true);
  });

  test("overlapping acceptances share one creation attempt", async () => {
    let release;
    creationGate = new Promise((resolve) => {
      release = resolve;
    });
    const first = createWallet();
    const second = createWallet();
    expect(second).toBe(first);
    release();
    expect((await first).isOk()).toBe(true);
    expect((await second).isOk()).toBe(true);
    expect(calls.filter((call) => call === "create")).toHaveLength(1);
    expect(calls.filter((call) => call === "generate")).toHaveLength(1);
  });
});

const showAlert = mock(() => {});
mock.module("gt-react-native", () => ({ useGT: () => (text) => text }));
mock.module("@tanstack/react-query", () => ({ useMutation: (options) => options, useQuery() {} }));
mock.module("../../src/contexts/AlertProvider", () => ({ useAlert: () => ({ showAlert }) }));
mock.module("../../src/store/serverStore", () => ({ useServerStore() {} }));
mock.module("../../src/store/transactionStore", () => ({ useTransactionStore() {} }));
mock.module("../../src/store/backupStore", () => ({ useBackupStore() {} }));
mock.module("../../src/lib/autoBoarding", () => ({ getAutoBoardThreshold() {} }));
mock.module("../../src/lib/backupService", () => ({ restoreWallet() {} }));
mock.module("../../src/lib/api", () => ({ deregister() {} }));
mock.module("../../src/queryClient", () => ({ queryClient: {} }));
mock.module("../../src/lib/recurringPayments", () => ({ clearAllRecurringPayments() {} }));
mock.module("../../src/lib/recurringBackgroundTask", () => ({ syncRecurringBackgroundTask() {} }));
const { useCreateWallet } = await import("../../src/hooks/useWallet");

test("the creation hook reports a resume failure without deleting the existing wallet", async () => {
  mnemonic = "existing-seed";
  files.add(ARK_PATH);
  loadError = new Error("server unavailable");
  const mutation = useCreateWallet();
  let error;
  try {
    await mutation.mutationFn();
  } catch (failure) {
    error = failure;
  }
  expect(error).toBe(loadError);
  await mutation.onError(error);
  expect(mnemonic).toBe("existing-seed");
  expect(files.has(ARK_PATH)).toBe(true);
  expect(calls).toEqual(["load"]);
  expect(showAlert).toHaveBeenCalledWith({
    title: "Creation Failed",
    description: "server unavailable",
  });
});
