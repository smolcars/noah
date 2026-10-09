import { expect, mock, test } from "bun:test";
import { msg } from "gt-i18n";
import { internalInitializeGTSRA } from "@generaltranslation/react-core/pure";
import { loadTranslations } from "../../loadTranslations";
import config from "../../gt.config.json";

let locale = "es";
mock.module("gt-react-native", () => ({ getLocale: () => locale }));
internalInitializeGTSRA({ ...config, loadTranslations });
const { getBackgroundMessages } = await import("../../src/lib/backgroundTranslations");

test("background translation keeps dynamic notification data local and intact", async () => {
  const message = msg("You received {amount}", { amount: "₿ 1.234" });
  const spanish = await getBackgroundMessages();
  expect(spanish(message)).toBe("Has recibido ₿ 1.234");
  locale = "en";
  const english = await getBackgroundMessages();
  expect(english(message)).toBe("You received ₿ 1.234");
});
