import { queryClient } from "~/queryClient";
import { useWalletStore } from "~/store/walletStore";
import { onchainSync, sync } from "~/lib/walletApi";
import logger from "~/lib/log";
import { tryClaimAllLightningReceives } from "./paymentsApi";

const log = logger("sync");

export const syncWallet = async () => {
  const { isInitialized, isWalletLoaded } = useWalletStore.getState();

  if (!isInitialized || !isWalletLoaded) {
    return;
  }

  log.i("syncWallet");

  const tasks = [
    { label: "Offchain wallet sync", promise: sync() },
    { label: "Onchain wallet sync", promise: onchainSync() },
    { label: "Lightning receive claim", promise: tryClaimAllLightningReceives(false) },
  ];
  const results = await Promise.allSettled(tasks.map((task) => task.promise));

  results.forEach((result, index) => {
    const label = tasks[index]?.label ?? "Background sync";
    if (result.status === "rejected") {
      log.e(`${label} failed`, [result.reason]);
      return;
    }

    if (result.value.isErr()) {
      log.e(`${label} failed`, [result.value.error]);
    }
  });

  await queryClient.invalidateQueries({ queryKey: ["balance"] });
  await queryClient.invalidateQueries({ queryKey: ["transactions"] });
};
