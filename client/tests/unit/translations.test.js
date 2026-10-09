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
