import { expect, mock, test } from "bun:test";
import { decodeMsg, msg } from "gt-i18n";
import { internalInitializeGTSRA } from "@generaltranslation/react-core/pure";
import { loadTranslations } from "../../loadTranslations";
import config from "../../gt.config.json";

let locale = "es";
const resolvedErrors = [];
mock.module("gt-react-native", () => ({
  getLocale: () => locale,
  msg,
  decodeMsg,
  useMessages: () => (message) => {
    resolvedErrors.push(message);
    return "Error traducido";
  },
}));
internalInitializeGTSRA({ ...config, loadTranslations });
const { getBackgroundMessages } = await import("../../src/lib/backgroundTranslations");
const { useErrorTranslation } = await import("../../src/hooks/useErrorTranslation");

test("background translation keeps dynamic notification data local and intact", async () => {
  const message = msg("You received {amount}", { amount: "₿ 1.234" });
  const spanish = await getBackgroundMessages();
  expect(spanish(message)).toBe("Has recibido ₿ 1.234");
  locale = "ja";
  const japanese = await getBackgroundMessages();
  expect(japanese(msg("You received {amount}", { amount: "₿ 1,234" }))).toBe(
    "₿ 1,234を受け取りました",
  );
  locale = "en";
  const english = await getBackgroundMessages();
  expect(english(message)).toBe("You received ₿ 1.234");
});

test("only registered application errors enter the translation resolver", () => {
  const translateError = useErrorTranslation();
  expect(translateError("Something went wrong on our end. Please try again.")).toBe(
    "Error traducido",
  );
  expect(translateError("Native diagnostic code 42")).toBe("Native diagnostic code 42");
  expect(resolvedErrors).toHaveLength(1);
});
