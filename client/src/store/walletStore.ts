import { create } from "zustand";
import { persist, createJSONStorage, StateStorage } from "zustand/middleware";
import { mmkv } from "~/lib/mmkv";
import logger from "~/lib/log";

const log = logger("walletStore");

const zustandStorage: StateStorage = {
  setItem: (name: string, value: string) => {
    try {
      return mmkv.set(name, value);
    } catch (error) {
      // Silently fail to prevent error loops and crashes
      // Only log in development
      log.e("Wallet storage setItem failed:", [error]);
      return;
    }
  },
  getItem: (name: string) => {
    try {
      const value = mmkv.getString(name);
      return value ?? null;
    } catch (error) {
      // Silently fail and return null
      log.e("Wallet storage getItem failed:", [error]);
      return null;
    }
  },
  removeItem: (name: string) => {
    try {
      return mmkv.remove(name);
    } catch (error) {
      // Silently fail
      log.e("Wallet storage removeItem failed:", [error]);
      return;
    }
  },
};

export type RestoreProgress = {
  step: string;
  progress: number;
};

interface WalletState {
  isInitialized: boolean;
  isWalletLoaded: boolean;
  walletError: boolean;
  staticVtxoPubkey: string | null;
  restoreProgress: RestoreProgress | null;
  isBiometricsEnabled: boolean;
  isDebugModeEnabled: boolean;
  /** Flag indicating if the wallet is suspended (all operations disabled) */
  isWalletSuspended: boolean;
  /** Flag indicating if a background push notification job is currently running */
  isBackgroundJobRunning: boolean;
  /** Ephemeral status for Android WorkManager and native push wallet jobs */
  isNativeBackgroundJobRunning: boolean;
  /** Timestamp (Date.now()) when the current background job started, used to detect stale flags */
  backgroundJobStartTime: number | null;
  finishOnboarding: () => void;
  setWalletLoaded: () => void;
  setWalletUnloaded: () => void;
  setWalletError: (error: boolean) => void;
  setStaticVtxoPubkey: (pubkey: string) => void;
  setRestoreProgress: (progress: RestoreProgress | null) => void;
  setBiometricsEnabled: (enabled: boolean) => void;
  setDebugModeEnabled: (enabled: boolean) => void;
  setWalletSuspended: (suspended: boolean) => void;
  setBackgroundJobRunning: (running: boolean) => void;
  setNativeBackgroundJobRunning: (running: boolean) => void;
  clearStaleBackgroundJobFlag: () => void;
  reset: () => void;
}

const initialState = {
  isInitialized: false,
  isWalletLoaded: false,
  walletError: false,
  staticVtxoPubkey: null,
  restoreProgress: null,
  isBiometricsEnabled: false,
  isDebugModeEnabled: false,
  isWalletSuspended: false,
  isBackgroundJobRunning: false,
  isNativeBackgroundJobRunning: false,
  backgroundJobStartTime: null,
};

export const useWalletStore = create<WalletState>()(
  persist(
    (set) => ({
      ...initialState,
      finishOnboarding: () => set({ isInitialized: true, isWalletLoaded: true }),
      setWalletLoaded: () => set({ isWalletLoaded: true, walletError: false }),
      setWalletUnloaded: () => set({ isWalletLoaded: false }),
      setWalletError: (error) => set({ walletError: error }),
      setStaticVtxoPubkey: (pubkey) => set({ staticVtxoPubkey: pubkey }),
      setRestoreProgress: (progress) => set({ restoreProgress: progress }),
      setBiometricsEnabled: (enabled) => set({ isBiometricsEnabled: enabled }),
      setDebugModeEnabled: (enabled) => set({ isDebugModeEnabled: enabled }),
      setWalletSuspended: (suspended) => set({ isWalletSuspended: suspended }),
      /**
       * Sets the background job running flag and records the start time.
       *
       * Called from pushNotifications.ts:
       * - Set to true when background task starts
       * - Set to false in finally block when task completes
       *
       * The timestamp allows us to detect stale flags (e.g., if the task crashed
       * and the finally block never executed).
       */
      setBackgroundJobRunning: (running) =>
        set({
          isBackgroundJobRunning: running,
          backgroundJobStartTime: running ? Date.now() : null,
        }),
      setNativeBackgroundJobRunning: (running) => set({ isNativeBackgroundJobRunning: running }),
      /**
       * Clears the background job flag if it has been set for too long (>60s).
       *
       * This prevents the app from getting stuck waiting for a background job that:
       * - Crashed before completing
       * - Was killed by the OS (iOS/Android ~30s timeout)
       * - Had an error that prevented the finally block from executing
       *
       * Called automatically:
       * - Before loading wallet (useBackgroundJobCoordination hook)
       * - When app comes to foreground (AppState listener)
       *
       * Why 60 seconds?
       * - Background tasks timeout after ~30s on iOS/Android
       * - Real jobs complete within 10-20s
       * - 60s buffer catches stale flags while avoiding false positives
       */
      clearStaleBackgroundJobFlag: () =>
        set((state) => {
          const STALE_TIMEOUT = 60000; // 60 seconds
          if (
            state.isBackgroundJobRunning &&
            state.backgroundJobStartTime &&
            Date.now() - state.backgroundJobStartTime > STALE_TIMEOUT
          ) {
            log.w("Clearing stale background job flag", [
              `Age: ${Date.now() - state.backgroundJobStartTime}ms`,
            ]);
            return {
              isBackgroundJobRunning: false,
              backgroundJobStartTime: null,
            };
          }
          return state;
        }),
      reset: () => set(initialState),
    }),
    {
      name: "wallet-storage",
      storage: createJSONStorage(() => zustandStorage),
      partialize: (state) => ({
        isInitialized: state.isInitialized,
        isWalletLoaded: state.isWalletLoaded,
        staticVtxoPubkey: state.staticVtxoPubkey,
        isBiometricsEnabled: state.isBiometricsEnabled,
        isDebugModeEnabled: state.isDebugModeEnabled,
        isWalletSuspended: state.isWalletSuspended,
      }),
    },
  ),
);
