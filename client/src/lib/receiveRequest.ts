import { encodeBIP321 } from "bip-321";

type AmountlessReceiveRequest = {
  amountSat: null;
  arkAddress: string;
  lightningInvoice?: undefined;
  onchainAddress: string;
};

type AmountfulReceiveRequest = {
  amountSat: number;
  arkAddress: string;
  lightningInvoice: string;
  onchainAddress: string;
};

export type ReceiveRequest = AmountlessReceiveRequest | AmountfulReceiveRequest;

export const buildReceiveRequestUri = ({
  amountSat,
  arkAddress,
  lightningInvoice,
  onchainAddress,
}: ReceiveRequest) => {
  if (amountSat !== null && (!Number.isSafeInteger(amountSat) || amountSat <= 0)) {
    throw new Error("Receive amount must be a positive whole number of sats");
  }

  return encodeBIP321({
    address: onchainAddress,
    amount: amountSat === null ? undefined : amountSat / 100_000_000,
    ark: arkAddress,
    lightning: lightningInvoice,
  }).uri;
};
