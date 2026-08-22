import { Platform } from "react-native";
import * as Device from "expo-device";
import {
  isBatteryOptimizationEnabled as nativeIsBatteryOptimizationEnabled,
  openBatteryOptimizationSettings as nativeOpenBatteryOptimizationSettings,
} from "noah-tools";

export function isBatteryOptimizationEnabled(): boolean {
  return nativeIsBatteryOptimizationEnabled();
}

export function openBatteryOptimizationSettings(): boolean {
  return nativeOpenBatteryOptimizationSettings();
}

export function shouldPromptForBatteryOptimization(): boolean {
  return (
    Platform.OS === "android" && Device.isDevice && isBatteryOptimizationEnabled()
  );
}