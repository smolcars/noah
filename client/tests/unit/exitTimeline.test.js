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
  EXIT_STATE_LABELS,
  EXIT_STATE_ORDER,
  getExitStatusText,
} = await import("../../src/lib/exitTimeline");

describe("canceled exit timelines", () => {
  test("exposes canceled as a terminal exit state", () => {
    expect(EXIT_STATE_ORDER.at(-1)).toBe("Canceled");
    expect(EXIT_STATE_LABELS.Canceled).toBe("Canceled");
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
