import { useMutation, useQuery } from "@tanstack/react-query";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { AppState } from "react-native";
import { useIsFocused } from "@react-navigation/native";
import { queryClient } from "~/queryClient";
import { useAlert } from "~/contexts/AlertProvider";
import { useWalletStore } from "~/store/walletStore";
import { getBlockHeight } from "~/hooks/useMarketData";
import {
  allClaimableAtHeight,
  cancelExit,
  claimExits,
  estimateEmergencyExitFee,
  getExitStatus,
  getExitVtxos,
  hasPendingExits,
  listClaimable,
  pendingExitTotal,
  progressExits,
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
import {
  exitEstimateKey,
  isExitReviewCurrent,
  EXIT_ESTIMATE_MAX_AGE_MS,
  type ExitEstimateInputs,
  type ExitFeeReview,
} from "~/lib/exitFeeEstimate";

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
    queryClient.invalidateQueries({ queryKey: ["exit-fee-estimate"] }),
  ]);
};

const readResult = <T>(result: Result<T, Error>): T => {
  if (result.isErr()) {
    throw result.error;
  }
  return result.value;
};

export function useExitOverview() {
  const { isInitialized, staticVtxoPubkey } = useWalletStore();

  return useQuery({
    queryKey: ["exit-overview", staticVtxoPubkey],
    queryFn: async (): Promise<ExitOverview> => {
      log.d("Loading exit overview");
      // Bark 0.7 syncExit permits progression. Reading this screen must not broadcast.

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

export function useExitFeeEstimate(inputs: ExitEstimateInputs, enabled: boolean) {
  const isFocused = useIsFocused();
  const [isForeground, setIsForeground] = useState(AppState.currentState === "active");
  const [foregroundRevision, setForegroundRevision] = useState(0);
  const [settledKey, setSettledKey] = useState("");
  const [isReviewing, setIsReviewing] = useState(false);
  const contextKey = exitEstimateKey({
    ...inputs,
    revision: `${inputs.revision}:${isFocused}:${isForeground}:${foregroundRevision}`,
  });
  const canEstimate = enabled && isFocused && isForeground && inputs.vtxoIds.length > 0;
  const currentContext = useRef({ contextKey, canEstimate });
  useLayoutEffect(() => {
    currentContext.current = { contextKey, canEstimate };
  }, [contextKey, canEstimate]);

  useEffect(() => {
    if (isFocused) setForegroundRevision((revision) => revision + 1);
  }, [isFocused]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (state) => {
      setIsForeground(state === "active");
      if (state === "active") setForegroundRevision((revision) => revision + 1);
    });
    return () => subscription.remove();
  }, []);

  useEffect(() => {
    if (!canEstimate) return;
    const timer = setTimeout(() => setSettledKey(contextKey), 350);
    return () => clearTimeout(timer);
  }, [contextKey, canEstimate]);

  const query = useQuery({
    // Switch keys immediately, before debounce: never expose the previous selection's quote.
    queryKey: ["exit-fee-estimate", contextKey],
    queryFn: async () =>
      readResult(await estimateEmergencyExitFee(inputs.vtxoIds, inputs.destinationAddress)),
    enabled: canEstimate && settledKey === contextKey,
    retry: false,
    staleTime: EXIT_ESTIMATE_MAX_AGE_MS,
    refetchInterval: EXIT_ESTIMATE_MAX_AGE_MS,
  });

  const review = async (): Promise<ExitFeeReview | undefined> => {
    if (!canEstimate || isReviewing) return;
    setIsReviewing(true);
    // Always refresh before review; the snapshot also pins the eventual action inputs.
    const result = await estimateEmergencyExitFee(
      inputs.vtxoIds,
      inputs.destinationAddress,
    ).finally(() => setIsReviewing(false));
    return {
      inputs: { ...inputs, vtxoIds: [...inputs.vtxoIds] },
      contextKey,
      estimate: result.isOk() ? result.value : undefined,
      reviewedAt: Date.now(),
    };
  };

  return {
    contextKey,
    estimate: canEstimate && settledKey === contextKey && !query.isError ? query.data : undefined,
    isLoading: canEstimate && (settledKey !== contextKey || query.isPending),
    isError: canEstimate && settledKey === contextKey && query.isError,
    retry: () => query.refetch(),
    review,
    isReviewing,
    isCurrentReview: (snapshot: ExitFeeReview) =>
      currentContext.current.canEstimate &&
      isExitReviewCurrent(snapshot, currentContext.current.contextKey) &&
      useWalletStore.getState().staticVtxoPubkey === snapshot.inputs.walletId &&
      useWalletStore.getState().isWalletLoaded &&
      !useWalletStore.getState().isWalletSuspended &&
      !useWalletStore.getState().isBackgroundJobRunning,
  };
}

export function useStartVtxoExit() {
  const { showAlert } = useAlert();

  return useMutation<void, Error, string[]>({
    mutationFn: async (vtxoIds) => {
      log.i("User requested selected VTXO exit start", [{ vtxo_ids: vtxoIds }]);
      const available = new Set(
        readResult(await getVtxos())
          .filter((vtxo) => vtxo.state === "Spendable")
          .map((vtxo) => vtxo.id),
      );
      if (vtxoIds.some((id) => !available.has(id))) {
        throw new Error("Available VTXOs changed. Refresh and review the exit again.");
      }
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

export function useCancelExit() {
  const { showAlert } = useAlert();

  return useMutation<void, Error, string>({
    mutationFn: async (vtxoId) => {
      log.i("User requested exit cancellation", [{ vtxo_id: vtxoId }]);
      readResult(await cancelExit(vtxoId));
    },
    onSuccess: async () => {
      await Promise.all([
        invalidateExitQueries(),
        queryClient.invalidateQueries({ queryKey: ["transactions"] }),
      ]);
      showAlert({
        title: "Exit Canceled",
        description: "The VTXO remains spendable and can be exited again later.",
      });
    },
    onError: async (error) => {
      log.e("Exit cancellation mutation failed", [error]);
      showAlert({ title: "Failed to Cancel Exit", description: error.message });
      await queryClient.invalidateQueries({ queryKey: ["exit-overview"] });
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
