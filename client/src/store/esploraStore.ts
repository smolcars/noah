import { create } from "zustand";
import { createJSONStorage, persist, type StateStorage } from "zustand/middleware";
import logger from "~/lib/log";
import { mmkv } from "~/lib/mmkv";

const log = logger("esploraStore");

const zustandStorage: StateStorage = {
  setItem: (name, value) => {
    try {
      return mmkv.set(name, value);
    } catch (error) {
      log.w("Esplora storage setItem failed:", [error]);
    }
  },
  getItem: (name) => {
    try {
      return mmkv.getString(name) ?? null;
    } catch (error) {
      log.w("Esplora storage getItem failed:", [error]);
      return null;
    }
  },
  removeItem: (name) => {
    try {
      return mmkv.remove(name);
    } catch (error) {
      log.w("Esplora storage removeItem failed:", [error]);
    }
  },
};

interface EsploraState {
  endpointOverride: string | null;
  setEndpointOverride: (endpoint: string | null) => void;
  reset: () => void;
}

export const useEsploraStore = create<EsploraState>()(
  persist(
    (set) => ({
      endpointOverride: null,
      setEndpointOverride: (endpointOverride) => set({ endpointOverride }),
      reset: () => set({ endpointOverride: null }),
    }),
    {
      name: "esplora-storage",
      storage: createJSONStorage(() => zustandStorage),
      partialize: (state) => ({ endpointOverride: state.endpointOverride }),
      merge: (persistedState, currentState) => {
        const state = persistedState as Partial<EsploraState> | null;
        return {
          ...currentState,
          endpointOverride:
            typeof state?.endpointOverride === "string" ? state.endpointOverride : null,
        };
      },
    },
  ),
);
