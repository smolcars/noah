import { formatNumber } from "~/lib/utils";

export const SUPPORTED_FIAT_CURRENCIES = [
  "USD",
  "EUR",
  "GBP",
  "CAD",
  "CHF",
  "AUD",
  "JPY",
  "KRW",
] as const;

export type FiatCurrencyCode = (typeof SUPPORTED_FIAT_CURRENCIES)[number];

export type FiatCurrencyInfo = {
  code: FiatCurrencyCode;
  name: string;
  symbol: string;
  decimals: number;
};

export const FIAT_CURRENCY_INFO: Record<FiatCurrencyCode, FiatCurrencyInfo> = {
  USD: { code: "USD", name: "U.S. Dollar", symbol: "$", decimals: 2 },
  EUR: { code: "EUR", name: "Euro", symbol: "€", decimals: 2 },
  GBP: { code: "GBP", name: "British Pound", symbol: "£", decimals: 2 },
  CAD: { code: "CAD", name: "Canadian Dollar", symbol: "CA$", decimals: 2 },
  CHF: { code: "CHF", name: "Swiss Franc", symbol: "CHF", decimals: 2 },
  AUD: { code: "AUD", name: "Australian Dollar", symbol: "A$", decimals: 2 },
  JPY: { code: "JPY", name: "Japanese Yen", symbol: "¥", decimals: 0 },
  KRW: { code: "KRW", name: "South Korean Won", symbol: "₩", decimals: 0 },
};

export type FiatRates = Partial<Record<FiatCurrencyCode, number>>;

export const isFiatCurrencyCode = (value: unknown): value is FiatCurrencyCode =>
  typeof value === "string" && SUPPORTED_FIAT_CURRENCIES.includes(value as FiatCurrencyCode);

export const getFiatCurrencyInfo = (currency: FiatCurrencyCode): FiatCurrencyInfo =>
  FIAT_CURRENCY_INFO[currency];

export const satsToFiat = (sats: number, btcPrice: number, currency: FiatCurrencyCode): string => {
  const { decimals } = getFiatCurrencyInfo(currency);
  return ((sats * btcPrice) / 100_000_000).toFixed(decimals);
};

export const fiatToSats = (amount: number, btcPrice: number): number => {
  return Math.round((amount / btcPrice) * 100_000_000);
};

export const formatFiatAmount = (amount: number | string, currency: FiatCurrencyCode): string => {
  const { decimals, symbol } = getFiatCurrencyInfo(currency);
  const numericAmount = typeof amount === "number" ? amount : Number(amount);
  const formattedAmount = Number.isFinite(numericAmount)
    ? numericAmount.toFixed(decimals)
    : amount.toString();
  const separator = /^[A-Z]+$/.test(symbol) ? " " : "";
  return `${symbol}${separator}${formatNumber(formattedAmount)}`;
};
