import { useMutation, useQuery } from "@tanstack/react-query";
import { queryClient } from "~/queryClient";
import { useAlert } from "~/contexts/AlertProvider";
import { useWalletStore } from "~/store/walletStore";
import { getBlockHeight } from "~/hooks/useMarketData";
import {
  allClaimableAtHeight,
  claimExits,
  getExitStatus,
  getExitVtxos,
  hasPendingExits,
  listClaimable,
  pendingExitTotal,
  progressExits,
  startExitForEntireWallet,
  startExitForVtxos,
  syncExit,
  type ExitClaimResult,
} from "~/lib/exitApi";
import { getVtxos } from "~/lib/walletApi";
import type { Result } from "neverthrow";
import type {
  BarkVtxo,
  ExitProgressStatusResult,
  ExitStatusResult,
  ExitVtxoResult,
} from "react-native-nitro-ark";
import logger from "~/lib/log";

const log = logger("useUnilateralExit");

export type ExitOverview = {
  exits: ExitVtxoResult[];
  statuses: Record<string, ExitStatusResult | undefined>;
  claimable: ExitVtxoResult[];
  spendableVtxos: BarkVtxo[];
  spendableVtxoCount: number;
  spendableVtxoTotal: number;
  hasPending: boolean;
  pendingTotal: number;
  allClaimableAtHeight?: number;
  blockHeight?: number;
};

export type ClaimExitsVariables = {
  vtxoIds: string[];
  destinationAddress: string;
  feeRateSatPerKvb?: number;
};

const invalidateExitQueries = async () => {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["exit-overview"] }),
    queryClient.invalidateQueries({ queryKey: ["balance"] }),
    queryClient.invalidateQueries({ queryKey: ["vtxos"] }),
    queryClient.invalidateQueries({ queryKey: ["getBlockHeight"] }),
  ]);
};

const readResult = <T>(result: Result<T, Error>): T => {
  if (result.isErr()) {
    throw result.error;
  }
  return result.value;
};

export function useExitOverview() {
  const { isInitialized } = useWalletStore();

  return useQuery({
    queryKey: ["exit-overview"],
    queryFn: async (): Promise<ExitOverview> => {
      log.d("Loading exit overview");
      readResult(await syncExit());

      const [
        exitsResult,
        claimableResult,
        hasPendingResult,
        pendingTotalResult,
        allClaimableAtHeightResult,
        spendableVtxosResult,
        blockHeightResult,
      ] = await Promise.all([
        getExitVtxos(),
        listClaimable(),
        hasPendingExits(),
        pendingExitTotal(),
        allClaimableAtHeight(),
        getVtxos(),
        getBlockHeight(),
      ]);

      const exits = readResult(exitsResult);
      const spendableVtxos = readResult(spendableVtxosResult).filter(
        (vtxo) => vtxo.state === "Spendable",
      );
      const statusResults = await Promise.all(
        exits.map(async (exit) => ({
          vtxoId: exit.vtxo_id,
          result: await getExitStatus(exit.vtxo_id, true, false),
        })),
      );

      const statuses = statusResults.reduce<Record<string, ExitStatusResult | undefined>>(
        (acc, item) => {
          acc[item.vtxoId] = readResult(item.result);
          return acc;
        },
        {},
      );

      const overview = {
        exits,
        statuses,
        claimable: readResult(claimableResult),
        spendableVtxos,
        spendableVtxoCount: spendableVtxos.length,
        spendableVtxoTotal: spendableVtxos.reduce((total, vtxo) => total + vtxo.amount, 0),
        hasPending: readResult(hasPendingResult),
        pendingTotal: readResult(pendingTotalResult),
        allClaimableAtHeight: readResult(allClaimableAtHeightResult),
        blockHeight: readResult(blockHeightResult),
      };

      log.d("Loaded exit overview", [
        {
          exit_count: overview.exits.length,
          claimable_count: overview.claimable.length,
          spendable_vtxo_count: overview.spendableVtxoCount,
          spendable_vtxo_total_sat: overview.spendableVtxoTotal,
          has_pending: overview.hasPending,
          pending_total_sat: overview.pendingTotal,
          all_claimable_at_height: overview.allClaimableAtHeight,
          block_height: overview.blockHeight,
        },
      ]);

      return overview;
    },
    enabled: isInitialized,
    retry: false,
    refetchInterval: (query) => (query.state.data?.hasPending ? 60_000 : false),
  });
}

export function useStartWalletExit() {
  const { showAlert } = useAlert();

  return useMutation<void, Error>({
    mutationFn: async () => {
      log.i("User requested wallet exit start");
      readResult(await startExitForEntireWallet());
    },
    onSuccess: async () => {
      await invalidateExitQueries();
      showAlert({
        title: "Exit Started",
        description: "Your wallet exits have been registered. Progress them until claimable.",
      });
    },
    onError: (error) => {
      log.e("Wallet exit start mutation failed", [error]);
      showAlert({ title: "Failed to Start Exit", description: error.message });
    },
  });
}

export function useStartVtxoExit() {
  const { showAlert } = useAlert();

  return useMutation<void, Error, string[]>({
    mutationFn: async (vtxoIds) => {
      log.i("User requested selected VTXO exit start", [{ vtxo_ids: vtxoIds }]);
      readResult(await startExitForVtxos(vtxoIds));
    },
    onSuccess: async () => {
      await invalidateExitQueries();
      showAlert({
        title: "Exit Started",
        description: "The selected VTXOs have been registered for emergency exit.",
      });
    },
    onError: (error) => {
      log.e("Selected VTXO exit start mutation failed", [error]);
      showAlert({ title: "Failed to Start Exit", description: error.message });
    },
  });
}

export function useProgressExits() {
  const { showAlert } = useAlert();

  return useMutation<ExitProgressStatusResult[], Error, number | undefined>({
    mutationFn: async (feeRateSatPerKvb) => {
      log.i("User requested exit progress", [{ fee_rate_sat_per_kvb: feeRateSatPerKvb }]);
      return readResult(await progressExits(feeRateSatPerKvb));
    },
    onSuccess: async () => {
      await invalidateExitQueries();
      showAlert({
        title: "Exit Progressed",
        description: "Exit status has been refreshed. Some transactions may have been broadcast.",
      });
    },
    onError: (error) => {
      log.e("Exit progress mutation failed", [error]);
      showAlert({ title: "Failed to Progress Exit", description: error.message });
    },
  });
}

export function useSyncExits() {
  const { showAlert } = useAlert();

  return useMutation<void, Error>({
    mutationFn: async () => {
      log.i("User requested exit sync");
      readResult(await syncExit());
    },
    onSuccess: async () => {
      await invalidateExitQueries();
    },
    onError: (error) => {
      log.e("Exit sync mutation failed", [error]);
      showAlert({ title: "Failed to Sync Exits", description: error.message });
    },
  });
}

export function useClaimExits() {
  const { showAlert } = useAlert();

  return useMutation<ExitClaimResult, Error, ClaimExitsVariables>({
    mutationFn: async (variables) => {
      log.i("User requested exit claim", [{ vtxo_ids: variables.vtxoIds }]);
      return readResult(await claimExits(variables));
    },
    onSuccess: async (result) => {
      await invalidateExitQueries();
      showAlert({
        title: "Claim Broadcasted",
        description: `Claim transaction broadcasted: ${result.txid}`,
      });
    },
    onError: (error) => {
      log.e("Exit claim mutation failed", [error]);
      showAlert({ title: "Failed to Claim Exits", description: error.message });
    },
  });
}
