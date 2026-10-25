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
  handledAutoBoardFundingTxids: string[];
  autoBoardSuccessBanner: {
    netBoardAmountSat: number;
    createdAt: number;
  } | null;
  setAutoBoardingEnabled: (enabled: boolean) => void;
  markAutoBoardFundingHandled: (txids: string[]) => void;
  unmarkAutoBoardFundingHandled: (txids: string[]) => void;
  setAutoBoardSuccessBanner: (netBoardAmountSat: number) => void;
  clearAutoBoardSuccessBanner: () => void;
  reset: () => void;
}

type PersistedTransactionState = Pick<
  TransactionState,
  "isAutoBoardingEnabled" | "handledAutoBoardFundingTxids"
>;

export const useTransactionStore = create<TransactionState>()(
  persist(
    (set) => ({
      isAutoBoardingEnabled: true,
      handledAutoBoardFundingTxids: [],
      autoBoardSuccessBanner: null,
      setAutoBoardingEnabled: (enabled: boolean) =>
        set((state) => ({
          isAutoBoardingEnabled: enabled,
          handledAutoBoardFundingTxids:
            enabled && !state.isAutoBoardingEnabled ? [] : state.handledAutoBoardFundingTxids,
        })),
      markAutoBoardFundingHandled: (txids) =>
        set((state) => ({
          handledAutoBoardFundingTxids: [
            ...new Set([...state.handledAutoBoardFundingTxids, ...txids]),
          ],
        })),
      unmarkAutoBoardFundingHandled: (txids) =>
        set((state) => ({
          handledAutoBoardFundingTxids: state.handledAutoBoardFundingTxids.filter(
            (txid) => !txids.includes(txid),
          ),
        })),
      setAutoBoardSuccessBanner: (netBoardAmountSat: number) =>
        set({ autoBoardSuccessBanner: { netBoardAmountSat, createdAt: Date.now() } }),
      clearAutoBoardSuccessBanner: () => set({ autoBoardSuccessBanner: null }),
      reset: () =>
        set({
          isAutoBoardingEnabled: true,
          handledAutoBoardFundingTxids: [],
          autoBoardSuccessBanner: null,
        }),
    }),
    {
      name: "transaction-storage",
      storage: createJSONStorage(() => zustandStorage),
      version: 2,
      partialize: (state): PersistedTransactionState => ({
        isAutoBoardingEnabled: state.isAutoBoardingEnabled,
        handledAutoBoardFundingTxids: state.handledAutoBoardFundingTxids,
      }),
      migrate: (persistedState, version) => {
        const state = persistedState as Partial<PersistedTransactionState>;

        if (version < 2) {
          // The lifetime flag cannot identify deposits; let eligible stuck funds prompt once.
          return {
            isAutoBoardingEnabled: state.isAutoBoardingEnabled ?? true,
            handledAutoBoardFundingTxids: [],
          };
        }

        return state;
      },
    },
  ),
);
