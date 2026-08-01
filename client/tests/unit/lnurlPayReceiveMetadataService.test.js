import { beforeEach, describe, expect, mock, test } from "bun:test";
import { ok } from "neverthrow";

globalThis.__DEV__ = false;

const calls = [];
const listLnurlPayReceiveMetadata = mock();
const acknowledgeLnurlPayReceiveMetadata = mock();
const history = mock();
const updateHistoryMetadata = mock();
const scheduleBackup = mock();
const invalidateQueries = mock();

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
mock.module("../../src/lib/api", () => ({
  listLnurlPayReceiveMetadata,
  acknowledgeLnurlPayReceiveMetadata,
}));
mock.module("../../src/lib/backupCoordinator", () => ({
  scheduleBackup,
}));
mock.module("../../src/lib/paymentsApi", () => ({
  history,
  updateHistoryMetadata,
  newAddress: mock(),
  onchainAddress: mock(),
  onchainIsMine: mock(),
  boardArk: mock(),
  bolt11Invoice: mock(),
  onchainDrain: mock(),
  onchainSend: mock(),
  sendOnchainFromOffchain: mock(),
  sendArkoorPayment: mock(),
  payLightningInvoice: mock(),
  payLightningInvoiceWithOrigin: mock(),
  payLightningOffer: mock(),
  boardAllArk: mock(),
  offboardAllArk: mock(),
  estimateArkoorPaymentFee: mock(),
  estimateLightningSendFee: mock(),
  estimateSendOnchainFee: mock(),
  estimateOnchainWalletSendFee: mock(),
  estimateOffboardAllFee: mock(),
  estimateBoardOffchainFee: mock(),
  estimateStandardOnchainTxFee: mock(),
  validateArkoorPaymentAddress: mock(),
}));
mock.module("../../src/lib/log", () => ({
  default: () => ({ w: () => {} }),
}));
mock.module("../../src/queryClient", () => ({
  queryClient: { invalidateQueries },
}));

const { reconcileLnurlPayReceiveMetadata } =
  await import("../../src/lib/lnurlPayReceiveMetadataService");

const record = {
  id: "opaque-id",
  payment_hash: "a".repeat(64),
  amount_sat: 1_000,
  payer_data: {
    name: "Alice",
    identifier: "alice@example.com",
  },
  comment: "Hello",
};

const persistedMetadata = {
  noah: {
    lnurl_pay: {
      schema_version: 1,
      payer_data: record.payer_data,
      comment: record.comment,
    },
  },
};

const lightningReceiveMovement = (metadata = {}) => ({
  id: 7,
  status: "successful",
  subsystem: { name: "bark.lightning_receive", kind: "receive" },
  metadata_json: JSON.stringify({
    payment_hash: record.payment_hash,
    ...metadata,
  }),
  received_on: [],
});

beforeEach(() => {
  calls.length = 0;
  listLnurlPayReceiveMetadata.mockClear();
  acknowledgeLnurlPayReceiveMetadata.mockClear();
  history.mockClear();
  updateHistoryMetadata.mockClear();
  scheduleBackup.mockClear();
  invalidateQueries.mockClear();

  listLnurlPayReceiveMetadata.mockImplementation(async () => {
    calls.push("list");
    return ok({ items: [record] });
  });
  updateHistoryMetadata.mockImplementation(async () => {
    calls.push("write");
    return ok(undefined);
  });
  scheduleBackup.mockImplementation(() => calls.push("backup"));
  invalidateQueries.mockImplementation(async () => calls.push("invalidate"));
  acknowledgeLnurlPayReceiveMetadata.mockImplementation(async () => {
    calls.push("ack");
    return ok(undefined);
  });
});

describe("LNURL-pay receive metadata reconciliation", () => {
  test("writes, verifies, schedules backup, then acknowledges", async () => {
    const before = lightningReceiveMovement();
    const after = lightningReceiveMovement(persistedMetadata);
    history
      .mockImplementationOnce(async () => {
        calls.push("history-before");
        return ok([before]);
      })
      .mockImplementationOnce(async () => {
        calls.push("history-verify");
        return ok([after]);
      });

    const result = await reconcileLnurlPayReceiveMetadata();

    expect(result.isOk()).toBe(true);
    expect(calls).toEqual([
      "list",
      "history-before",
      "write",
      "history-verify",
      "backup",
      "invalidate",
      "ack",
    ]);
    expect(acknowledgeLnurlPayReceiveMetadata).toHaveBeenCalledWith({
      ids: [record.id],
    });
  });

  test("leaves metadata unacknowledged until its movement exists", async () => {
    history.mockImplementationOnce(async () => {
      calls.push("history-before");
      return ok([]);
    });

    const result = await reconcileLnurlPayReceiveMetadata();

    expect(result._unsafeUnwrap()).toEqual({
      reconciled: [],
      deferredCount: 1,
    });
    expect(updateHistoryMetadata).not.toHaveBeenCalled();
    expect(scheduleBackup).not.toHaveBeenCalled();
    expect(acknowledgeLnurlPayReceiveMetadata).not.toHaveBeenCalled();
  });

  test("schedules backup before ack when a previous pass already wrote the metadata", async () => {
    history.mockImplementationOnce(async () => {
      calls.push("history-before");
      return ok([lightningReceiveMovement(persistedMetadata)]);
    });

    const result = await reconcileLnurlPayReceiveMetadata();

    expect(result.isOk()).toBe(true);
    expect(calls).toEqual(["list", "history-before", "backup", "invalidate", "ack"]);
    expect(updateHistoryMetadata).not.toHaveBeenCalled();
  });
});
