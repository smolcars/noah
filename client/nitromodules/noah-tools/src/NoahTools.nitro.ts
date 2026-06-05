import type { HybridObject } from "react-native-nitro-modules";

export interface HttpResponse {
  status: number;
  body: string;
  headers: Record<string, string>;
}

export interface UnifiedPushDistributor {
  id: string;
  name: string;
  isSaved: boolean;
  isConnected: boolean;
}

export interface NoahTools extends HybridObject<{ ios: "swift"; android: "kotlin" }> {
  getAppVariant(): string;
  getAppLogs(): Promise<string[]>;
  createBackup(mnemonic: string): Promise<string>;
  restoreBackup(encryptedData: string, mnemonic: string): Promise<boolean>;

  // Native HTTP client for POST requests
  nativePost(
    url: string,
    body: string,
    headers: Record<string, string>,
    timeoutSeconds: number,
  ): Promise<HttpResponse>;

  // Native HTTP client for GET requests
  nativeGet(
    url: string,
    headers: Record<string, string>,
    timeoutSeconds: number,
  ): Promise<HttpResponse>;

  // Native logging
  nativeLog(level: string, tag: string, message: string): void;

  // Audio playback
  playAudio(filePath: string): Promise<void>;
  pauseAudio(): void;
  stopAudio(): void;
  resumeAudio(): void;
  seekAudio(positionSeconds: number): void;
  getAudioDuration(): number;
  getAudioPosition(): number;
  isAudioPlaying(): boolean;

  // Widget data sharing
  updateWidgetData(
    totalBalance: number,
    onchainBalance: number,
    offchainBalance: number,
    pendingBalance: number,
    closestExpiryBlocks: number,
    expiryThreshold: number,
    appGroup: string,
  ): void;

  // Android methods only!
  isGooglePlayServicesAvailable(): boolean;
  registerUnifiedPush(): void;
  getUnifiedPushEndpoint(): string;
  getUnifiedPushDistributors(): UnifiedPushDistributor[];
  setUnifiedPushDistributor(distributorId: string | null): void;
  storeNativeMnemonic(mnemonic: string): Promise<void>;
}
