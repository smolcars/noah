import { useEffect } from "react";
import { Platform } from "react-native";
import { updateWidgetData } from "noah-tools";
import { APP_VARIANT } from "~/config";
import logger from "~/lib/log";
import { getVtxos, fetchOnchainBalance, fetchOffchainBalance } from "~/lib/walletApi";
import { getBlockHeight } from "~/hooks/useMarketData";
import { calculateBalances, type BalanceData } from "~/lib/balanceUtils";
import { getWalletRefreshExpiryThreshold } from "~/lib/walletConfig";

const log = logger("useWidget");

const getAppGroup = (): string => {
  const isIOS = Platform.OS === "ios";
  const prefix = isIOS ? "group." : "";

  switch (APP_VARIANT) {
    case "regtest":
      return `${prefix}com.noahwallet.regtest`;
    case "signet":
      return `${prefix}com.noahwallet.signet`;
    case "mainnet":
      return `${prefix}com.noahwallet.mainnet`;
    default:
      return `${prefix}com.noahwallet.regtest`;
  }
};

export function useWidget(balanceData: BalanceData | null) {
  useEffect(() => {
    if (!balanceData) {
      return;
    }

    updateWidget(balanceData);
  }, [balanceData]);
}

export async function updateWidget(balanceData?: BalanceData): Promise<void> {
  if (Platform.OS !== "ios" && Platform.OS !== "android") {
    return;
  }

  try {
    const appGroup = getAppGroup();

    // Calculate expiry - always fetch VTXOs and block height
    const [vtxosResult, blockHeightResult] = await Promise.allSettled([
      getVtxos(),
      getBlockHeight(),
    ]);

    let closestExpiryBlocks: number | null = null;

    if (
      vtxosResult.status === "fulfilled" &&
      vtxosResult.value.isOk() &&
      blockHeightResult.status === "fulfilled" &&
      blockHeightResult.value.isOk()
    ) {
      const vtxos = vtxosResult.value.value;
      const currentHeight = blockHeightResult.value.value;

      // Find the vtxo with the closest expiry (including expired ones with negative blocks)
      // Find the vtxo with the closest expiry (including expired ones with negative blocks)
      if (vtxos.length > 0) {
        closestExpiryBlocks = Math.min(...vtxos.map((vtxo) => vtxo.expiry_height - currentHeight));
      }
    }

    // If no VTXOs found, use sentinel value -999 to signal widget to hide expiry section
    if (closestExpiryBlocks === null) {
      closestExpiryBlocks = -999;
    }

    // Calculate balances
    let totalBalance = 0;
    let onchainBalance = 0;
    let offchainBalance = 0;
    let pendingBalance = 0;

    if (balanceData) {
      // Use provided balance data (from home screen)
      totalBalance = balanceData.totalBalance;
      onchainBalance = balanceData.onchainBalance;
      offchainBalance = balanceData.offchainBalance;
      pendingBalance = balanceData.pendingBalance;
    } else {
      // Fetch balance data (from push notifications)
      const [onchainBalanceResult, offchainBalanceResult] = await Promise.allSettled([
        fetchOnchainBalance(),
        fetchOffchainBalance(),
      ]);

      if (
        onchainBalanceResult.status === "fulfilled" &&
        onchainBalanceResult.value.isOk() &&
        offchainBalanceResult.status === "fulfilled" &&
        offchainBalanceResult.value.isOk()
      ) {
        const balances = calculateBalances({
          onchain: onchainBalanceResult.value.value,
          offchain: offchainBalanceResult.value.value,
        });

        totalBalance = balances.totalBalance;
        onchainBalance = balances.onchainBalance;
        offchainBalance = balances.offchainBalance;
        pendingBalance = balances.pendingBalance;
      }
    }

    const expiryThreshold = getWalletRefreshExpiryThreshold();

    updateWidgetData(
      totalBalance,
      onchainBalance,
      offchainBalance,
      pendingBalance,
      closestExpiryBlocks,
      expiryThreshold,
      appGroup,
    );
  } catch (error) {
    log.e("Failed to update widget:", [error]);
  }
}
