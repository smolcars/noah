import type { BarkCreateOpts } from "react-native-nitro-ark";
import RNFSTurbo from "react-native-fs-turbo";
import { APP_VARIANT } from "./config";
import { decode } from "light-bolt11-decoder";
import { Result } from "neverthrow";
import { Platform } from "react-native";
import { isGooglePlayServicesAvailable } from "noah-tools";
import * as Device from "expo-device";

const isEmail = (n: string): boolean => /^[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,4}$/.test(n);
const isOnion = (n: string): boolean => /.onion$/.test(n);
const isUsername = (n: string): boolean => /^[a-z0-9_.-]+$/.test(n);
const parseEmail = (email: string): string[] => email.split("@");
export const MIN_AUTO_BOARD_AMOUNT = 10_000;
// Auto-backup timing controls
export const AUTO_BACKUP_FRESHNESS_MS = 24 * 60 * 60 * 1000; // 24 hours
export const AUTO_BACKUP_MIN_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes
export const AUTO_BACKUP_SUCCESS_BANNER_MS = 5 * 1000; // 5 seconds
export const AUTO_BACKUP_IN_PROGRESS_TIMEOUT_MS = 30 * 1000; // 30 seconds

export const MNEMONIC_KEYCHAIN_SERVICE = `com.noah.mnemonic.${APP_VARIANT}`;
export const AUTH_TOKEN_KEYCHAIN_SERVICE = `com.noah.auth-token.${APP_VARIANT}`;
export const ARK_SERVER_ACCESS_TOKEN_KEYCHAIN_SERVICE = `com.noah.ark-server-access-token.${APP_VARIANT}`;
export const KEYCHAIN_USERNAME = "noah";

export const PLATFORM = Platform.OS;
export const DOCUMENT_DIRECTORY_PATH = RNFSTurbo.DocumentDirectoryPath;
export const CACHES_DIRECTORY_PATH = RNFSTurbo.CachesDirectoryPath;

export const hasGooglePlayServices = () => {
  if (PLATFORM === "ios") {
    return true;
  }
  return PLATFORM === "android" && Device.isDevice && isGooglePlayServicesAvailable();
};

const REGTEST_URL = process.env.EXPO_PUBLIC_REGTEST_URL
  ? process.env.EXPO_PUBLIC_REGTEST_URL
  : PLATFORM === "android"
    ? "10.0.2.2"
    : "localhost";

const REGTEST_SERVER_URL = process.env.EXPO_PUBLIC_REGTEST_SERVER_URL
  ? process.env.EXPO_PUBLIC_REGTEST_SERVER_URL
  : PLATFORM === "android"
    ? "http://10.0.2.2:3000"
    : "http://localhost:3000";

const getArkDataPath = (): string => {
  switch (APP_VARIANT) {
    case "regtest":
      return `${DOCUMENT_DIRECTORY_PATH}/noah-data-regtest`;
    case "signet":
      return `${DOCUMENT_DIRECTORY_PATH}/noah-data-signet`;
    case "mainnet":
      return `${DOCUMENT_DIRECTORY_PATH}/noah-data-mainnet`;
    default:
      // Default to signet for development builds that aren't launched via a profile
      return `${DOCUMENT_DIRECTORY_PATH}/noah-data-signet`;
  }
};

export const ARK_DATA_PATH = getArkDataPath();

export const getServerEndpoint = (): string => {
  switch (APP_VARIANT) {
    case "regtest":
      return REGTEST_SERVER_URL;
    case "signet":
      return "https://signet.noahwallet.io";
    case "mainnet":
      return "https://noahwallet.io";
    default:
      return "https://signet.noahwallet.io";
  }
};

export const getLnurlDomain = (): string => {
  switch (APP_VARIANT) {
    case "regtest":
      return process.env.EXPO_PUBLIC_REGTEST_LNURL_DOMAIN ?? "localhost.com";
    case "signet":
      return "signet.noahwallet.io";
    case "mainnet":
      return "noahwallet.io";
    default:
      return "signet.noahwallet.io";
  }
};

type WalletCreationOptions = Omit<BarkCreateOpts, "mnemonic">;

export const SIGNET_CONFIG: WalletCreationOptions = {
  regtest: false,
  signet: true,
  bitcoin: false,
  config: {
    esplora: "esplora.signet.2nd.dev",
    ark: "ark.signet.2nd.dev",
    vtxo_refresh_expiry_threshold: 48,
    fallback_fee_rate: 10000,
    htlc_recv_claim_delta: 18,
    vtxo_exit_margin: 12,
    round_tx_required_confirmations: 1,
  },
};

export const REGTEST_CONFIG: WalletCreationOptions = {
  regtest: true,
  signet: false,
  bitcoin: false,
  config: {
    bitcoind: `http://${REGTEST_URL}:18443`,
    ark: `http://${REGTEST_URL}:3535`,
    bitcoind_user: "second",
    bitcoind_pass: "ark",
    vtxo_refresh_expiry_threshold: 24,
    fallback_fee_rate: 10000,
    htlc_recv_claim_delta: 18,
    vtxo_exit_margin: 12,
    round_tx_required_confirmations: 1,
  },
};

export const PRODUCTION_CONFIG: WalletCreationOptions = {
  regtest: false,
  signet: false,
  bitcoin: true,
  config: {
    esplora: "https://mempool.second.tech/api",
    ark: "https://ark.second.tech",
    vtxo_refresh_expiry_threshold: 288,
    fallback_fee_rate: 10000,
    htlc_recv_claim_delta: 18,
    vtxo_exit_margin: 12,
    round_tx_required_confirmations: 2,
  },
};

const getActiveWalletConfig = (): WalletCreationOptions => {
  switch (APP_VARIANT) {
    case "regtest":
      return REGTEST_CONFIG;
    case "signet":
      return SIGNET_CONFIG;
    case "mainnet":
      return PRODUCTION_CONFIG;
    default:
      // Default to signet for development builds that aren't launched via a profile
      return SIGNET_CONFIG;
  }
};

export const ACTIVE_WALLET_CONFIG = getActiveWalletConfig();

export const decodeBolt11 = (invoice: string) => {
  return Result.fromThrowable(decode)(invoice).unwrapOr(null);
};

export const msatToSatoshi = (msat: number) => msat / 1000;

export const mempoolPriceEndpoint = "https://mempool.noderunner.wtf/api/v1/prices";
export const mempoolHistoricalPriceEndpoint =
  "https://mempool.noderunner.wtf/api/v1/historical-price";

export const getBlockheightEndpoint = () => {
  switch (APP_VARIANT) {
    case "mainnet":
      return "https://mempool.second.tech/api/blocks/tip/height";
    case "signet":
      return "https://mempool.space/signet/api/blocks/tip/height";
    case "regtest":
      return `http://${REGTEST_URL}:18443`;
  }
};

export const isValidLightningAddress = (url: string): boolean => {
  if (!isEmail(url)) {
    return false;
  }

  const [username, domain] = parseEmail(url);

  if (!isUsername(username)) {
    return false;
  }

  if (isOnion(domain)) {
    return false;
  }

  return true;
};

export const stringToUint8Array = (str: string) => {
  return Uint8Array.from(str, (x) => x.charCodeAt(0));
};

export const hexToUint8Array = (hexString: string) => {
  const matches = hexString.match(/.{1,2}/g);
  return new Uint8Array(matches ? matches.map((byte) => parseInt(byte, 16)) : []);
};

export const bytesToHexString = (bytes: Uint8Array<ArrayBufferLike>): string => {
  return bytes.reduce(function (memo, i) {
    return memo + ("0" + i.toString(16)).slice(-2); //pad with leading 0 if <16
  }, "");
};

export const getDomainFromURL = (url: string) =>
  url.replace("http://", "").replace("https://", "").split(/[/?#]/)[0];

export const BITCOIN_FACTS = [
  "There can only ever be 21 million bitcoin.",
  "Fix the money, fix the world.",
  "Money for everyone, by everyone.",
  "Send money to anyone, anywhere, anytime.",
  "No one can freeze your funds.",
  "A 'satoshi' is its smallest unit.",
  "It's freedom in your pocket.",
  "Hope for a fairer financial system.",
  "Taxation is theft.",
  "Power back to the people.",
  "Be your own bank.",
  "A powerful tool for savers.",
  "The future of money is here.",
  "Not your keys, not your coins.",
  "Stay humble, stack sats.",
  "In code we trust.",
  "Governments can print money, but not Bitcoin.",
  "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks.",
  "Vires in numeris. (Strength in numbers)",
  "\"If you don't believe it or don't get it, I don't have time to try to convince you, sorry.\" - Satoshi Nakamoto",
  "There is no second best.",
  "When in doubt, zoom out.",
  "Separate money and state.",
  "Tick tock next block.",
];
