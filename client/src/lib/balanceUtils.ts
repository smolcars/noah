import type { OnchainBalanceResult, OffchainBalanceResult } from "react-native-nitro-ark";

export interface BalanceData {
  totalBalance: number;
  onchainBalance: number;
  offchainBalance: number;
  pendingBalance: number;
}

export interface BalanceResults {
  onchain: OnchainBalanceResult;
  offchain: OffchainBalanceResult;
}

/**
 * Calculate onchain balance from balance result
 */
export function calculateOnchainBalance(onchain: OnchainBalanceResult): number {
  return (
    (onchain.confirmed ?? 0) +
    (onchain.immature ?? 0) +
    (onchain.trusted_pending ?? 0) +
    (onchain.untrusted_pending ?? 0)
  );
}

/**
 * Calculate offchain balance from balance result
 */
export function calculateOffchainBalance(offchain: OffchainBalanceResult): number {
  return offchain.total;
}

/**
 * Calculate total pending balance from balance results
 */
export function calculatePendingBalance(
  onchain: OnchainBalanceResult,
  offchain: OffchainBalanceResult,
): number {
  return onchain.trusted_pending + onchain.untrusted_pending + onchain.immature + offchain.pending;
}

/**
 * User-facing balance groups. They add up to the total balance.
 */
export interface BalanceGroups {
  spendable: number;
  arriving: number;
  refreshing: number;
  sending: number;
  exiting: number;
  onchain: number;
  needsRefresh: number;
}

export function groupBalances({ onchain, offchain }: BalanceResults): BalanceGroups {
  return {
    spendable: offchain.spendable,
    arriving:
      offchain.pending_board +
      offchain.claimable_lightning_receive +
      onchain.trusted_pending +
      onchain.untrusted_pending +
      onchain.immature,
    refreshing: offchain.pending_in_round,
    sending:
      offchain.pending_arkoor_send + offchain.pending_lightning_send + offchain.pending_offboard,
    exiting: offchain.pending_exit,
    onchain: onchain.confirmed,
    needsRefresh: offchain.needs_refresh,
  };
}

/**
 * Calculate all balances from balance results
 */
export function calculateBalances(balance: BalanceResults): BalanceData {
  const onchainBalance = calculateOnchainBalance(balance.onchain);
  const offchainBalance = calculateOffchainBalance(balance.offchain);
  const pendingBalance = calculatePendingBalance(balance.onchain, balance.offchain);
  const totalBalance = onchainBalance + offchainBalance;

  return {
    totalBalance,
    onchainBalance,
    offchainBalance,
    pendingBalance,
  };
}
