import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
import { APP_VARIANT } from "../config";
export { formatBip177 } from "~/lib/bitcoinAmount";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export function isValidEmail(email: string): boolean {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return emailRegex.test(email);
}

export const formatNumber = (num: number | string) => {
  const numStr = num.toString();
  const parts = numStr.split(".");

  // Add commas to the integer part only
  parts[0] = parts[0].replace(/(\d)(?=(\d{3})+(?!\d))/g, "$1,");

  // Join back with decimal part if it exists
  return parts.join(".");
};

export const satsToBtc = (sats: number) => {
  return (sats / 100_000_000).toFixed(8);
};

export const isNetworkMatch = (
  network: string | undefined,
  paymentType: "onchain" | "lightning" | "ark",
): boolean => {
  if (!network) return false;

  if (paymentType === "ark") {
    // For Ark, testnet covers testnet, signet, and regtest
    if (APP_VARIANT === "mainnet") {
      return network === "mainnet";
    } else {
      // APP_VARIANT is testnet, signet, or regtest
      // Ark network should be "testnet"
      return network === "testnet";
    }
  }

  // For onchain Bitcoin addresses: Exception for signet to allow testnet addresses
  if (paymentType === "onchain") {
    if (APP_VARIANT === "signet" && (network === "signet" || network === "testnet")) {
      return true;
    }
    return network === APP_VARIANT;
  }

  // For lightning invoices: Exact network match required
  return network === APP_VARIANT;
};
