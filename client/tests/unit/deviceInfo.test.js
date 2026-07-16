import { describe, expect, mock, test } from "bun:test";

mock.module("expo-application", () => ({
  nativeApplicationVersion: "0.1.4",
  nativeBuildVersion: "27",
}));
mock.module("expo-constants", () => ({
  default: { expoConfig: { version: "0.1.4" } },
}));
mock.module("expo-device", () => ({
  manufacturer: "Apple",
  modelName: "iPhone 12 Pro",
  osName: "iOS",
  osVersion: "26.5",
}));

const {
  DEVICE_INFO_REFRESH_INTERVAL_MS,
  getCurrentDeviceInfo,
  getDeviceInfoFingerprint,
  shouldReportDeviceInfo,
} = await import("../../src/lib/deviceInfo");

const now = Date.UTC(2026, 6, 16);
const deviceInfo = getCurrentDeviceInfo();

describe("device information reporting", () => {
  test("collects app, build, OS, and hardware details", () => {
    expect(deviceInfo).toEqual({
      device_manufacturer: "Apple",
      device_model: "iPhone 12 Pro",
      os_name: "iOS",
      os_version: "26.5",
      app_version: "0.1.4",
      app_build: "27",
    });
  });

  test("does not report unchanged recent details", () => {
    expect(
      shouldReportDeviceInfo(
        deviceInfo,
        getDeviceInfoFingerprint(deviceInfo),
        now - 1_000,
        now,
      ),
    ).toBe(false);
  });

  test("reports after app, build, or OS changes", () => {
    const previousVersions = [
      { ...deviceInfo, app_version: "0.1.3" },
      { ...deviceInfo, app_build: "26" },
      { ...deviceInfo, os_version: "26.4" },
    ];

    for (const previousDeviceInfo of previousVersions) {
      expect(
        shouldReportDeviceInfo(
          deviceInfo,
          getDeviceInfoFingerprint(previousDeviceInfo),
          now - 1_000,
          now,
        ),
      ).toBe(true);
    }
  });

  test("reports again when a failed attempt left no successful state", () => {
    expect(shouldReportDeviceInfo(deviceInfo, null, null, now)).toBe(true);
    expect(shouldReportDeviceInfo(deviceInfo, null, null, now + 1_000)).toBe(true);
  });

  test("refreshes unchanged details after 30 days", () => {
    expect(
      shouldReportDeviceInfo(
        deviceInfo,
        getDeviceInfoFingerprint(deviceInfo),
        now - DEVICE_INFO_REFRESH_INTERVAL_MS,
        now,
      ),
    ).toBe(true);
  });

  test("repairs a report timestamp that is in the future", () => {
    expect(
      shouldReportDeviceInfo(
        deviceInfo,
        getDeviceInfoFingerprint(deviceInfo),
        now + 1_000,
        now,
      ),
    ).toBe(true);
  });
});
