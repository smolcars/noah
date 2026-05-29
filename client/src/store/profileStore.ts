import { create } from "zustand";
import { persist, createJSONStorage, StateStorage } from "zustand/middleware";
import { mmkv } from "~/lib/mmkv";
import logger from "~/lib/log";

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
  setDisplayName: (displayName: string) => void;
}

export const useProfileStore = create<ProfileState>()(
  persist(
    (set) => ({
      displayName: "",
      setDisplayName: (displayName) => set({ displayName }),
    }),
    {
      name: "profile-storage",
      storage: createJSONStorage(() => zustandStorage),
    },
  ),
);
