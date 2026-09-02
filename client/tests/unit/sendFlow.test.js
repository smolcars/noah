import { describe, expect, test } from "bun:test";

import {
  canAddRecipientNote,
  getBip321Rails,
  getBip321MethodForRail,
  getNextSendStage,
  getRecommendedRail,
  isMaxCompatibleDestination,
} from "../../src/lib/sendFlow";

describe("send payment rail choices", () => {
  test("recommends Ark, Lightning, then on-chain and groups Lightning variants", () => {
    expect(
      getBip321Rails({
        onchainAddress: "bc1qdestination",
        offer: "lno1offer",
        lightningInvoice: "lnbc1invoice",
        arkAddress: "ark1destination",
      }),
    ).toEqual(["ark", "lightning", "onchain"]);
  });

  test("uses an invoice before an offer for the Lightning rail", () => {
    expect(
      getBip321MethodForRail("lightning", {
        lightningInvoice: "lnbc1invoice",
        offer: "lno1offer",
      }),
    ).toBe("lightning");
    expect(getBip321MethodForRail("lightning", { offer: "lno1offer" })).toBe("offer");
  });

  test("recommends the first eligible rail without hiding unavailable rails", () => {
    expect(
      getRecommendedRail(["ark", "lightning", "onchain"], {
        ark: false,
        lightning: false,
        onchain: true,
      }),
    ).toBe("onchain");
    expect(
      getRecommendedRail(["ark", "lightning", "onchain"], {
        ark: false,
        lightning: false,
        onchain: false,
      }),
    ).toBe("ark");
  });
});

describe("send recipient notes", () => {
  test("offers a note only when a Lightning address accepts comments", () => {
    expect(canAddRecipientNote("lnurl", 64)).toBe(true);
    expect(canAddRecipientNote("lnurl", 0)).toBe(false);
    expect(canAddRecipientNote("lightning", 64)).toBe(false);
    expect(canAddRecipientNote("ark", 64)).toBe(false);
    expect(canAddRecipientNote("onchain", 64)).toBe(false);
  });
});

describe("MAX recipient compatibility", () => {
  test("accepts only destinations with an on-chain payment method", () => {
    expect(isMaxCompatibleDestination("onchain", null)).toBe(true);
    expect(isMaxCompatibleDestination("bip321", { onchainAddress: "bc1qdestination" })).toBe(true);
    expect(isMaxCompatibleDestination("bip321", { arkAddress: "ark1destination" })).toBe(false);
    expect(isMaxCompatibleDestination("lightning", null)).toBe(false);
  });
});

describe("send stage progression", () => {
  test("walks an amount-first payment through each unresolved decision", () => {
    const baseProgress = {
      entry: "amount-first",
      amountConfirmed: false,
      recipientConfirmed: false,
      railConfirmed: false,
      sourceConfirmed: false,
      rails: ["ark", "lightning", "onchain"],
      selectedRail: "onchain",
      sourceOptions: ["offchain", "onchain"],
    };

    expect(getNextSendStage(baseProgress)).toBe("amount");
    expect(getNextSendStage({ ...baseProgress, amountConfirmed: true })).toBe("recipient");
    expect(
      getNextSendStage({ ...baseProgress, amountConfirmed: true, recipientConfirmed: true }),
    ).toBe("method");
    expect(
      getNextSendStage({
        ...baseProgress,
        amountConfirmed: true,
        recipientConfirmed: true,
        railConfirmed: true,
      }),
    ).toBe("source");
    expect(
      getNextSendStage({
        ...baseProgress,
        amountConfirmed: true,
        recipientConfirmed: true,
        railConfirmed: true,
        sourceConfirmed: true,
      }),
    ).toBe("review");
  });

  test("asks for a prefilled recipient before an unresolved amount", () => {
    expect(
      getNextSendStage({
        entry: "recipient-first",
        amountConfirmed: false,
        recipientConfirmed: false,
        railConfirmed: true,
        sourceConfirmed: true,
        rails: ["lightning"],
        selectedRail: "lightning",
        sourceOptions: [],
      }),
    ).toBe("recipient");
  });

  test("chooses the balance before the recipient for MAX", () => {
    expect(
      getNextSendStage({
        entry: "max",
        amountConfirmed: true,
        recipientConfirmed: false,
        railConfirmed: true,
        sourceConfirmed: false,
        rails: ["onchain"],
        selectedRail: "onchain",
        sourceOptions: ["offchain", "onchain"],
      }),
    ).toBe("source");
  });

  test("shows funding sources when no balance can cover an on-chain payment", () => {
    expect(
      getNextSendStage({
        entry: "amount-first",
        amountConfirmed: true,
        recipientConfirmed: true,
        railConfirmed: true,
        sourceConfirmed: false,
        rails: ["onchain"],
        selectedRail: "onchain",
        sourceOptions: [],
      }),
    ).toBe("source");
  });
});
