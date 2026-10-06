import { create } from "zustand";
import { persist, createJSONStorage, StateStorage } from "zustand/middleware";
import { mmkv } from "~/lib/mmkv";
import logger from "~/lib/log";
import type { RecurringPayment } from "~/types/recurringPayment";

const log = logger("recurringPaymentStore");

const zustandStorage: StateStorage = {
  setItem: (name: string, value: string) => {
    try {
      return mmkv.set(name, value);
    } catch (error) {
      log.e("Recurring payment storage setItem failed:", [error]);
      return;
    }
  },
  getItem: (name: string) => {
    try {
      const value = mmkv.getString(name);
      return value ?? null;
    } catch (error) {
      log.e("Recurring payment storage getItem failed:", [error]);
      return null;
    }
  },
  removeItem: (name: string) => {
    try {
      return mmkv.remove(name);
    } catch (error) {
      log.e("Recurring payment storage removeItem failed:", [error]);
      return;
    }
  },
};

interface RecurringPaymentState {
  schedules: Record<string, RecurringPayment>;
  /** True when the server copy of the wake-up schedule may be out of date. */
  serverSyncPending: boolean;
  upsertSchedule: (schedule: RecurringPayment) => void;
  removeSchedule: (id: string) => void;
  setServerSyncPending: (pending: boolean) => void;
  reset: () => void;
}

export const useRecurringPaymentStore = create<RecurringPaymentState>()(
  persist(
    (set) => ({
      schedules: {},
      serverSyncPending: false,
      upsertSchedule: (schedule) =>
        set((state) => ({
          schedules: { ...state.schedules, [schedule.id]: schedule },
          serverSyncPending: true,
        })),
      removeSchedule: (id) =>
        set((state) => {
          const rest = { ...state.schedules };
          delete rest[id];
          return { schedules: rest, serverSyncPending: true };
        }),
      setServerSyncPending: (pending) => set({ serverSyncPending: pending }),
      // Used on wallet deletion. Deregistration already removes the server
      // records, so there is nothing left to sync.
      reset: () => set({ schedules: {}, serverSyncPending: false }),
    }),
    {
      name: "recurring-payment-storage",
      storage: createJSONStorage(() => zustandStorage),
    },
  ),
);

export const getRecurringPayments = (): RecurringPayment[] =>
  Object.values(useRecurringPaymentStore.getState().schedules).sort(
    (a, b) => (a.nextRunAt ?? Number.MAX_SAFE_INTEGER) - (b.nextRunAt ?? Number.MAX_SAFE_INTEGER),
  );
