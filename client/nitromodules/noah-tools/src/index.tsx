import { NitroModules } from "react-native-nitro-modules";
import type {
  BackupFileInfo,
  DecryptedBackupInfo,
  NoahTools,
  HttpResponse,
} from "./NoahTools.nitro";

const NoahToolsHybridObject = NitroModules.createHybridObject<NoahTools>("NoahTools");
export type LogLevel = "verbose" | "debug" | "info" | "warn" | "error";

export function getAppVariant(): "mainnet" | "signet" | "regtest" {
  return NoahToolsHybridObject.getAppVariant() as "mainnet" | "signet" | "regtest";
}

export function getAppLogs(): Promise<string[]> {
  return NoahToolsHybridObject.getAppLogs();
}

export function createBackup(mnemonic: string): Promise<string> {
  return NoahToolsHybridObject.createBackup(mnemonic);
}

export function restoreBackup(encryptedData: string, mnemonic: string): Promise<boolean> {
  return NoahToolsHybridObject.restoreBackup(encryptedData, mnemonic);
}

export function encryptWalletSnapshot(
  snapshotPath: string,
  manifestJson: string,
  destinationPath: string,
  mnemonic: string,
): Promise<BackupFileInfo> {
  return NoahToolsHybridObject.encryptWalletSnapshot(
    snapshotPath,
    manifestJson,
    destinationPath,
    mnemonic,
  );
}

export function decryptWalletBackup(
  encryptedPath: string,
  destinationDirectory: string,
  mnemonic: string,
): Promise<DecryptedBackupInfo> {
  return NoahToolsHybridObject.decryptWalletBackup(encryptedPath, destinationDirectory, mnemonic);
}

export function installWalletSnapshot(
  snapshotPath: string,
  walletDataPath: string,
): Promise<string> {
  return NoahToolsHybridObject.installWalletSnapshot(snapshotPath, walletDataPath);
}

export function finalizeWalletSnapshotInstall(rollbackPath: string): Promise<void> {
  return NoahToolsHybridObject.finalizeWalletSnapshotInstall(rollbackPath);
}

export function rollbackWalletSnapshotInstall(
  walletDataPath: string,
  rollbackPath: string,
): Promise<void> {
  return NoahToolsHybridObject.rollbackWalletSnapshotInstall(walletDataPath, rollbackPath);
}

export function nativePost(
  url: string,
  body: string,
  headers: Record<string, string>,
  timeoutSeconds: number = 30,
): Promise<HttpResponse> {
  return NoahToolsHybridObject.nativePost(url, body, headers, timeoutSeconds);
}

export function nativeGet(
  url: string,
  headers: Record<string, string>,
  timeoutSeconds: number = 30,
): Promise<HttpResponse> {
  return NoahToolsHybridObject.nativeGet(url, headers, timeoutSeconds);
}

export function uploadFile(
  url: string,
  path: string,
  headers: Record<string, string>,
  timeoutSeconds: number = 60,
): Promise<void> {
  return NoahToolsHybridObject.uploadFile(url, path, headers, timeoutSeconds);
}

export function downloadFile(
  url: string,
  path: string,
  headers: Record<string, string> = {},
  timeoutSeconds: number = 60,
): Promise<void> {
  return NoahToolsHybridObject.downloadFile(url, path, headers, timeoutSeconds);
}

export function nativeLog(level: LogLevel, tag: string, message: string): void {
  return NoahToolsHybridObject.nativeLog(level, tag, message);
}

export function playAudio(filePath: string): Promise<void> {
  return NoahToolsHybridObject.playAudio(filePath);
}

export function pauseAudio(): void {
  return NoahToolsHybridObject.pauseAudio();
}

export function stopAudio(): void {
  return NoahToolsHybridObject.stopAudio();
}

export function resumeAudio(): void {
  return NoahToolsHybridObject.resumeAudio();
}

export function seekAudio(positionSeconds: number): void {
  return NoahToolsHybridObject.seekAudio(positionSeconds);
}

export function getAudioDuration(): number {
  return NoahToolsHybridObject.getAudioDuration();
}

export function getAudioPosition(): number {
  return NoahToolsHybridObject.getAudioPosition();
}

export function isAudioPlaying(): boolean {
  return NoahToolsHybridObject.isAudioPlaying();
}

export function updateWidgetData(
  totalBalance: number,
  onchainBalance: number,
  offchainBalance: number,
  pendingBalance: number,
  closestExpiryBlocks: number,
  expiryThreshold: number,
  appGroup: string,
): void {
  return NoahToolsHybridObject.updateWidgetData(
    totalBalance,
    onchainBalance,
    offchainBalance,
    pendingBalance,
    closestExpiryBlocks,
    expiryThreshold,
    appGroup,
  );
}

export function isGooglePlayServicesAvailable(): boolean {
  return NoahToolsHybridObject.isGooglePlayServicesAvailable();
}

export function registerUnifiedPush(): void {
  NoahToolsHybridObject.registerUnifiedPush();
}

export function getUnifiedPushEndpoint(): string {
  return NoahToolsHybridObject.getUnifiedPushEndpoint();
}

export function getUnifiedPushDistributors() {
  return NoahToolsHybridObject.getUnifiedPushDistributors();
}

export function setUnifiedPushDistributor(distributorId: string | null) {
  NoahToolsHybridObject.setUnifiedPushDistributor(distributorId);
}

export function storeNativeMnemonic(mnemonic: string): Promise<void> {
  return NoahToolsHybridObject.storeNativeMnemonic(mnemonic);
}

export function clearNativeMnemonic(): Promise<void> {
  return NoahToolsHybridObject.clearNativeMnemonic();
}

export type { HttpResponse } from "./NoahTools.nitro";
