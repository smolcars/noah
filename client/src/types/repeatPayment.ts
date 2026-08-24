import type { FiatCurrencyCode } from "~/lib/fiatCurrency";

export type PaymentAmountMode = "FIAT" | "SATS";

export type RepeatPaymentDetails = {
  destination: string;
  comment: string;
  amountMode: PaymentAmountMode;
  amountInput: string;
  amountSat: number;
  fiatCurrency?: FiatCurrencyCode;
};

export type RepeatPaymentMetadata = Omit<RepeatPaymentDetails, "amountSat">;
