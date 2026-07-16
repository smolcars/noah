import { describe, expect, test } from "bun:test";

import { getMaxSendBalanceSat } from "../../src/lib/onchainSend";

describe("MAX onchain send balance", () => {
  test("shows the selected source's full balance", () => {
    expect(getMaxSendBalanceSat("offchain", 25_000, 40_000)).toBe(40_000);
    expect(getMaxSendBalanceSat("onchain", 25_000, 40_000)).toBe(25_000);
  });

  test("shows the larger available balance until a source is selected", () => {
    expect(getMaxSendBalanceSat(null, 25_000, 40_000)).toBe(40_000);
    expect(getMaxSendBalanceSat(null, 50_000, 40_000)).toBe(50_000);
  });
});
