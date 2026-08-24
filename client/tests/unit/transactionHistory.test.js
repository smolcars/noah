import { describe, expect, test } from "bun:test";

import {
  getBoardingMovementAmount,
  getMovementTransactionId,
  getTransactionAccountingValues,
  getTransactionDisplayLabel,
  isCanceledTransaction,
  isInternalBoardingTransfer,
  mergeBoardingWithOnchainTransactions,
  parseMovementMetadata,
} from "../../src/lib/transactionHistory";
import {
  buildRepeatPaymentMetadataPatch,
  canRepeatPayment,
  findNewArkoorMovementId,
  getRepeatPaymentPrefill,
  resolveRepeatPaymentDetails,
  shouldUseArkDirectLightningAddressRoute,
} from "../../src/lib/repeatPayment";

describe("boarding transaction metadata", () => {
  test("extracts the chain transaction and fees", () => {
    expect(
      parseMovementMetadata(
        JSON.stringify({
          offboard_txid: "offboard-txid",
          onchain_fee_sat: 123,
          chain_anchor: "board-txid:1",
        }),
      ),
    ).toEqual({
      offboardTxid: "offboard-txid",
      onchainFeeSat: 123,
      chainAnchor: "board-txid:1",
    });
  });

  test("uses the transaction ID portion of a board anchor outpoint", () => {
    expect(
      getMovementTransactionId(
        {
          id: 7,
          input_vtxos: [],
          output_vtxos: [],
          exited_vtxos: [],
        },
        { chainAnchor: "funding-txid:1" },
        false,
      ),
    ).toBe("funding-txid");
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

describe("repeat payment metadata", () => {
  test("round trips a fiat lightning-address payment", () => {
    const details = {
      destination: "merchant@example.com",
      comment: "Order 307",
      amountMode: "FIAT",
      amountInput: "12.50",
      amountSat: 10_000,
      fiatCurrency: "USD",
    };

    const metadata = parseMovementMetadata(buildRepeatPaymentMetadataPatch(details));

    expect(metadata.repeatPayment).toEqual({
      destination: "merchant@example.com",
      comment: "Order 307",
      amountMode: "FIAT",
      amountInput: "12.50",
      fiatCurrency: "USD",
    });
    expect(
      resolveRepeatPaymentDetails({ metadata: metadata.repeatPayment, amountSat: 10_000 }),
    ).toEqual(details);
  });

  test("derives a sats repeat action for legacy lightning-address history", () => {
    expect(
      resolveRepeatPaymentDetails({
        destination: "LEGACY@EXAMPLE.COM",
        paymentMethod: "lightning-address",
        amountSat: 2_100,
      }),
    ).toEqual({
      destination: "legacy@example.com",
      comment: "",
      amountMode: "SATS",
      amountInput: "2100",
      amountSat: 2_100,
    });
  });

  test("derives a sats repeat action for an Ark payment", () => {
    expect(
      resolveRepeatPaymentDetails({
        destination: "  tark1recipient  ",
        paymentMethod: "ark",
        amountSat: 5_000,
      }),
    ).toEqual({
      destination: "tark1recipient",
      comment: "",
      amountMode: "SATS",
      amountInput: "5000",
      amountSat: 5_000,
    });
  });

  test("only enables repeat for successful outgoing payments", () => {
    const repeatPayment = {
      destination: "merchant@example.com",
      comment: "",
      amountMode: "SATS",
      amountInput: "2100",
      amountSat: 2_100,
    };

    expect(
      canRepeatPayment({ direction: "outgoing", movementStatus: "successful", repeatPayment }),
    ).toBe(true);
    expect(
      canRepeatPayment({ direction: "incoming", movementStatus: "successful", repeatPayment }),
    ).toBe(false);
    expect(
      canRepeatPayment({ direction: "outgoing", movementStatus: "failed", repeatPayment }),
    ).toBe(false);
  });

  test("restores fiat only when the preferred currency still matches", () => {
    const repeatPayment = {
      destination: "merchant@example.com",
      comment: "",
      amountMode: "FIAT",
      amountInput: "12.50",
      amountSat: 10_000,
      fiatCurrency: "USD",
    };

    expect(getRepeatPaymentPrefill(repeatPayment, "USD")).toEqual({
      amountInput: "12.50",
      amountMode: "FIAT",
    });
    expect(getRepeatPaymentPrefill(repeatPayment, "EUR")).toEqual({
      amountInput: "10000",
      amountMode: "SATS",
    });
  });

  test("uses standard LNURL when a comment must be delivered", () => {
    expect(shouldUseArkDirectLightningAddressRoute("ark", null)).toBe(true);
    expect(shouldUseArkDirectLightningAddressRoute("ark", "Order 307")).toBe(false);
    expect(shouldUseArkDirectLightningAddressRoute("lightning", null)).toBe(false);
  });

  test("finds the new matching Ark-routed movement", () => {
    const movement = {
      id: 9,
      status: "successful",
      subsystem: { name: "bark.arkoor", kind: "send" },
      sent_to: [
        { destination: "tark-destination", payment_method: "ark", amount_sat: 3_000 },
      ],
    };

    expect(
      findNewArkoorMovementId({
        existingMovementIds: new Set([8]),
        movements: [movement],
        destination: "tark-destination",
        amountSat: 3_000,
      }),
    ).toBe(9);
    expect(
      findNewArkoorMovementId({
        existingMovementIds: new Set([9]),
        movements: [movement],
        destination: "tark-destination",
        amountSat: 3_000,
      }),
    ).toBeUndefined();
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

  test("treats boards as internal transfers but preserves offboards as outgoing sends", () => {
    expect(isInternalBoardingTransfer({ type: "Onchain", movementKind: "onboard" })).toBe(true);
    expect(isInternalBoardingTransfer({ type: "Onchain", movementKind: "offboard" })).toBe(false);
  });

  test("presents a canceled exit as neutral activity", () => {
    const canceledExit = {
      type: "Onchain",
      movementKind: "exit",
      movementStatus: "canceled",
      direction: "outgoing",
      amount: 92_590,
    };

    expect(isCanceledTransaction(canceledExit)).toBe(true);
    expect(getTransactionDisplayLabel(canceledExit)).toBe("Canceled Ark Exit");
    expect(getTransactionAccountingValues(canceledExit)).toEqual({
      direction: "None",
      amount: 0,
    });
    expect(
      getTransactionDisplayLabel({
        type: "Bolt11",
        movementStatus: "canceled",
      }),
    ).toBe("Canceled Lightning");
  });

  test("preserves signed accounting values for completed activity", () => {
    expect(
      getTransactionAccountingValues({
        type: "Arkoor",
        direction: "outgoing",
        amount: 21_000,
      }),
    ).toEqual({ direction: "Outgoing", amount: -21_000 });

    expect(
      getTransactionAccountingValues({
        type: "Onchain",
        movementKind: "onboard",
        direction: "incoming",
        amount: 50_000,
      }),
    ).toEqual({ direction: "Transfer", amount: 50_000 });
  });
});
