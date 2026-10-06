// Runs the real recurring payment executor and payment helpers with only the
// native wallet, storage, notifications and network mocked. Covers the case
// where the user pauses or cancels while the payment is being prepared, after
// the preflight checks but before the transfer is submitted.
import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, mock, test } from "bun:test";
import { ok } from "neverthrow";

globalThis.__DEV__ = false;

const deferred = () => {
  let resolve;
  const promise = new Promise((r) => {
    resolve = r;
  });
  return { promise, resolve };
};

// Lets pending promise callbacks run.
const flush = async () => {
  for (let i = 0; i < 20; i += 1) await Promise.resolve();
  await new Promise((r) => setTimeout(r, 0));
};

const ARK_ADDRESS = "tark1recipient";

// Bun shares module mocks between test files. Mocked app modules get a stub
// for every export, so a file that runs later can still replace the exports it
// needs.
const srcRoot = new URL("../../src/", import.meta.url);
const exportNames = (path) =>
  [
    ...readFileSync(new URL(path, srcRoot), "utf8").matchAll(
      /export\s+(?:async\s+)?(?:const|function|let|class)\s+(\w+)/g,
    ),
  ].map((match) => match[1]);
const mockAppModule = (path, implementation) => {
  const stubs = Object.fromEntries(
    exportNames(path).map((name) => [
      name,
      () => {
        throw new Error(`Unexpected call: ${path} ${name}`);
      },
    ]),
  );
  mock.module(`~/${path.replace(/\.tsx?$/, "")}`, () => ({ ...stubs, ...implementation }));
};

// --- Native wallet (react-native-nitro-ark) --------------------------------

const nativeCalls = { sendArkoor: 0, payOffer: 0, payLightningAddress: 0 };
let historyGate = null;
let validationGate = null;

// Every native function the app uses gets a stub that fails loudly, so an
// unexpected native call shows up as a test failure. Listing all of them (not
// just the payment ones) also lets other test files that mock this module
// later replace the functions they need.
const nitroNames = new Set();
for (const file of new Bun.Glob("src/**/*.{ts,tsx}").scanSync({
  cwd: new URL("../..", import.meta.url).pathname,
  absolute: true,
})) {
  const source = readFileSync(file, "utf8");
  if (!source.includes("react-native-nitro-ark")) continue;
  for (const match of source.matchAll(/import\s*{([^}]*)}\s*from\s*"react-native-nitro-ark"/g)) {
    for (const part of match[1].split(",")) {
      const name = part
        .trim()
        .split(/\s+as\s+/)[0]
        .trim();
      if (name && !name.startsWith("type ")) nitroNames.add(name);
    }
  }
  for (const match of source.matchAll(/NitroArk\.(\w+)/g)) nitroNames.add(match[1]);
}
const nitroMock = Object.fromEntries(
  [...nitroNames].map((name) => [
    name,
    async () => {
      throw new Error(`Unexpected native call: ${name}`);
    },
  ]),
);
Object.assign(nitroMock, {
  history: async () => {
    if (historyGate) await historyGate.promise;
    return [];
  },
  validateArkoorAddress: async () => {
    if (validationGate) await validationGate.promise;
  },
  sendArkoorPayment: async (destination, amountSat) => {
    nativeCalls.sendArkoor += 1;
    return { amount_sat: amountSat, destination_address: destination, vtxos: [] };
  },
  payLightningOffer: async () => {
    nativeCalls.payOffer += 1;
    return { state: "paid" };
  },
  payLightningAddress: async () => {
    nativeCalls.payLightningAddress += 1;
    return { state: "paid" };
  },
  estimateArkoorPaymentFee: async () => ({ fee_sat: 0 }),
  estimateLightningSendFee: async () => ({ fee_sat: 0 }),
});
mock.module("react-native-nitro-ark", () => nitroMock);

// --- Other native and network boundaries -----------------------------------

mock.module("noah-tools", () => ({
  getAppVariant: () => "signet",
  isGooglePlayServicesAvailable: () => true,
  nativeLog: () => {},
  nativeGet: async () => {
    throw new Error("Unexpected network call");
  },
  nativePost: async () => {
    throw new Error("Unexpected network call");
  },
}));
mock.module("react-native", () => ({ AppState: {}, Platform: { OS: "ios" } }));
// Other test files mock noah-tools with only a few exports. Mock the app
// modules that read it so this file does not depend on which test ran first.
mockAppModule("lib/log.ts", {
  default: () => ({ d: () => {}, i: () => {}, w: () => {}, e: () => {} }),
});
mockAppModule("constants.ts", { isRecurringPaymentsSupported: () => true });
mockAppModule("lib/sendUtils.ts", {
  normalizeLightningAddress: (address) => address.trim().toLowerCase(),
  parseDestination: () => ({ destinationType: "lnurl" }),
});
mock.module("react-native-fs-turbo", () => ({
  default: { CachesDirectoryPath: "/tmp", DocumentDirectoryPath: "/tmp" },
}));
mock.module("expo-device", () => ({ isDevice: true }));
const memoryStorage = new Map();
mockAppModule("lib/mmkv.ts", {
  mmkv: {
    set: (key, value) => memoryStorage.set(key, value),
    getString: (key) => memoryStorage.get(key),
    remove: (key) => memoryStorage.delete(key),
  },
});
mock.module("expo-notifications", () => ({
  AndroidImportance: { HIGH: 4 },
  SchedulableTriggerInputTypes: { DATE: "date" },
  setNotificationChannelAsync: async () => {},
  cancelScheduledNotificationAsync: async () => {},
  scheduleNotificationAsync: async () => "id",
}));
mock.module("react-native-uuid", () => ({ default: { v4: () => "uuid" } }));
mockAppModule("contexts/AlertProvider.tsx", { useAlert: () => ({ showAlert: () => {} }) });
mockAppModule("lib/api.ts", { syncRecurringPayments: async () => ok(undefined) });
mockAppModule("lib/walletApi.ts", {
  loadWalletIfNeeded: async () => ok(true),
  fetchOffchainBalance: async () => ok({ spendable: 10_000_000 }),
  getArkInfo: async () => ok({ server_pubkey: "server-pubkey" }),
});
mock.module("ky", () => ({
  default: {
    get: () => ({
      json: async () => ({
        tag: "payRequest",
        callback: "https://example.com/callback",
        minSendable: 1_000,
        maxSendable: 1_000_000_000,
        commentAllowed: 0,
        ark: ARK_ADDRESS,
      }),
    }),
  },
}));

const { executeDueRecurringPayments, cancelRecurringPayment, pauseRecurringPayment } =
  await import("../../src/lib/recurringPayments");
const { useRecurringPaymentStore } = await import("../../src/store/recurringPaymentStore");
const { createRecurringPayment } = await import("../../src/lib/recurringSchedule");

const addDueSchedule = (overrides) => {
  const now = Date.now();
  const schedule = createRecurringPayment(
    "rent",
    {
      label: "Rent",
      destination: "landlord@example.com",
      destinationType: "lnurl",
      amountSat: 50_000,
      comment: "",
      interval: { unit: "month", every: 1 },
      startAt: now - 60_000,
      endAt: null,
      maxOccurrences: null,
      ...overrides,
    },
    now - 120_000,
  );
  useRecurringPaymentStore.getState().upsertSchedule(schedule);
  return schedule;
};

const storedSchedule = () => useRecurringPaymentStore.getState().schedules.rent;
const totalNativeSends = () =>
  nativeCalls.sendArkoor + nativeCalls.payOffer + nativeCalls.payLightningAddress;

beforeEach(() => {
  useRecurringPaymentStore.getState().reset();
  nativeCalls.sendArkoor = 0;
  nativeCalls.payOffer = 0;
  nativeCalls.payLightningAddress = 0;
  historyGate = null;
  validationGate = null;
});

describe("recurring payment stopped while the transfer is being prepared", () => {
  test("cancelling while history() is waiting sends nothing (Lightning address via Ark)", async () => {
    addDueSchedule();
    historyGate = deferred();

    const run = executeDueRecurringPayments("manual");
    await flush();
    expect(totalNativeSends()).toBe(0);

    await cancelRecurringPayment("rent");
    expect(storedSchedule()).toBeUndefined();

    historyGate.resolve();
    const summary = await run;

    expect(totalNativeSends()).toBe(0);
    expect(storedSchedule()).toBeUndefined();
    expect(summary).toEqual({ paid: 0, failed: 0, needsAttention: 0 });
  });

  test("pausing while history() is waiting sends nothing and keeps it paused", async () => {
    addDueSchedule();
    historyGate = deferred();

    const run = executeDueRecurringPayments("manual");
    await flush();
    expect(totalNativeSends()).toBe(0);

    await pauseRecurringPayment("rent");
    historyGate.resolve();
    const summary = await run;

    expect(totalNativeSends()).toBe(0);
    expect(summary).toEqual({ paid: 0, failed: 0, needsAttention: 0 });
    const schedule = storedSchedule();
    expect(schedule.status).toBe("paused");
    // Nothing was attempted: no failure is recorded and the marker is cleared,
    // so resuming later isn't treated as an interrupted payment.
    expect(schedule.inFlight).toBeNull();
    expect(schedule.runs).toEqual([]);
    expect(schedule.lastError).toBeNull();
    expect(schedule.nextOccurrenceIndex).toBe(0);
  });

  test("pausing while the Ark address is being validated sends nothing", async () => {
    addDueSchedule({ destination: ARK_ADDRESS, destinationType: "ark" });

    // Let preflight finish, then hold the send at address validation.
    validationGate = deferred();
    const run = executeDueRecurringPayments("manual");
    await flush();
    expect(totalNativeSends()).toBe(0);

    await pauseRecurringPayment("rent");
    validationGate.resolve();
    await run;

    expect(totalNativeSends()).toBe(0);
    expect(storedSchedule().status).toBe("paused");
    expect(storedSchedule().inFlight).toBeNull();
  });

  test("a schedule left alone is still paid once", async () => {
    addDueSchedule();
    historyGate = deferred();

    const run = executeDueRecurringPayments("manual");
    await flush();
    historyGate.resolve();
    const summary = await run;

    expect(nativeCalls.sendArkoor).toBe(1);
    expect(summary.paid).toBe(1);
    expect(storedSchedule().status).toBe("active");
    expect(storedSchedule().inFlight).toBeNull();
    expect(storedSchedule().occurrencesPaid).toBe(1);
  });
});
