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
  getVtxos,
  getExpiringVtxos,
  closeWalletIfLoaded,
  sync,
  type WalletServerAccessTokenOptions,
} from "../lib/walletApi";
import { restoreWallet as restoreWalletAction } from "../lib/backupService";
import { deregister } from "../lib/api";
import { queryClient } from "~/queryClient";
import { useTransactionStore } from "../store/transactionStore";
import { useBackupStore } from "~/store/backupStore";
import { ResultAsync } from "neverthrow";
import logger from "~/lib/log";

const log = logger("useWallet");

export function useCreateWallet() {
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async (options?: WalletServerAccessTokenOptions) => {
      const result = await createWalletAction(options);
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
      const result = await Promise.allSettled([sync(), onchainSyncAction()]);
      const isRejected = result.some((result) => result.status === "rejected");
      if (isRejected) {
        throw result.find((result) => result.status === "rejected")?.reason;
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
    mutationFn: async ({
      mnemonic,
      serverAccessToken,
    }: { mnemonic: string } & WalletServerAccessTokenOptions) => {
      const result = await restoreWalletAction(mnemonic, { serverAccessToken });
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
