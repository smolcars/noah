import { create } from "zustand";
import { persist, createJSONStorage, StateStorage } from "zustand/middleware";
import { mmkv } from "~/lib/mmkv";
import logger from "~/lib/log";

const log = logger("transactionStore");

const zustandStorage: StateStorage = {
  setItem: (name: string, value: string) => {
    try {
      return mmkv.set(name, value);
    } catch (error) {
      log.w("Transaction storage setItem failed:", [error]);
      return;
    }
  },
  getItem: (name: string) => {
    try {
      const value = mmkv.getString(name);
      return value ?? null;
    } catch (error) {
      log.w("Transaction storage getItem failed:", [error]);
      return null;
    }
  },
  removeItem: (name: string) => {
    try {
      return mmkv.remove(name);
    } catch (error) {
      log.w("Transaction storage removeItem failed:", [error]);
      return;
    }
  },
};

interface TransactionState {
  isAutoBoardingEnabled: boolean;
  hasAttemptedAutoBoarding: boolean;
  autoBoardSuccessBanner: {
    netBoardAmountSat: number;
    createdAt: number;
  } | null;
  setAutoBoardingEnabled: (enabled: boolean) => void;
  setHasAttemptedAutoBoarding: (attempted: boolean) => void;
  setAutoBoardSuccessBanner: (netBoardAmountSat: number) => void;
  clearAutoBoardSuccessBanner: () => void;
  reset: () => void;
}

type PersistedTransactionState = Pick<
  TransactionState,
  "isAutoBoardingEnabled" | "hasAttemptedAutoBoarding"
>;

export const useTransactionStore = create<TransactionState>()(
  persist(
    (set) => ({
      isAutoBoardingEnabled: true,
      hasAttemptedAutoBoarding: false,
      autoBoardSuccessBanner: null,
      setAutoBoardingEnabled: (enabled: boolean) =>
        set({
          isAutoBoardingEnabled: enabled,
          ...(enabled ? { hasAttemptedAutoBoarding: false } : {}),
        }),
      setHasAttemptedAutoBoarding: (attempted: boolean) =>
        set({ hasAttemptedAutoBoarding: attempted }),
      setAutoBoardSuccessBanner: (netBoardAmountSat: number) =>
        set({ autoBoardSuccessBanner: { netBoardAmountSat, createdAt: Date.now() } }),
      clearAutoBoardSuccessBanner: () => set({ autoBoardSuccessBanner: null }),
      reset: () =>
        set({
          isAutoBoardingEnabled: true,
          hasAttemptedAutoBoarding: false,
          autoBoardSuccessBanner: null,
        }),
    }),
    {
      name: "transaction-storage",
      storage: createJSONStorage(() => zustandStorage),
      version: 1,
      partialize: (state): PersistedTransactionState => ({
        isAutoBoardingEnabled: state.isAutoBoardingEnabled,
        hasAttemptedAutoBoarding: state.hasAttemptedAutoBoarding,
      }),
      migrate: (persistedState, version) => {
        const state = persistedState as Partial<PersistedTransactionState>;

        if (version < 1) {
          return {
            ...state,
            hasAttemptedAutoBoarding: false,
          };
        }

        return state;
      },
    },
  ),
);
