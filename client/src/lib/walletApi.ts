import {
  createMnemonic,
  loadWallet as loadWalletNitro,
  createWallet as createWalletNitro,
  mailboxAuthorization as mailboxAuthorizationNitro,
  onchainBalance as onchainBalanceNitro,
  offchainBalance as offchainBalanceNitro,
  sync as syncNitro,
  onchainSync as onchainSyncNitro,
  closeWallet as closeWalletNitro,
  isWalletLoaded as isWalletLoadedNitro,
  verifyMessage as verifyMessageNitro,
  maintenance as maintenanceNitro,
  maintenanceRefresh as maintenanceRefreshNitro,
  maintenanceWithOnchain as maintenanceWithOnchainNitro,
  maintenanceDelegated as maintenanceDelegatedNitro,
  maintenanceWithOnchainDelegated as maintenanceWithOnchainDelegatedNitro,
  refreshServer as refreshServerNitro,
  getArkInfo as getArkInfoNitro,
  signMesssageWithMnemonic as signMessageWithMnemonicNitro,
  deriveKeypairFromMnemonic as deriveKeypairFromMnemonicNitro,
  vtxos as vtxosNitro,
  getExpiringVtxos as getExpiringVtxosNitro,
  type BarkArkInfo,
  type BarkCreateOpts,
  type OnchainBalanceResult,
  type OffchainBalanceResult,
  KeyPairResult,
} from "react-native-nitro-ark";
import * as Keychain from "react-native-keychain";
import RNFSTurbo from "react-native-fs-turbo";
import {
  ARK_DATA_PATH,
  CACHES_DIRECTORY_PATH,
  DOCUMENT_DIRECTORY_PATH,
  MNEMONIC_KEYCHAIN_SERVICE,
  ACTIVE_WALLET_CONFIG,
  hasGooglePlayServices,
} from "../constants";
import {
  deriveStoreNextKeypair,
  getArkServerAccessToken,
  peakKeyPair,
  getMnemonic,
  resetArkServerAccessToken,
  resetServerAuthToken,
  setArkServerAccessToken,
  setMnemonic,
} from "./crypto";
import { err, ok, Result, ResultAsync } from "neverthrow";
import logger from "~/lib/log";
import { storeNativeMnemonic, storeNativeServerAccessToken } from "noah-tools";
import { useWalletStore } from "~/store/walletStore";
import { APP_VARIANT } from "~/config";

const log = logger("walletApi");

export interface MailboxAuthorizationResult {
  mailbox_id: string;
  expiry: number;
  encoded: string;
}

type WalletCreationOptions = Omit<BarkCreateOpts, "mnemonic">;

export interface WalletServerAccessTokenOptions {
  serverAccessToken?: string | null;
}

export const isArkServerAccessTokenEnabled = APP_VARIANT === "mainnet";

const normalizeServerAccessToken = (token: string | null | undefined): string | null => {
  const trimmed = token?.trim();
  return trimmed ? trimmed : null;
};

const syncNativeServerAccessToken = async (token: string | null): Promise<void> => {
  if (hasGooglePlayServices()) {
    return;
  }

  const nativeResult = await ResultAsync.fromPromise(
    storeNativeServerAccessToken(token ?? ""),
    (e) => e as Error,
  );
  if (nativeResult.isErr()) {
    log.w("Failed to store Ark server access token natively for push service", [
      nativeResult.error,
    ]);
  }
};

export const saveArkServerAccessToken = async (token: string): Promise<Result<void, Error>> => {
  if (!isArkServerAccessTokenEnabled) {
    return ok(undefined);
  }

  const normalized = normalizeServerAccessToken(token);
  const storeResult = normalized
    ? await setArkServerAccessToken(normalized)
    : await resetArkServerAccessToken();
  if (storeResult.isErr()) {
    return err(storeResult.error);
  }

  await syncNativeServerAccessToken(normalized);
  return ok(undefined);
};

export const clearArkServerAccessToken = async (): Promise<Result<void, Error>> => {
  const resetResult = await resetArkServerAccessToken();
  if (resetResult.isErr()) {
    return err(resetResult.error);
  }

  await syncNativeServerAccessToken(null);
  return ok(undefined);
};

const getWalletCreationOptions = async (
  options?: WalletServerAccessTokenOptions,
): Promise<Result<WalletCreationOptions, Error>> => {
  if (!isArkServerAccessTokenEnabled) {
    return ok(ACTIVE_WALLET_CONFIG);
  }

  const token =
    options && "serverAccessToken" in options
      ? normalizeServerAccessToken(options.serverAccessToken)
      : null;
  const tokenResult =
    options && "serverAccessToken" in options ? ok(token) : await getArkServerAccessToken();

  if (tokenResult.isErr()) {
    return err(tokenResult.error);
  }

  const normalizedToken = normalizeServerAccessToken(tokenResult.value);
  if (!normalizedToken) {
    return ok(ACTIVE_WALLET_CONFIG);
  }

  const activeConfig = ACTIVE_WALLET_CONFIG.config;
  if (!activeConfig) {
    return ok(ACTIVE_WALLET_CONFIG);
  }

  return ok({
    ...ACTIVE_WALLET_CONFIG,
    config: {
      ...activeConfig,
      server_access_token: normalizedToken,
    },
  });
};

const createWalletFromMnemonic = async (
  mnemonic: string,
  options?: WalletServerAccessTokenOptions,
): Promise<Result<void, Error>> => {
  if (isArkServerAccessTokenEnabled && options && "serverAccessToken" in options) {
    const saveResult = await saveArkServerAccessToken(options.serverAccessToken ?? "");
    if (saveResult.isErr()) {
      return err(saveResult.error);
    }
  }

  const isLoadedResult = await ResultAsync.fromPromise(isWalletLoadedNitro(), (e) => e as Error);
  if (isLoadedResult.isErr()) return err(isLoadedResult.error);

  if (isLoadedResult.value) {
    const closeResult = await ResultAsync.fromPromise(closeWalletNitro(), (e) => e as Error);
    if (closeResult.isErr()) return err(closeResult.error);
  }

  const configResult = await getWalletCreationOptions(options);
  if (configResult.isErr()) {
    return err(configResult.error);
  }

  const createResult = await ResultAsync.fromPromise(
    createWalletNitro(ARK_DATA_PATH, { ...configResult.value, mnemonic }),
    (e) => e as Error,
  );

  if (createResult.isErr()) {
    return err(createResult.error);
  }

  const setMnemonicResult = await ResultAsync.fromPromise(setMnemonic(mnemonic), (e) => e as Error);

  if (setMnemonicResult.isErr()) {
    return err(setMnemonicResult.error);
  }

  if (!hasGooglePlayServices()) {
    const storeNativeResult = await ResultAsync.fromPromise(
      storeNativeMnemonic(mnemonic),
      (e) => e as Error,
    );
    if (storeNativeResult.isErr()) {
      log.w("Failed to store mnemonic natively for push service", [storeNativeResult.error]);
    }
  }

  const loadResult = await loadWallet(mnemonic, options);
  if (loadResult.isErr()) {
    return err(loadResult.error);
  }

  const deriveResult = await deriveStoreNextKeypair();
  if (deriveResult.isErr()) {
    return err(deriveResult.error);
  }

  const keypairResult = await peakKeyPair(0);
  if (keypairResult.isErr()) {
    return err(keypairResult.error);
  }

  return ok(undefined);
};

export const createWallet = async (
  options?: WalletServerAccessTokenOptions,
): Promise<Result<void, Error>> => {
  const mnemonicResult = await ResultAsync.fromPromise(createMnemonic(), (e) => e as Error);
  if (mnemonicResult.isErr()) {
    return err(mnemonicResult.error);
  }
  return createWalletFromMnemonic(mnemonicResult.value, options);
};

export const restoreWallet = async (
  mnemonic: string,
  options?: WalletServerAccessTokenOptions,
): Promise<Result<boolean, Error>> => {
  if (isArkServerAccessTokenEnabled && options && "serverAccessToken" in options) {
    const saveResult = await saveArkServerAccessToken(options.serverAccessToken ?? "");
    if (saveResult.isErr()) {
      return err(saveResult.error);
    }
  }

  const setResult = await ResultAsync.fromPromise(setMnemonic(mnemonic), (e) => e as Error);
  if (setResult.isErr()) {
    return err(setResult.error);
  }

  if (!hasGooglePlayServices()) {
    const storeNativeResult = await ResultAsync.fromPromise(
      storeNativeMnemonic(mnemonic),
      (e) => e as Error,
    );
    if (storeNativeResult.isErr()) {
      log.w("Failed to store mnemonic natively for push service", [storeNativeResult.error]);
    }
  }
  return loadWallet(mnemonic, options);
};

const loadWallet = async (
  mnemonic: string,
  options?: WalletServerAccessTokenOptions,
): Promise<Result<boolean, Error>> => {
  const configResult = await getWalletCreationOptions(options);
  if (configResult.isErr()) {
    return err(configResult.error);
  }

  const loadResult = await ResultAsync.fromPromise(
    loadWalletNitro(ARK_DATA_PATH, {
      mnemonic,
      ...configResult.value,
    }),
    (e) => e as Error,
  );

  if (loadResult.isErr()) {
    return err(loadResult.error);
  }

  return ok(true);
};

const loadWalletFromStorage = async (): Promise<Result<boolean, Error>> => {
  const mnemonicResult = await getMnemonic();

  if (mnemonicResult.isErr()) {
    return err(mnemonicResult.error);
  }

  const mnemonic = mnemonicResult.value;
  if (!mnemonic) {
    return err(new Error("No wallet found. Please create a wallet first."));
  }

  return loadWallet(mnemonic);
};

export const loadWalletIfNeeded = async (): Promise<Result<boolean, Error>> => {
  const isWalletSuspended = useWalletStore.getState().isWalletSuspended;
  if (isWalletSuspended) {
    log.d("Wallet is suspended, skipping load");
    return ok(false);
  }

  const isLoadedResult = await ResultAsync.fromPromise(isWalletLoadedNitro(), (e) => e as Error);
  if (isLoadedResult.isErr()) {
    return err(isLoadedResult.error);
  }

  if (isLoadedResult.value) {
    return ok(true);
  }

  return loadWalletFromStorage();
};

export const closeWalletIfLoaded = async (): Promise<Result<boolean, Error>> => {
  const isLoaded = await isWalletLoadedNitro();

  log.d("Checking if wallet is loaded:", [isLoaded]);

  if (!isLoaded) {
    return ok(true);
  }
  const closeWalletResult = await ResultAsync.fromPromise(closeWalletNitro(), (e) => e as Error);
  if (closeWalletResult.isErr()) {
    log.w("Failed to close wallet:", [closeWalletResult.error]);
    return ok(false);
  }

  return ok(true);
};

export const fetchOnchainBalance = async (): Promise<Result<OnchainBalanceResult, Error>> => {
  return ResultAsync.fromPromise(onchainBalanceNitro(), (e) => e as Error);
};

export const fetchOffchainBalance = async (): Promise<Result<OffchainBalanceResult, Error>> => {
  return ResultAsync.fromPromise(offchainBalanceNitro(), (e) => e as Error);
};

export const sync = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(syncNitro(), (e) => e as Error);
};

export const signMesssageWithMnemonic = async (
  k1: string,
  mnemonic: string,
  network: string,
  index: number,
): Promise<Result<string, Error>> => {
  return ResultAsync.fromPromise(
    signMessageWithMnemonicNitro(k1, mnemonic, network, index),
    (e) => e as Error,
  );
};

export const deriveKeypairFromMnemonic = async (
  mnemonic: string,
  network: string,
  index: number,
): Promise<Result<KeyPairResult, Error>> => {
  return ResultAsync.fromPromise(
    deriveKeypairFromMnemonicNitro(mnemonic, network, index),
    (e) => e as Error,
  );
};

export const verifyMessage = async (
  message: string,
  signature: string,
  publicKey: string,
): Promise<Result<boolean, Error>> => {
  return ResultAsync.fromPromise(
    verifyMessageNitro(message, signature, publicKey),
    (e) => e as Error,
  );
};

export const onchainSync = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(onchainSyncNitro(), (e) => e as Error);
};

export const maintanance = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(maintenanceNitro(), (e) => e as Error);
};

export const maintenanceRefresh = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(maintenanceRefreshNitro(), (e) => e as Error);
};

export const maintenanceWithOnchain = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(maintenanceWithOnchainNitro(), (e) => e as Error);
};

export const maintenanceDelegated = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(maintenanceDelegatedNitro(), (e) => e as Error);
};

export const maintenanceWithOnchainDelegated = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(maintenanceWithOnchainDelegatedNitro(), (e) => e as Error);
};

export const refreshServer = async (): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(refreshServerNitro(), (e) => e as Error);
};

export const getArkInfo = async (): Promise<Result<BarkArkInfo, Error>> => {
  return ResultAsync.fromPromise(getArkInfoNitro(), (e) => e as Error);
};

export const deleteWallet = async (): Promise<Result<void, Error>> => {
  // Check if document directory path exists
  // Then recursively delete all files and directories within it
  const documentDirectoryExists = RNFSTurbo.exists(DOCUMENT_DIRECTORY_PATH);

  if (documentDirectoryExists) {
    const dircontents = RNFSTurbo.readdir(DOCUMENT_DIRECTORY_PATH);
    log.d(`Directory contents: ${dircontents}`);
    dircontents.forEach((n) => {
      log.d(`Deleting file: ${n}`);
      RNFSTurbo.unlink(`${DOCUMENT_DIRECTORY_PATH}/${n}`);
    });
  }

  // Check if cache directory path exists
  // Then recursively delete all files and directories within it
  const cacheDirectoryExists = RNFSTurbo.exists(CACHES_DIRECTORY_PATH);

  if (cacheDirectoryExists) {
    const cacheContents = RNFSTurbo.readdir(CACHES_DIRECTORY_PATH);
    log.d(`Cache contents: ${cacheContents}`);

    cacheContents.forEach((n) => {
      log.d(`Deleting file: ${n}`);
      RNFSTurbo.unlink(`${CACHES_DIRECTORY_PATH}/${n}`);
    });
  }

  const clearKeyChainResult = await ResultAsync.fromPromise(
    clearStaleKeychain(),
    (e) => e as Error,
  );

  if (clearKeyChainResult.isErr()) {
    log.e("Failed to clear keychain while deleting wallet", [clearKeyChainResult.error]);
    return err(clearKeyChainResult.error);
  }

  return ok(undefined);
};

export const getVtxos = async () => {
  return ResultAsync.fromPromise(vtxosNitro(), (e) => e as Error);
};

export const getExpiringVtxos = async () => {
  return ResultAsync.fromPromise(
    getExpiringVtxosNitro(ACTIVE_WALLET_CONFIG.config?.vtxo_refresh_expiry_threshold || 288),
    (e) => e as Error,
  );
};

export const getMailboxAuthorization = async (
  authorizationExpiry: number,
): Promise<Result<MailboxAuthorizationResult, Error>> => {
  return ResultAsync.fromPromise(mailboxAuthorizationNitro(authorizationExpiry), (e) => e as Error);
};

/**
 * Checks if wallet data exists on disk.
 * Used to detect stale keychain state after app reinstall on iOS.
 * iOS Keychain persists after uninstall, but app data is deleted.
 */
export const walletDataExists = (): boolean => {
  try {
    if (!RNFSTurbo.exists(ARK_DATA_PATH)) {
      return false;
    }
    const contents = RNFSTurbo.readdir(ARK_DATA_PATH);
    return contents.length > 0;
  } catch {
    return false;
  }
};

/**
 * Clears stale keychain mnemonic when wallet data doesn't exist.
 * This handles the iOS reinstall case where keychain persists but app data is gone.
 */
export const clearStaleKeychain = async (): Promise<Result<void, Error>> => {
  const mnemonicResetResult = await ResultAsync.fromPromise(
    Keychain.resetGenericPassword({ service: MNEMONIC_KEYCHAIN_SERVICE }),
    (e) => e as Error,
  );
  if (mnemonicResetResult.isErr()) {
    log.w("Failed to clear stale keychain", [mnemonicResetResult.error]);
    return err(mnemonicResetResult.error);
  }

  const tokenResetResult = await resetServerAuthToken();
  if (tokenResetResult.isErr()) {
    log.w("Failed to clear server auth token", [tokenResetResult.error]);
    return err(tokenResetResult.error);
  }

  const arkTokenResetResult = await clearArkServerAccessToken();
  if (arkTokenResetResult.isErr()) {
    log.w("Failed to clear Ark server access token", [arkTokenResetResult.error]);
    return err(arkTokenResetResult.error);
  }

  log.i("Cleared stale keychain mnemonic after reinstall detection");
  return ok(undefined);
};
