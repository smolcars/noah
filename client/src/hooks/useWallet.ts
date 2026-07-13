import { useMutation, useQuery } from "@tanstack/react-query";
import { useAlert } from "~/contexts/AlertProvider";
import { useServerStore } from "../store/serverStore";
import { useWalletStore } from "../store/walletStore";
import {
  createWallet as createWalletAction,
  fetchOnchainBalance,
  fetchOffchainBalance,
  deleteWallet as deleteWalletAction,
  loadWalletIfNeeded as loadWalletAction,
  sync as syncAction,
  onchainSync as onchainSyncAction,
  maintenanceWithOnchainDelegated,
  getVtxos,
  getExpiringVtxos,
  closeWalletIfLoaded,
  sync,
  getArkInfo,
  syncPendingRounds,
  refreshVtxosDelegated,
  estimateRefreshFee,
} from "../lib/walletApi";
import { getAutoBoardThreshold } from "~/lib/autoBoarding";
import { restoreWallet as restoreWalletAction } from "../lib/backupService";
import { deregister } from "../lib/api";
import { queryClient } from "~/queryClient";
import { useTransactionStore } from "../store/transactionStore";
import { useBackupStore } from "~/store/backupStore";
import { useEsploraStore } from "~/store/esploraStore";
import { ResultAsync } from "neverthrow";
import logger from "~/lib/log";

const log = logger("useWallet");

export function useCreateWallet() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const result = await createWalletAction();
      if (result.isErr()) {
        throw result.error;
      }
    },
    onError: async (error: Error) => {
      await deleteWalletAction();
      showAlert({ title: "Creation Failed", description: error.message });
    },
  });
}

export function useLoadWallet() {
  const { setWalletLoaded, setWalletError } = useWalletStore();

  return useMutation({
    mutationFn: async () => {
      const result = await loadWalletAction();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onSuccess: (walletExists) => {
      if (walletExists) {
        setWalletLoaded();
      }
    },
    onError: (error: Error) => {
      log.e("Error syncing wallet", [error]);
      setWalletError(true);
    },
  });
}

export function useWalletSync() {
  return useMutation({
    mutationFn: async () => {
      const results = await Promise.allSettled([sync(), onchainSyncAction()]);

      for (const result of results) {
        if (result.status === "rejected") {
          throw result.reason;
        }

        if (result.value.isErr()) {
          throw result.value.error;
        }
      }

      return;
    },
    retry: false,
  });
}

export function useBalance() {
  const { isInitialized } = useWalletStore();

  return useQuery({
    queryKey: ["balance"],
    queryFn: async () => {
      const [onchainResult, offchainResult] = await Promise.all([
        fetchOnchainBalance(),
        fetchOffchainBalance(),
      ]);

      if (onchainResult.isErr()) {
        throw onchainResult.error;
      }
      if (offchainResult.isErr()) {
        throw offchainResult.error;
      }

      return { onchain: onchainResult.value, offchain: offchainResult.value };
    },
    enabled: isInitialized,
    retry: false,
  });
}

export function useAutoBoardThreshold(enabled = true) {
  return useQuery({
    queryKey: ["auto-board-threshold"],
    queryFn: async () => {
      const result = await getArkInfo();
      if (result.isErr()) {
        throw result.error;
      }
      return getAutoBoardThreshold(result.value);
    },
    enabled,
    retry: false,
  });
}

export function useArkInfo(enabled = true) {
  return useQuery({
    queryKey: ["ark-info"],
    queryFn: async () => {
      const result = await getArkInfo();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    enabled,
    retry: false,
  });
}

export function usePendingRounds(refetchIntervalMs: number | false = false) {
  const { isInitialized, isWalletSuspended, isBackgroundJobRunning } = useWalletStore();

  return useQuery({
    queryKey: ["pending-rounds"],
    queryFn: async () => {
      const result = await syncPendingRounds();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    enabled: isInitialized && !isWalletSuspended && !isBackgroundJobRunning,
    refetchInterval: refetchIntervalMs,
    retry: false,
  });
}

export function useGetVtxos() {
  return useQuery({
    queryKey: ["vtxos"],
    queryFn: async () => {
      const result = await getVtxos();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    retry: false,
  });
}

export function useGetExpiringVtxos() {
  return useQuery({
    queryKey: ["expiring-vtxos"],
    queryFn: async () => {
      const result = await getExpiringVtxos();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    retry: false,
  });
}

export function useRefreshExpiringVtxos() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const result = await maintenanceWithOnchainDelegated();
      if (result.isErr()) {
        throw result.error;
      }
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["vtxos"] }),
        queryClient.invalidateQueries({ queryKey: ["expiring-vtxos"] }),
        queryClient.invalidateQueries({ queryKey: ["balance"] }),
        queryClient.invalidateQueries({ queryKey: ["pending-rounds"] }),
      ]);
      showAlert({
        title: "Refresh scheduled",
        description: "A delegated refresh has been scheduled for eligible VTXOs.",
      });
    },
    onError: (error: Error) => {
      log.e("Failed to refresh expiring VTXOs", [error]);
      showAlert({ title: "Failed to refresh VTXO", description: error.message });
    },
  });
}

export function useEstimateRefreshFee() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async (vtxoIds: string[]) => {
      if (vtxoIds.length === 0) {
        throw new Error("Select at least one VTXO.");
      }

      const result = await estimateRefreshFee(vtxoIds);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onError: (error: Error) => {
      log.e("Failed to estimate refresh fee", [error]);
      showAlert({ title: "Failed to estimate refresh fee", description: error.message });
    },
  });
}

export function useRefreshSelectedVtxos() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async (vtxoIds: string[]) => {
      if (vtxoIds.length === 0) {
        throw new Error("Select at least one VTXO.");
      }

      const result = await refreshVtxosDelegated(vtxoIds);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onSuccess: async (roundState) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["vtxos"] }),
        queryClient.invalidateQueries({ queryKey: ["expiring-vtxos"] }),
        queryClient.invalidateQueries({ queryKey: ["balance"] }),
        queryClient.invalidateQueries({ queryKey: ["pending-rounds"] }),
      ]);
      if (!roundState) {
        showAlert({
          title: "No refresh needed",
          description: "The selected VTXOs do not need to be refreshed yet.",
        });
        return;
      }

      showAlert({
        title: "Refresh scheduled",
        description: "The selected VTXOs have been scheduled for refresh.",
      });
    },
    onError: (error: Error) => {
      log.e("Failed to refresh selected VTXOs", [error]);
      showAlert({ title: "Failed to refresh VTXOs", description: error.message });
    },
  });
}

export function useCloseWallet() {
  const { setWalletUnloaded } = useWalletStore();
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: closeWalletIfLoaded,
    onError: (error: Error) => {
      showAlert({ title: "Failed to close wallet", description: error.message });
    },
    onSuccess: () => {
      setWalletUnloaded();
    },
  });
}

export const useBalanceSync = () => {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const results = await Promise.allSettled([syncAction(), onchainSyncAction()]);
      results.forEach((result) => {
        if (result.status === "rejected") {
          throw result.reason;
        }
      });
    },
    onError: (error: Error) => {
      showAlert({ title: "Failed to sync wallet balance", description: error.message });
    },
  });
};

export function useOffchainSync() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const result = await syncAction();
      if (result.isErr()) {
        throw result.error;
      }
    },
    onError: (error: Error) => {
      showAlert({ title: "Failed to sync wallet", description: error.message });
    },
  });
}

export function useOnchainSync() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const result = await onchainSyncAction();
      if (result.isErr()) {
        throw result.error;
      }
    },
    onError: (error: Error) => {
      showAlert({ title: "Failed to sync wallet", description: error.message });
    },
  });
}

export function useDeleteWallet() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      // Deregister from server first (best effort)
      await ResultAsync.fromPromise(deregister(), (error) => {
        log.w("Deregistration failed during wallet deletion:", [error]);
        return error;
      });

      // Also reset all MMKV stores
      useTransactionStore.getState().reset();
      useWalletStore.getState().reset();
      useServerStore.getState().resetRegistration();
      useBackupStore.getState().reset();
      useEsploraStore.getState().reset();

      // Clear query cache
      queryClient.clear();

      // Now delete the wallet files
      const result = await deleteWalletAction();
      if (result.isErr()) {
        throw result.error;
      }
    },
    onError: (error: Error) => {
      showAlert({ title: "Deletion Failed", description: error.message });
    },
  });
}

export function useRestoreWallet() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async ({ mnemonic }: { mnemonic: string }) => {
      const result = await restoreWalletAction(mnemonic);
      if (result.isErr()) {
        throw result.error;
      }
    },
    onError: (error: Error) => {
      showAlert({ title: "Restore Failed", description: error.message });
    },
  });
}

export function useSuspendWallet() {
  const { showAlert } = useAlert();
  const { setWalletSuspended, setWalletLoaded } = useWalletStore();

  return useMutation({
    mutationFn: async (suspend: boolean) => {
      if (suspend) {
        const closeResult = await closeWalletIfLoaded();
        if (closeResult.isErr()) {
          throw closeResult.error;
        }
        setWalletSuspended(true);
      } else {
        setWalletSuspended(false);
        const loadResult = await loadWalletAction();
        if (loadResult.isErr()) {
          throw loadResult.error;
        }
        if (loadResult.value) {
          setWalletLoaded();
        }
      }
    },
    onError: (error: Error, suspend) => {
      if (suspend) {
        setWalletSuspended(false);
      }
      showAlert({
        title: suspend ? "Failed to suspend wallet" : "Failed to resume wallet",
        description: error.message,
      });
    },
  });
}
