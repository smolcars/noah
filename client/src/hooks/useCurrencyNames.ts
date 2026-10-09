import { useGT } from "gt-react-native";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";

export function useCurrencyNames(): Record<FiatCurrencyCode, string> {
  const gt = useGT();
  return {
    USD: gt("U.S. Dollar"),
    EUR: gt("Euro"),
    GBP: gt("British Pound"),
    CAD: gt("Canadian Dollar"),
    CHF: gt("Swiss Franc"),
    AUD: gt("Australian Dollar"),
    JPY: gt("Japanese Yen"),
    BRL: gt("Brazilian Real"),
    KRW: gt("South Korean Won"),
    INR: gt("Indian Rupee"),
    MXN: gt("Mexican Peso"),
    SGD: gt("Singapore Dollar"),
  };
}
