import { afterAll, describe, expect, mock, test } from "bun:test";
import { readFileSync } from "node:fs";

const nativeWalletConfigs = JSON.parse(
  readFileSync(
    new URL(
      "../../nitromodules/noah-tools/android/src/main/assets/noah_bark_config.json",
      import.meta.url,
    ),
    "utf8",
  ),
);

const originalRegtestUrl = process.env.EXPO_PUBLIC_REGTEST_URL;
delete process.env.EXPO_PUBLIC_REGTEST_URL;

mock.module("expo-constants", () => ({
  default: { expoConfig: { version: "0.1.4" } },
}));
mock.module("noah-tools", () => ({
  getAppVariant: () => "signet",
  isGooglePlayServicesAvailable: () => true,
  nativeLog: () => {},
}));
mock.module("react-native", () => ({
  Platform: { OS: "android" },
}));

const {
  buildArkUserAgent,
  getBaseWalletConfig,
  getDefaultBlockheightEndpoint,
  getDefaultEsploraEndpoint,
  getEffectiveWalletConfig,
  getWalletEndpoints,
  getWalletRefreshExpiryThreshold,
  getWalletRpcAuth,
} = await import("../../src/lib/walletConfig");

afterAll(() => {
  if (originalRegtestUrl === undefined) {
    delete process.env.EXPO_PUBLIC_REGTEST_URL;
  } else {
    process.env.EXPO_PUBLIC_REGTEST_URL = originalRegtestUrl;
  }
});

describe("base wallet configuration", () => {
  test("keeps the mainnet settings together", () => {
    expect(getBaseWalletConfig("mainnet")).toEqual({
      regtest: false,
      signet: false,
      bitcoin: true,
      config: {
        esplora: "https://mempool.second.tech/api",
        ark: "https://ark.second.tech",
        vtxo_refresh_expiry_threshold: 144,
        fallback_fee_rate: 10000,
        htlc_recv_claim_delta: 18,
        vtxo_exit_margin: 12,
        round_tx_required_confirmations: 2,
      },
    });
  });

  test("keeps the signet settings together", () => {
    expect(getBaseWalletConfig("signet")).toEqual({
      regtest: false,
      signet: true,
      bitcoin: false,
      config: {
        esplora: "https://esplora.signet.2nd.dev",
        ark: "https://ark.signet.2nd.dev",
        vtxo_refresh_expiry_threshold: 12,
        fallback_fee_rate: 10000,
        htlc_recv_claim_delta: 18,
        vtxo_exit_margin: 12,
        round_tx_required_confirmations: 1,
      },
    });
  });

  test("keeps the Android regtest settings together", () => {
    expect(getBaseWalletConfig("regtest")).toEqual({
      regtest: true,
      signet: false,
      bitcoin: false,
      config: {
        bitcoind: "http://10.0.2.2:18443",
        ark: "http://10.0.2.2:3535",
        bitcoind_user: "second",
        bitcoind_pass: "ark",
        vtxo_refresh_expiry_threshold: 24,
        fallback_fee_rate: 10000,
        htlc_recv_claim_delta: 18,
        vtxo_exit_margin: 12,
        round_tx_required_confirmations: 1,
      },
    });
  });

  test("keeps the native background wallet config aligned with the JavaScript wallet", () => {
    for (const variant of ["mainnet", "signet", "regtest"]) {
      const config = getBaseWalletConfig(variant).config;

      expect(nativeWalletConfigs[variant]).toMatchObject({
        ark: config.ark,
        esplora: config.esplora ?? null,
        bitcoind: config.bitcoind ?? null,
        bitcoindCookie: config.bitcoind_cookie ?? null,
        bitcoindUser: config.bitcoind_user ?? null,
        bitcoindPass: config.bitcoind_pass ?? null,
        vtxoRefreshExpiryThreshold: config.vtxo_refresh_expiry_threshold,
        fallbackFeeRate: config.fallback_fee_rate,
        htlcRecvClaimDelta: config.htlc_recv_claim_delta,
        vtxoExitMargin: config.vtxo_exit_margin,
        roundTxRequiredConfirmations: config.round_tx_required_confirmations,
      });
    }
  });
});

describe("effective wallet configuration", () => {
  test("adds the app identity and current Esplora endpoint without mutating the base", () => {
    const baseConfig = getBaseWalletConfig("signet");
    const result = getEffectiveWalletConfig("https://override.example.com", "signet");

    expect(result.config?.user_agent).toBe("noah-signet-android/0.1.4");
    expect(result.config?.esplora).toBe("https://override.example.com");
    expect(baseConfig.config).not.toHaveProperty("user_agent");
    expect(baseConfig.config?.esplora).toBe("https://esplora.signet.2nd.dev");
  });

  test("does not apply an Esplora endpoint to regtest", () => {
    const result = getEffectiveWalletConfig("https://override.example.com", "regtest");

    expect(result.config?.user_agent).toBe("noah-regtest-android/0.1.4");
    expect(result.config?.esplora).toBeUndefined();
  });
});

describe("wallet configuration accessors", () => {
  test("formats the Ark user agent", () => {
    expect(buildArkUserAgent("signet", "android", "0.1.4")).toBe(
      "noah-signet-android/0.1.4",
    );
  });

  test("returns wallet endpoints, thresholds, and RPC credentials", () => {
    expect(getDefaultEsploraEndpoint("signet")).toBe("https://esplora.signet.2nd.dev");
    expect(getWalletEndpoints(null, "mainnet")).toEqual({
      ark: "https://ark.second.tech",
      esplora: "https://mempool.second.tech/api",
      bitcoind: undefined,
    });
    expect(getWalletRefreshExpiryThreshold("regtest")).toBe(24);
    expect(getWalletRpcAuth("regtest")).toEqual({ username: "second", password: "ark" });
  });

  test("keeps refresh thresholds below Lightning receive HTLC lifetimes", () => {
    expect(getWalletRefreshExpiryThreshold("mainnet")).toBeLessThan(188);
    expect(getWalletRefreshExpiryThreshold("signet")).toBeLessThan(50);
  });

  test("preserves the existing default block-height endpoints", () => {
    expect(getDefaultBlockheightEndpoint("mainnet")).toBe(
      "https://mempool.second.tech/api/blocks/tip/height",
    );
    expect(getDefaultBlockheightEndpoint("signet")).toBe(
      "https://mempool.space/signet/api/blocks/tip/height",
    );
    expect(getDefaultBlockheightEndpoint("regtest")).toBe("http://10.0.2.2:18443");
  });
});
