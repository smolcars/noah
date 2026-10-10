import { describe, expect, mock, test } from "bun:test";

mock.module("noah-tools", () => ({
  getAppVariant: () => "signet",
}));

const { fiatToSats, formatFiatAmount } = await import("../../src/lib/fiatCurrency");
const { formatNumber } = await import("../../src/lib/utils");

describe("fiat-to-sats conversion", () => {
  test("treats an empty fiat amount as zero", () => {
    expect(fiatToSats(Number.parseFloat(""), 100_000)).toBe(0);
  });
});

test("Spanish amount entry preserves fractional digits while displaying a comma", () => {
  expect(formatNumber("12345.00", "es")).toBe("12.345,00");
  expect(formatNumber("0.", "es")).toBe("0,");
  expect(formatNumber("0.05", "es")).toBe("0,05");
  expect(formatFiatAmount("12.50", "EUR", "es")).toBe("12,50\u00a0€");
  expect(fiatToSats(Number.parseFloat("12.50"), 100_000)).toBe(12_500);
});

test("Japanese amount entry preserves fractional digits and yen displays whole units", () => {
  expect(formatNumber("12345.00", "ja")).toBe("12,345.00");
  expect(formatNumber("0.", "ja")).toBe("0.");
  expect(formatNumber("0.05", "ja")).toBe("0.05");
  // Intl uses either yen symbol depending on the platform's locale data.
  expect(formatFiatAmount("1234.5", "JPY", "ja")).toMatch(/^[¥￥]1,235$/);
});
