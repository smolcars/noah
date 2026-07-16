import * as Application from "expo-application";
import Constants from "expo-constants";
import * as Device from "expo-device";

import type { DeviceInfo } from "~/types/serverTypes";

export const DEVICE_INFO_REFRESH_INTERVAL_MS = 30 * 24 * 60 * 60 * 1000;

export const getCurrentDeviceInfo = (): DeviceInfo => ({
  device_manufacturer: Device.manufacturer,
  device_model: Device.modelName,
  os_name: Device.osName,
  os_version: Device.osVersion,
  app_version: Application.nativeApplicationVersion ?? Constants.expoConfig?.version ?? null,
  app_build: Application.nativeBuildVersion,
});

export const getDeviceInfoFingerprint = (deviceInfo: DeviceInfo): string =>
  JSON.stringify([
    deviceInfo.device_manufacturer,
    deviceInfo.device_model,
    deviceInfo.os_name,
    deviceInfo.os_version,
    deviceInfo.app_version,
    deviceInfo.app_build,
  ]);

export const shouldReportDeviceInfo = (
  deviceInfo: DeviceInfo,
  lastReportedFingerprint: string | null,
  lastReportedAt: number | null,
  now = Date.now(),
): boolean => {
  if (lastReportedFingerprint !== getDeviceInfoFingerprint(deviceInfo)) {
    return true;
  }

  if (lastReportedAt === null || lastReportedAt > now) {
    return true;
  }

  return now - lastReportedAt >= DEVICE_INFO_REFRESH_INTERVAL_MS;
};
