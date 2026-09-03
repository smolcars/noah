import { describe, expect, mock, test } from "bun:test";

mock.module("noah-tools", () => ({
  getAppVariant: () => "signet",
}));

const { fiatToSats } = await import("../../src/lib/fiatCurrency");

describe("fiat-to-sats conversion", () => {
  test("treats an empty fiat amount as zero", () => {
    expect(fiatToSats(Number.parseFloat(""), 100_000)).toBe(0);
  });
});
