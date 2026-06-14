import { useWalletStore } from "~/store/walletStore";
import { onchainSync, sync } from "~/lib/walletApi";
import logger from "~/lib/log";
import { tryClaimAllLightningReceives } from "./paymentsApi";
import { invalidateWalletDerivedQueries } from "~/lib/queryInvalidation";

const log = logger("sync");

export const syncWallet = async () => {
  const { isInitialized, isWalletLoaded } = useWalletStore.getState();

  if (!isInitialized || !isWalletLoaded) {
    return;
  }

  log.i("syncWallet");

  const results = await Promise.allSettled([
    sync(),
    onchainSync(),
    tryClaimAllLightningReceives(false),
  ]);

  results.forEach((result) => {
    if (result.status === "rejected") {
      log.e("background sync failed:", [result.reason]);
    }
  });

  await invalidateWalletDerivedQueries();
};
