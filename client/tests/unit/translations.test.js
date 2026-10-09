import { expect, test } from "bun:test";
import config from "../../gt.config.json";
import { loadTranslations } from "../../loadTranslations";

test("every configured target locale has bundled translations", async () => {
  for (const locale of config.locales) {
    const translations = await loadTranslations(locale);
    expect(Object.keys(translations).length).toBeGreaterThan(0);
  }
});

test("source and unsupported locales use the original copy", async () => {
  expect(await loadTranslations(config.defaultLocale)).toEqual({});
  expect(await loadTranslations("unsupported")).toEqual({});
});

// App language must control display formatting independently of the device language.
test("Spanish Bitcoin amounts use Spanish number formatting", async () => {
  const { formatBitcoinAmount } = await import("../../src/lib/bitcoinAmount");
  expect(formatBitcoinAmount(1234567, "sats", "es")).toBe("1.234.567 sats");
  expect(formatBitcoinAmount(1234567, "bip177", "en")).toBe("₿\u00a01,234,567");
});
