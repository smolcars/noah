import { describe, expect, test } from "bun:test";

import { calculateBalances, groupBalances } from "../../src/lib/balanceUtils";

const balance = {
  onchain: { confirmed: 210_000, immature: 1_000, trusted_pending: 2_000, untrusted_pending: 30_000 },
  offchain: {
    spendable: 842_310,
    needs_refresh: 61_200,
    pending: 210_007,
    total: 1_113_517,
    pending_arkoor_send: 1,
    pending_lightning_send: 15_000,
    claimable_lightning_receive: 25_000,
    pending_in_round: 120_000,
    pending_board: 50_000,
    pending_offboard: 2,
    pending_exit: 4,
  },
};

describe("balance groups", () => {
  test("fold every Bark field into a group that adds up to the total", () => {
    const groups = groupBalances(balance);
    expect(groups).toEqual({
      spendable: 842_310,
      arriving: 108_000,
      refreshing: 120_000,
      sending: 15_003,
      exiting: 4,
      onchain: 210_000,
      needsRefresh: 61_200,
    });
    const sum = Object.values(groups).reduce((total, amount) => total + amount, 0);
    expect(sum).toBe(calculateBalances(balance).totalBalance);
  });

  test("uses Bark's own offchain total and pending sums", () => {
    expect(calculateBalances(balance)).toEqual({
      totalBalance: 1_356_517,
      onchainBalance: 243_000,
      offchainBalance: 1_113_517,
      pendingBalance: 243_007,
    });
  });
});
