import Constants from "expo-constants";
import type { BarkConfigOpts, BarkCreateOpts } from "react-native-nitro-ark";
import { Platform } from "react-native";
import { APP_VARIANT } from "~/config";
import { normalizeEsploraEndpoint } from "~/lib/esploraUrl";

export type AppVariant = "mainnet" | "signet" | "regtest";
export type WalletCreationOptions = Omit<BarkCreateOpts, "mnemonic">;

type BaseWalletConfig = Omit<WalletCreationOptions, "config"> & {
  config: BarkConfigOpts;
};

const REGTEST_HOST = process.env.EXPO_PUBLIC_REGTEST_URL
  ? process.env.EXPO_PUBLIC_REGTEST_URL
  : Platform.OS === "android"
    ? "10.0.2.2"
    : "localhost";

const WALLET_CONFIGS: Record<AppVariant, BaseWalletConfig> = {
  mainnet: {
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
  },
  signet: {
    regtest: false,
    signet: true,
    bitcoin: false,
    config: {
      esplora: "https://esplora.signet.2nd.dev",
      ark: "https://ark.signet.2nd.dev",
      vtxo_refresh_expiry_threshold: 48,
      fallback_fee_rate: 10000,
      htlc_recv_claim_delta: 18,
      vtxo_exit_margin: 12,
      round_tx_required_confirmations: 1,
    },
  },
  regtest: {
    regtest: true,
    signet: false,
    bitcoin: false,
    config: {
      bitcoind: `http://${REGTEST_HOST}:18443`,
      ark: `http://${REGTEST_HOST}:3535`,
      bitcoind_user: "second",
      bitcoind_pass: "ark",
      vtxo_refresh_expiry_threshold: 24,
      fallback_fee_rate: 10000,
      htlc_recv_claim_delta: 18,
      vtxo_exit_margin: 12,
      round_tx_required_confirmations: 1,
    },
  },
};

export const buildArkUserAgent = (
  appVariant: AppVariant,
  platform: string,
  version: string,
): string => `noah-${appVariant}-${platform}/${version}`;

export const getBaseWalletConfig = (appVariant: AppVariant = APP_VARIANT): BaseWalletConfig =>
  WALLET_CONFIGS[appVariant];

export const getDefaultEsploraEndpoint = (appVariant: AppVariant = APP_VARIANT): string | null => {
  if (appVariant === "regtest") {
    return null;
  }

  const configuredEndpoint = getBaseWalletConfig(appVariant).config.esplora;
  if (!configuredEndpoint) {
    return null;
  }

  return normalizeEsploraEndpoint(configuredEndpoint).unwrapOr(null);
};

export const getEffectiveWalletConfig = (
  esploraEndpoint?: string | null,
  appVariant: AppVariant = APP_VARIANT,
): WalletCreationOptions => {
  const walletOptions = getBaseWalletConfig(appVariant);
  const endpoint =
    esploraEndpoint === undefined ? getDefaultEsploraEndpoint(appVariant) : esploraEndpoint;

  return {
    ...walletOptions,
    config: {
      ...walletOptions.config,
      user_agent: buildArkUserAgent(
        appVariant,
        Platform.OS,
        Constants.expoConfig?.version ?? "unknown",
      ),
      ...(appVariant !== "regtest" && endpoint ? { esplora: endpoint } : {}),
    },
  };
};

export const getWalletEndpoints = (
  esploraEndpoint?: string | null,
  appVariant: AppVariant = APP_VARIANT,
) => {
  const config = getEffectiveWalletConfig(esploraEndpoint, appVariant).config;

  return {
    ark: config?.ark,
    esplora: config?.esplora,
    bitcoind: config?.bitcoind,
  };
};

export const getWalletRefreshExpiryThreshold = (appVariant: AppVariant = APP_VARIANT): number =>
  getBaseWalletConfig(appVariant).config.vtxo_refresh_expiry_threshold;

export const getWalletRpcAuth = (appVariant: AppVariant = APP_VARIANT) => {
  const config = getBaseWalletConfig(appVariant).config;

  return {
    username: config.bitcoind_user,
    password: config.bitcoind_pass,
  };
};

export const getDefaultBlockheightEndpoint = (appVariant: AppVariant = APP_VARIANT): string => {
  switch (appVariant) {
    case "mainnet":
      return "https://mempool.second.tech/api/blocks/tip/height";
    case "signet":
      return "https://mempool.space/signet/api/blocks/tip/height";
    case "regtest":
      return `http://${REGTEST_HOST}:18443`;
  }
};
