import { describe, expect, test } from "bun:test";

import {
  getBoardingMovementAmount,
  getTransactionDisplayLabel,
  mergeBoardingWithOnchainTransactions,
  parseMovementMetadata,
} from "../../src/lib/transactionHistory";

describe("boarding transaction metadata", () => {
  test("extracts the chain transaction and fees", () => {
    expect(
      parseMovementMetadata(
        JSON.stringify({
          offboard_txid: "offboard-txid",
          onchain_fee_sat: 123,
          chain_anchor: "board-txid",
        }),
      ),
    ).toEqual({
      offboardTxid: "offboard-txid",
      onchainFeeSat: 123,
      chainAnchor: "board-txid",
    });
  });

  test("prefers the intended boarding amount", () => {
    expect(
      getBoardingMovementAmount({
        intended_balance_sat: 50_000,
        effective_balance_sat: 49_500,
        sent_to: [{ amount_sat: 49_000 }],
        received_on: [],
      }),
    ).toBe(50_000);
  });
});

describe("unified transaction history", () => {
  test("merges a board movement with its matching BDK transaction", () => {
    const board = {
      id: "movement-7",
      type: "Onchain",
      amount: 50_000,
      date: "2026-07-15T12:00:00.000Z",
      direction: "incoming",
      source: "ark",
      txid: "funding-txid",
      movementKind: "onboard",
      btcPrice: 100_000,
    };
    const matchingOnchain = {
      id: "onchain-wallet-funding-txid",
      type: "Onchain",
      amount: 50_500,
      date: "2026-07-15T12:00:00.000Z",
      direction: "outgoing",
      source: "onchain-wallet",
      txid: "funding-txid",
      txHex: "deadbeef",
      balanceChangeSat: -50_500,
      hasOnchainFee: true,
      onchainFeeSat: 500,
      hasConfirmation: false,
    };
    const unrelatedOnchain = {
      ...matchingOnchain,
      id: "onchain-wallet-other-txid",
      txid: "other-txid",
    };

    const transactions = mergeBoardingWithOnchainTransactions(
      [board],
      [matchingOnchain, unrelatedOnchain],
    );

    expect(transactions).toHaveLength(2);
    expect(transactions[0]).toMatchObject({
      id: "movement-7",
      txid: "funding-txid",
      movementKind: "onboard",
      txHex: "deadbeef",
      balanceChangeSat: -50_500,
      onchainFeeSat: 500,
      hasConfirmation: false,
    });
    expect(transactions[1]?.id).toBe("onchain-wallet-other-txid");
  });

  test("uses board and offboard labels inside the onchain category", () => {
    expect(getTransactionDisplayLabel({ type: "Onchain", movementKind: "onboard" })).toBe(
      "Board",
    );
    expect(getTransactionDisplayLabel({ type: "Onchain", movementKind: "offboard" })).toBe(
      "Offboard",
    );
  });
});
