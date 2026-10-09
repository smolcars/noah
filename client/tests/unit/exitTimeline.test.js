import { describe, expect, mock, test } from "bun:test";

globalThis.__DEV__ = false;

mock.module("noah-tools", () => ({
  getAppVariant: () => "mainnet",
  isGooglePlayServicesAvailable: () => true,
  nativeLog: () => {},
}));
mock.module("react-native", () => ({
  Platform: { OS: "ios" },
}));
mock.module("react-native-fs-turbo", () => ({
  default: {
    CachesDirectoryPath: "/tmp",
    DocumentDirectoryPath: "/tmp",
  },
}));
mock.module("expo-device", () => ({
  isDevice: true,
  manufacturer: "Apple",
  modelName: "iPhone 12 Pro",
  osName: "iOS",
  osVersion: "26.5",
}));

const {
  buildExitTimelineItems,
  getExitStateLabels,
  EXIT_STATE_ORDER,
  getExitStatusText,
  isCancelableExit,
} = await import("../../src/lib/exitTimeline");

describe("exit cancellation eligibility", () => {
  test("allows a newly started exit", () => {
    expect(isCancelableExit("Start", { kind: "start", tip_height: 100 })).toBe(true);
  });

  test("allows processing until the final exit transaction is broadcast", () => {
    for (const kind of [
      "verify-inputs",
      "awaiting-input-confirmation",
      "awaiting-cpfp-broadcast",
    ]) {
      expect(
        isCancelableExit("Processing", {
          kind: "processing",
          tip_height: 100,
          transactions: [{ txid: "final", status: { kind } }],
        }),
      ).toBe(true);
    }

    expect(
      isCancelableExit("Processing", {
        kind: "processing",
        tip_height: 100,
        transactions: [
          { txid: "ancestor", status: { kind: "confirmed" } },
          { txid: "final", status: { kind: "awaiting-cpfp-broadcast" } },
        ],
      }),
    ).toBe(true);
  });

  test("rejects processing after the final exit transaction is broadcast", () => {
    for (const kind of ["awaiting-confirmation", "confirmed"]) {
      expect(
        isCancelableExit("Processing", {
          kind: "processing",
          tip_height: 100,
          transactions: [{ txid: "final", status: { kind } }],
        }),
      ).toBe(false);
    }
  });

  test("rejects states beyond the abortable window", () => {
    for (const state of [
      "AwaitingDelta",
      "Claimable",
      "ClaimInProgress",
      "Claimed",
      "VtxoAlreadySpent",
      "Canceled",
    ]) {
      expect(isCancelableExit(state, { kind: "awaiting-delta", tip_height: 100 })).toBe(false);
    }
  });
});

describe("canceled exit timelines", () => {
  test("exposes canceled as a terminal exit state", () => {
    expect(EXIT_STATE_ORDER.at(-1)).toBe("Canceled");
    expect(getExitStateLabels().Canceled).toBe("Canceled");
    expect(getExitStatusText({ state: "Canceled" })).toBe("Exit processing was canceled");
  });

  test("describes a canceled state in timeline history", () => {
    const items = buildExitTimelineItems({
      history: ["Start", "Canceled"],
      historyDetails: [
        { kind: "start", tip_height: 100 },
        { kind: "canceled", tip_height: 101 },
      ],
      currentState: "Canceled",
      currentDetails: { kind: "canceled", tip_height: 101 },
      currentBlockHeight: 101,
    });

    expect(items.at(-1)).toMatchObject({
      state: "Canceled",
      label: "Canceled",
      description: "Exit processing was canceled before completion.",
      isCurrent: true,
    });
  });
});
