import type { OnchainSendSource } from "~/lib/paymentsApi";

export const getMaxSendBalanceSat = (
  source: OnchainSendSource | null,
  onchainWalletBalance: number,
  offchainWalletBalance: number,
): number => {
  if (source === "onchain") {
    return onchainWalletBalance;
  }

  if (source === "offchain") {
    return offchainWalletBalance;
  }

  return Math.max(onchainWalletBalance, offchainWalletBalance);
};
