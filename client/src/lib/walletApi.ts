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
  refreshVtxosDelegated as refreshVtxosDelegatedNitro,
  estimateRefreshFee as estimateRefreshFeeNitro,
  refreshServer as refreshServerNitro,
  getArkInfo as getArkInfoNitro,
  syncPendingRounds as syncPendingRoundsNitro,
  signMesssageWithMnemonic as signMessageWithMnemonicNitro,
  deriveKeypairFromMnemonic as deriveKeypairFromMnemonicNitro,
  vtxos as vtxosNitro,
  decodeVtxoHex as decodeVtxoHexNitro,
  importVtxo as importVtxoNitro,
  dangerousDropVtxo as dangerousDropVtxoNitro,
  getExpiringVtxos as getExpiringVtxosNitro,
  type BarkArkInfo,
  type BarkFeeEstimate,
  type BarkVtxo,
  type DelegatedRoundState,
  type OnchainBalanceResult,
  type OffchainBalanceResult,
  type PendingRoundStatus,
  KeyPairResult,
} from "react-native-nitro-ark";
import RNFSTurbo from "react-native-fs-turbo";
import {
  ARK_DATA_PATH,
  CACHES_DIRECTORY_PATH,
  DOCUMENT_DIRECTORY_PATH,
  ACTIVE_WALLET_CONFIG,
  shouldUseUnifiedPush,
} from "../constants";
import {
  clearMnemonic,
  deriveStoreNextKeypair,
  peakKeyPair,
  getMnemonic,
  resetServerAuthToken,
  setMnemonic,
} from "./crypto";
import { err, ok, Result, ResultAsync } from "neverthrow";
import logger from "~/lib/log";
import {
  clearNativeEsploraEndpoint,
  clearNativeMnemonic,
  storeNativeEsploraEndpoint,
  storeNativeMnemonic,
} from "noah-tools";
import { useWalletStore } from "~/store/walletStore";
import { APP_VARIANT } from "~/config";
import {
  getActiveWalletConfig,
  getDefaultEsploraEndpoint,
  getEffectiveEsploraEndpoint,
  validateEsploraEndpoint,
} from "~/lib/esplora";
import { useEsploraStore } from "~/store/esploraStore";

const log = logger("walletApi");

export interface MailboxAuthorizationResult {
  mailbox_id: string;
  expiry: number;
  encoded: string;
}

const createWalletFromMnemonic = async (mnemonic: string): Promise<Result<void, Error>> => {
  const isLoadedResult = await ResultAsync.fromPromise(isWalletLoadedNitro(), (e) => e as Error);
  if (isLoadedResult.isErr()) {
    log.error("Failed to check if wallet is loaded", [isLoadedResult.error]);
    return err(isLoadedResult.error);
  }

  if (isLoadedResult.value) {
    const closeResult = await ResultAsync.fromPromise(closeWalletNitro(), (e) => e as Error);
    if (closeResult.isErr()) {
      log.error("Failed to close wallet", [closeResult.error]);
      return err(closeResult.error);
    }
  }

  const createResult = await ResultAsync.fromPromise(
    createWalletNitro(ARK_DATA_PATH, { ...getActiveWalletConfig(), mnemonic }),
    (e) => e as Error,
  );

  if (createResult.isErr()) {
    log.error("Failed to create wallet", [createResult.error]);
    return err(createResult.error);
  }

  const setMnemonicResult = await setMnemonic(mnemonic);

  if (setMnemonicResult.isErr()) {
    log.error("Failed to set mnemonic", [setMnemonicResult.error]);
    return err(setMnemonicResult.error);
  }

  if (shouldUseUnifiedPush()) {
    const storeNativeResult = await ResultAsync.fromPromise(
      storeNativeMnemonic(mnemonic),
      (e) => e as Error,
    );
    if (storeNativeResult.isErr()) {
      log.w("Failed to store mnemonic natively for push service", [storeNativeResult.error]);
    }
  }

  const loadResult = await loadWalletWithMnemonic(mnemonic);
  if (loadResult.isErr()) {
    log.error("Failed to load wallet", [loadResult.error]);
    return err(loadResult.error);
  }

  const deriveResult = await deriveStoreNextKeypair();
  if (deriveResult.isErr()) {
    log.error("Failed to derive store next keypair", [deriveResult.error]);
    return err(deriveResult.error);
  }

  const keypairResult = await peakKeyPair(0);
  if (keypairResult.isErr()) {
    log.error("Failed to peak keypair", [keypairResult.error]);
    return err(keypairResult.error);
  }

  return ok(undefined);
};

export const createWallet = async (): Promise<Result<void, Error>> => {
  const mnemonicResult = await ResultAsync.fromPromise(createMnemonic(), (e) => e as Error);
  if (mnemonicResult.isErr()) {
    log.error("Failed to create mnemonic", [mnemonicResult.error]);
    return err(mnemonicResult.error);
  }
  return createWalletFromMnemonic(mnemonicResult.value);
};

export const restoreWallet = async (mnemonic: string): Promise<Result<boolean, Error>> => {
  const setResult = await setMnemonic(mnemonic);
  if (setResult.isErr()) {
    log.error("Failed to set mnemonic", [setResult.error]);
    return err(setResult.error);
  }

  if (shouldUseUnifiedPush()) {
    const storeNativeResult = await ResultAsync.fromPromise(
      storeNativeMnemonic(mnemonic),
      (e) => e as Error,
    );
    if (storeNativeResult.isErr()) {
      log.w("Failed to store mnemonic natively for push service", [storeNativeResult.error]);
    }
  }
  return loadWalletWithMnemonic(mnemonic);
};

export const loadWalletWithMnemonic = async (
  mnemonic: string,
  esploraEndpoint = getEffectiveEsploraEndpoint(),
): Promise<Result<boolean, Error>> => {
  const loadResult = await ResultAsync.fromPromise(
    loadWalletNitro(ARK_DATA_PATH, {
      mnemonic,
      ...getActiveWalletConfig(esploraEndpoint),
    }),
    (e) => e as Error,
  );

  if (loadResult.isErr()) {
    return err(loadResult.error);
  }

  return ok(true);
};

const persistNativeEsploraOverride = async (
  endpointOverride: string | null,
): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(
    endpointOverride ? storeNativeEsploraEndpoint(endpointOverride) : clearNativeEsploraEndpoint(),
    (error) => error as Error,
  );
};

const reloadWalletWithEsplora = async (
  mnemonic: string,
  endpoint: string,
): Promise<Result<boolean, Error>> => {
  const closeAttempt = await ResultAsync.fromPromise(closeWalletIfLoaded(), (error) =>
    error instanceof Error ? error : new Error(String(error)),
  );
  if (closeAttempt.isErr()) {
    return err(closeAttempt.error);
  }

  const closeResult = closeAttempt.value;
  if (closeResult.isErr()) {
    return err(closeResult.error);
  }
  if (!closeResult.value) {
    return err(new Error("Failed to close the wallet while changing the Esplora endpoint."));
  }

  return loadWalletWithMnemonic(mnemonic, endpoint);
};

export interface EsploraSwitchResult {
  endpoint: string;
  isDefault: boolean;
}

export const switchEsploraEndpoint = async (
  requestedOverride: string | null,
): Promise<Result<EsploraSwitchResult, Error>> => {
  if (APP_VARIANT === "regtest") {
    return err(new Error("Regtest wallets use Bitcoin Core instead of Esplora."));
  }

  const defaultEndpoint = getDefaultEsploraEndpoint();
  if (!defaultEndpoint) {
    return err(new Error("No default Esplora endpoint is configured."));
  }

  const validationResult = await validateEsploraEndpoint(requestedOverride ?? defaultEndpoint);
  if (validationResult.isErr()) {
    return err(validationResult.error);
  }

  const targetEndpoint = validationResult.value;
  const targetOverride =
    requestedOverride === null || targetEndpoint === defaultEndpoint ? null : targetEndpoint;
  const previousOverride = useEsploraStore.getState().endpointOverride;
  const previousEndpoint = getEffectiveEsploraEndpoint();

  if (!previousEndpoint) {
    return err(new Error("No current Esplora endpoint is configured."));
  }

  if (targetEndpoint === previousEndpoint) {
    const nativeResult = await persistNativeEsploraOverride(targetOverride);
    if (nativeResult.isErr()) {
      return err(nativeResult.error);
    }
    useEsploraStore.getState().setEndpointOverride(targetOverride);
    return ok({ endpoint: targetEndpoint, isDefault: targetOverride === null });
  }

  const isLoadedResult = await ResultAsync.fromPromise(
    isWalletLoadedNitro(),
    (error) => error as Error,
  );
  if (isLoadedResult.isErr()) {
    return err(isLoadedResult.error);
  }

  const wasWalletLoaded = isLoadedResult.value;
  let mnemonic: string | null = null;

  if (wasWalletLoaded) {
    const mnemonicResult = await getMnemonic();
    if (mnemonicResult.isErr()) {
      return err(mnemonicResult.error);
    }
    mnemonic = mnemonicResult.value;
    if (!mnemonic) {
      return err(new Error("No wallet mnemonic is available to reload the wallet."));
    }

    const candidateLoadResult = await reloadWalletWithEsplora(mnemonic, targetEndpoint);
    if (candidateLoadResult.isErr()) {
      const rollbackResult = await reloadWalletWithEsplora(mnemonic, previousEndpoint);
      if (rollbackResult.isErr()) {
        useWalletStore.getState().setWalletUnloaded();
        return err(
          new Error(
            `Failed to load the wallet with the new Esplora endpoint and failed to restore the previous configuration: ${rollbackResult.error.message}`,
          ),
        );
      }
      return err(
        new Error(
          `Failed to load the wallet with the new Esplora endpoint: ${candidateLoadResult.error.message}`,
        ),
      );
    }
  }

  const nativeResult = await persistNativeEsploraOverride(targetOverride);
  if (nativeResult.isErr()) {
    await persistNativeEsploraOverride(previousOverride);
    if (wasWalletLoaded && mnemonic) {
      const rollbackResult = await reloadWalletWithEsplora(mnemonic, previousEndpoint);
      if (rollbackResult.isErr()) {
        useWalletStore.getState().setWalletUnloaded();
        return err(
          new Error(
            `Failed to save the Esplora endpoint for background operations and failed to restore the previous wallet configuration: ${rollbackResult.error.message}`,
          ),
        );
      }
    }
    return err(
      new Error(
        `Failed to save the Esplora endpoint for background operations: ${nativeResult.error.message}`,
      ),
    );
  }

  useEsploraStore.getState().setEndpointOverride(targetOverride);
  return ok({ endpoint: targetEndpoint, isDefault: targetOverride === null });
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

  return loadWalletWithMnemonic(mnemonic);
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
    useWalletStore.getState().setWalletLoaded();
    return ok(true);
  }

  const loadResult = await loadWalletFromStorage();
  if (loadResult.isOk() && loadResult.value) {
    useWalletStore.getState().setWalletLoaded();
  }
  return loadResult;
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

export const syncPendingRounds = async (): Promise<Result<PendingRoundStatus[], Error>> => {
  return ResultAsync.fromPromise(syncPendingRoundsNitro(), (e) => e as Error);
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

export const refreshVtxosDelegated = async (
  vtxoIds: string[],
): Promise<Result<DelegatedRoundState | undefined, Error>> => {
  return ResultAsync.fromPromise(refreshVtxosDelegatedNitro(vtxoIds), (e) => e as Error);
};

export const estimateRefreshFee = async (
  vtxoIds: string[],
): Promise<Result<BarkFeeEstimate, Error>> => {
  return ResultAsync.fromPromise(estimateRefreshFeeNitro(vtxoIds), (e) => e as Error);
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

export const decodeVtxoHex = async (vtxoHex: string): Promise<Result<BarkVtxo, Error>> => {
  return ResultAsync.fromPromise(decodeVtxoHexNitro(vtxoHex), (e) => e as Error);
};

export const importVtxo = async (vtxoHex: string): Promise<Result<BarkVtxo, Error>> => {
  return ResultAsync.fromPromise(importVtxoNitro(vtxoHex), (e) => e as Error);
};

export const dropVtxo = async (vtxoId: string): Promise<Result<void, Error>> => {
  return ResultAsync.fromPromise(dangerousDropVtxoNitro(vtxoId), (e) => e as Error);
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
  const mnemonicResetResult = await clearMnemonic();
  if (mnemonicResetResult.isErr()) {
    log.w("Failed to clear stale keychain", [mnemonicResetResult.error]);
    return err(mnemonicResetResult.error);
  }

  if (shouldUseUnifiedPush()) {
    const nativeResetResult = await ResultAsync.fromPromise(
      clearNativeMnemonic(),
      (e) => e as Error,
    );
    if (nativeResetResult.isErr()) {
      log.w("Failed to clear stale native mnemonic", [nativeResetResult.error]);
      return err(nativeResetResult.error);
    }
  }

  const nativeEsploraResetResult = await ResultAsync.fromPromise(
    clearNativeEsploraEndpoint(),
    (e) => e as Error,
  );
  if (nativeEsploraResetResult.isErr()) {
    log.w("Failed to clear native Esplora endpoint", [nativeEsploraResetResult.error]);
    return err(nativeEsploraResetResult.error);
  }
  useEsploraStore.getState().reset();

  const tokenResetResult = await resetServerAuthToken();
  if (tokenResetResult.isErr()) {
    log.w("Failed to clear server auth token", [tokenResetResult.error]);
    return err(tokenResetResult.error);
  }

  log.i("Cleared stale keychain mnemonic after reinstall detection");
  return ok(undefined);
};
