import { create } from "zustand";
import { persist, createJSONStorage, StateStorage } from "zustand/middleware";
import { mmkv } from "~/lib/mmkv";
import logger from "~/lib/log";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { isFiatCurrencyCode } from "~/lib/fiatCurrency";

const log = logger("profileStore");

const zustandStorage: StateStorage = {
  setItem: (name: string, value: string) => {
    try {
      return mmkv.set(name, value);
    } catch (error) {
      log.w("Profile storage setItem failed:", [error]);
      return;
    }
  },
  getItem: (name: string) => {
    try {
      const value = mmkv.getString(name);
      return value ?? null;
    } catch (error) {
      log.w("Profile storage getItem failed:", [error]);
      return null;
    }
  },
  removeItem: (name: string) => {
    try {
      return mmkv.remove(name);
    } catch (error) {
      log.w("Profile storage removeItem failed:", [error]);
      return;
    }
  },
};

interface ProfileState {
  displayName: string;
  preferredCurrency: FiatCurrencyCode;
  setDisplayName: (displayName: string) => void;
  setPreferredCurrency: (preferredCurrency: FiatCurrencyCode) => void;
}

export const useProfileStore = create<ProfileState>()(
  persist(
    (set) => ({
      displayName: "",
      preferredCurrency: "USD",
      setDisplayName: (displayName) => set({ displayName }),
      setPreferredCurrency: (preferredCurrency) => set({ preferredCurrency }),
    }),
    {
      name: "profile-storage",
      storage: createJSONStorage(() => zustandStorage),
      merge: (persistedState, currentState) => {
        const state = persistedState as Partial<ProfileState> | null;
        return {
          ...currentState,
          ...state,
          preferredCurrency: isFiatCurrencyCode(state?.preferredCurrency)
            ? state.preferredCurrency
            : "USD",
        };
      },
    },
  ),
);
