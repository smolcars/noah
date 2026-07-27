import { describe, expect, mock, test } from "bun:test";

globalThis.__DEV__ = false;

mock.module("noah-tools", () => ({
  getAppVariant: () => "mainnet",
  isGooglePlayServicesAvailable: () => true,
}));
mock.module("react-native", () => ({
  Platform: { OS: "ios" },
}));
mock.module("react-native-fs-turbo", () => ({
  default: {
    CachesDirectoryPath: "/tmp",
    DocumentDirectoryPath: "/tmp",
  },
}));
mock.module("expo-device", () => ({
  isDevice: true,
}));

const {
  buildLnurlPayReceiveMetadataPatch,
  doesPersistedLnurlPayReceiveMetadataMatch,
  findLightningReceiveMovement,
  formatLnurlPayPayerLabel,
  getMovementLightningPaymentHash,
  parseLnurlPayReceiveMetadata,
} = await import("../../src/lib/lnurlPayReceiveMetadata");

const PAYMENT_HASH = "f5636521e98000697a6700b979c288ddad56cb3995a2eb07550872c466ccc3e5";
const MAINNET_INVOICE =
  "lnbc20u1p3y0x3hpp5743k2g0fsqqxj7n8qzuhns5gmkk4djeejk3wkp64ppevgekvc0jsdqcve5kzar2v9nr5gpqd4hkuetesp5ez2g297jduwc20t6lmqlsg3man0vf2jfd8ar9fh8fhn2g8yttfkqxqy9gcqcqzys9qrsgqrzjqtx3k77yrrav9hye7zar2rtqlfkytl094dsp0ms5majzth6gt7ca6uhdkxl983uywgqqqqlgqqqvx5qqjqrzjqd98kxkpyw0l9tyy8r8q57k7zpy9zjmh6sez752wj6gcumqnj3yxzhdsmg6qq56utgqqqqqqqqqqqeqqjq7jd56882gtxhrjm03c93aacyfy306m4fq0tskf83c0nmet8zc2lxyyg3saz8x6vwcp26xnrlagf9semau3qm2glysp7sv95693fphvsp54l567";

const lightningReceiveMovement = (overrides = {}) => ({
  id: 7,
  status: "successful",
  subsystem: { name: "bark.lightning_receive", kind: "receive" },
  metadata_json: "{}",
  intended_balance_sat: 1_000,
  effective_balance_sat: 1_000,
  offchain_fee_sat: 0,
  sent_to: [],
  received_on: [],
  input_vtxos: [],
  output_vtxos: [],
  exited_vtxos: [],
  created_at: "2026-07-25T00:00:00Z",
  updated_at: "2026-07-25T00:00:00Z",
  ...overrides,
});

describe("LNURL-pay receive movement metadata", () => {
  test("builds and parses the versioned Noah namespace", () => {
    const patch = buildLnurlPayReceiveMetadataPatch({
      payer_data: {
        name: " Alice ",
        identifier: " alice@example.com ",
      },
      comment: " Hello ",
    });

    expect(patch).toEqual({
      noah: {
        lnurl_pay: {
          schema_version: 1,
          payer_data: {
            name: "Alice",
            identifier: "alice@example.com",
          },
          comment: "Hello",
        },
      },
    });
    expect(parseLnurlPayReceiveMetadata(JSON.stringify(patch))).toEqual({
      schemaVersion: 1,
      payerData: {
        name: "Alice",
        identifier: "alice@example.com",
      },
      comment: "Hello",
    });
  });

  test("ignores malformed or unsupported metadata without affecting history", () => {
    expect(parseLnurlPayReceiveMetadata("not json")).toBeUndefined();
    expect(
      parseLnurlPayReceiveMetadata(
        JSON.stringify({ noah: { lnurl_pay: { schema_version: 2 } } }),
      ),
    ).toBeUndefined();
    expect(
      parseLnurlPayReceiveMetadata(
        JSON.stringify({
          noah: {
            lnurl_pay: {
              schema_version: 1,
              payer_data: { name: "Alice\u202e" },
              comment: "Hello\nworld",
            },
          },
        }),
      ),
    ).toEqual({
      schemaVersion: 1,
      payerData: {},
    });
  });

  test("rejects Unicode line and paragraph separators in display metadata", () => {
    for (const separator of ["\u2028", "\u2029"]) {
      const patch = buildLnurlPayReceiveMetadataPatch({
        payer_data: { name: `Alice${separator}Smith` },
        comment: `Hello${separator}world`,
      });

      expect(patch).toEqual({
        noah: {
          lnurl_pay: {
            schema_version: 1,
            payer_data: {},
          },
        },
      });
      expect(parseLnurlPayReceiveMetadata(JSON.stringify(patch))).toEqual({
        schemaVersion: 1,
        payerData: {},
      });
    }
  });

  test("matches Lightning receives by exact payment hash from Bark metadata", () => {
    const exact = lightningReceiveMovement({
      metadata_json: JSON.stringify({ payment_hash: PAYMENT_HASH.toUpperCase() }),
    });
    const different = lightningReceiveMovement({
      id: 8,
      metadata_json: JSON.stringify({ payment_hash: "0".repeat(64) }),
    });

    expect(getMovementLightningPaymentHash(exact)).toBe(PAYMENT_HASH);
    expect(findLightningReceiveMovement([different, exact], PAYMENT_HASH)?.id).toBe(7);
    expect(findLightningReceiveMovement([exact], "f5636521")).toBeUndefined();
  });

  test("falls back to the received BOLT11 invoice for older movements", () => {
    const movement = lightningReceiveMovement({
      received_on: [
        {
          payment_method: "invoice",
          destination: MAINNET_INVOICE,
          amount_sat: 2_000,
        },
      ],
    });

    expect(getMovementLightningPaymentHash(movement)).toBe(PAYMENT_HASH);
  });

  test("formats payer labels", () => {
    const payerData = {
      name: "Alice",
      identifier: "alice@example.com",
    };
    const persisted = {
      schemaVersion: 1,
      payerData,
      comment: "Hello",
    };

    expect(formatLnurlPayPayerLabel(payerData)).toBe("Alice (⚡ alice@example.com)");
    expect(
      doesPersistedLnurlPayReceiveMetadataMatch(persisted, {
        payer_data: payerData,
        comment: "Hello",
      }),
    ).toBe(true);
  });
});
