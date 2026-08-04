import { beforeEach, describe, expect, mock, test } from "bun:test";
import { err, ok } from "neverthrow";

globalThis.__DEV__ = false;

const kyGet = mock();
const expoFetch = mock();
const queryClientFetchQuery = mock();
const payLightningInvoice = mock();
const payLightningInvoiceWithOrigin = mock();
const sendArkoorPayment = mock();
const validateArkoorPaymentAddress = mock();
const getArkInfo = mock();
const createLnurlPayCallbackUrl = mock();
const parseLnurlPayCallbackResponse = mock();
const validateLnurlPayInvoice = mock();
const validateLnurlPayComment = mock();
const buildLnurlPayerData = mock();

const unusedPaymentApi = () => {
  throw new Error("Unexpected payment API call");
};

mock.module("@tanstack/react-query", () => ({
  useMutation: (options) => options,
  useQuery: (options) => options,
}));
mock.module("ky", () => ({ default: { get: kyGet } }));
mock.module("expo/fetch", () => ({ fetch: expoFetch }));
mock.module("../../src/contexts/AlertProvider", () => ({
  useAlert: () => ({ showAlert: () => {} }),
}));
mock.module("../../src/queryClient", () => ({
  queryClient: { fetchQuery: queryClientFetchQuery, invalidateQueries: () => {} },
}));
mock.module("../../src/lib/log", () => ({
  default: () => ({ d: () => {}, e: () => {}, w: () => {} }),
}));
mock.module("../../src/lib/walletApi", () => ({ getArkInfo }));
mock.module("../../src/store/profileStore", () => ({
  useProfileStore: { getState: () => ({ displayName: "Alice" }) },
}));
mock.module("../../src/store/serverStore", () => ({
  useServerStore: { getState: () => ({ lightningAddress: "alice@noahwallet.io" }) },
}));
mock.module("../../src/lib/lnurlPay", () => ({
  buildLnurlPayerData,
  createLnurlPayCallbackUrl,
  normalizeLnurlPayCommentAllowed: (value) => (typeof value === "number" ? value : 0),
  parseLnurlPayCallbackResponse,
  parseLnurlPayRequestResponse: (value) => ok(value),
  validateLnurlPayComment,
  validateLnurlPayInvoice,
}));
mock.module("../../src/lib/paymentsApi", () => ({
  newAddress: unusedPaymentApi,
  onchainAddress: unusedPaymentApi,
  onchainIsMine: unusedPaymentApi,
  boardArk: unusedPaymentApi,
  bolt11Invoice: unusedPaymentApi,
  onchainDrain: unusedPaymentApi,
  onchainSend: unusedPaymentApi,
  sendOnchainFromOffchain: unusedPaymentApi,
  sendArkoorPayment,
  payLightningInvoice,
  payLightningInvoiceWithOrigin,
  payLightningOffer: unusedPaymentApi,
  boardAllArk: unusedPaymentApi,
  offboardAllArk: unusedPaymentApi,
  estimateArkoorPaymentFee: unusedPaymentApi,
  estimateLightningSendFee: unusedPaymentApi,
  estimateSendOnchainFee: unusedPaymentApi,
  estimateOnchainWalletSendFee: unusedPaymentApi,
  estimateOffboardAllFee: unusedPaymentApi,
  estimateBoardOffchainFee: unusedPaymentApi,
  estimateStandardOnchainTxFee: unusedPaymentApi,
  validateArkoorPaymentAddress,
}));

const { resolveLnurlPayRouteForLightningAddress, useSend } =
  await import("../../src/hooks/usePayments");

const ARK_SERVER_PUBKEY = `02${"ab".repeat(32)}`;
const OTHER_ARK_SERVER_PUBKEY = `03${"cd".repeat(32)}`;

const standardRoute = {
  method: "lightning",
  callback: "https://pay.example/callback",
  metadata: "[]",
  commentAllowed: 0,
  minSendableMsat: 1_000,
  maxSendableMsat: 10_000_000,
  origin: {
    method: "lightning-address",
    value: "receiver@example.com",
  },
};

beforeEach(() => {
  kyGet.mockClear();
  expoFetch.mockClear();
  queryClientFetchQuery.mockClear();
  payLightningInvoice.mockClear();
  payLightningInvoiceWithOrigin.mockClear();
  sendArkoorPayment.mockClear();
  validateArkoorPaymentAddress.mockClear();
  getArkInfo.mockClear();
  createLnurlPayCallbackUrl.mockClear();
  parseLnurlPayCallbackResponse.mockClear();
  validateLnurlPayInvoice.mockClear();
  validateLnurlPayComment.mockClear();
  buildLnurlPayerData.mockClear();

  kyGet.mockImplementation(() => ({
    ok: true,
    json: async () => ({ pr: "lnbc1callbackinvoice" }),
  }));
  expoFetch.mockImplementation(async () => ({
    ok: true,
    status: 200,
    json: async () => ({ pr: "lnbc1callbackinvoice" }),
  }));
  queryClientFetchQuery.mockImplementation(async () => standardRoute);
  payLightningInvoice.mockImplementation(async () =>
    ok({ state: "paid", payment_hash: "payment-hash" }),
  );
  payLightningInvoiceWithOrigin.mockImplementation(async () =>
    ok({ state: "paid", payment_hash: "payment-hash" }),
  );
  sendArkoorPayment.mockImplementation(async () => ok({ txid: "ark-payment" }));
  validateArkoorPaymentAddress.mockImplementation(async () => ok(undefined));
  getArkInfo.mockImplementation(async () => err(new Error("Ark info unavailable")));
  createLnurlPayCallbackUrl.mockImplementation((callback, amountMsat) =>
    ok(`${callback}?amount=${amountMsat}`),
  );
  parseLnurlPayCallbackResponse.mockImplementation((value) => ok(value));
  validateLnurlPayInvoice.mockImplementation(() => ok(undefined));
  validateLnurlPayComment.mockImplementation((comment) => ok(comment));
  buildLnurlPayerData.mockImplementation(() => ok(null));
});

describe("LNURL-pay routing", () => {
  test("discovers once without sending the payer's Ark server", async () => {
    kyGet.mockImplementationOnce(() => ({
      json: async () => ({
        callback: standardRoute.callback,
        metadata: standardRoute.metadata,
        minSendable: standardRoute.minSendableMsat,
        maxSendable: standardRoute.maxSendableMsat,
        tag: "payRequest",
      }),
    }));

    const route = await resolveLnurlPayRouteForLightningAddress(
      "LIGHTNING:Receiver@Example.com",
    );

    expect(kyGet).toHaveBeenCalledTimes(1);
    expect(kyGet).toHaveBeenCalledWith("https://example.com/.well-known/lnurlp/receiver", {
      throwHttpErrors: false,
    });
    expect(route.method).toBe("lightning");
    expect(route.callback).toBe(standardRoute.callback);
    expect(route.origin).toEqual({
      method: "lightning-address",
      value: "receiver@example.com",
    });
  });

  test("accepts a valid LNURL-pay discovery body from a non-2xx response", async () => {
    const responseBody = {
      callback: "https://example.com/callback",
      minSendable: 1_000,
      maxSendable: 3_000,
      metadata: '[["text/plain","Receiver"]]',
      tag: "payRequest",
    };
    kyGet.mockImplementationOnce(() => ({
      ok: false,
      status: 503,
      json: async () => responseBody,
    }));

    const route = await resolveLnurlPayRouteForLightningAddress("receiver@example.com");

    expect(kyGet).toHaveBeenCalledWith("https://example.com/.well-known/lnurlp/receiver", {
      throwHttpErrors: false,
    });
    expect(route).toMatchObject({
      method: "lightning",
      callback: "https://example.com/callback",
      minSendableMsat: 1_000,
      maxSendableMsat: 3_000,
    });
  });

  test("uses Lightning and preserves LUD-12/18 when no advertised Ark server matches", async () => {
    getArkInfo.mockImplementationOnce(async () =>
      ok({ server_pubkey: ARK_SERVER_PUBKEY.toUpperCase() }),
    );
    const payerData = { name: { mandatory: false } };
    kyGet.mockImplementationOnce(() => ({
      json: async () => ({
        callback: standardRoute.callback,
        metadata: standardRoute.metadata,
        minSendable: standardRoute.minSendableMsat,
        maxSendable: standardRoute.maxSendableMsat,
        tag: "payRequest",
        commentAllowed: 64,
        payerData,
        arkServers: [OTHER_ARK_SERVER_PUBKEY],
      }),
    }));

    const route = await resolveLnurlPayRouteForLightningAddress("receiver@example.com");

    expect(kyGet).toHaveBeenCalledTimes(1);
    expect(route).toMatchObject({
      method: "lightning",
      commentAllowed: 64,
      payerData,
    });
  });

  test("selects a matching Ark server without receiving an address or payer metadata", async () => {
    getArkInfo.mockImplementationOnce(async () =>
      ok({ server_pubkey: ARK_SERVER_PUBKEY.toUpperCase() }),
    );
    kyGet.mockImplementationOnce(() => ({
      json: async () => ({
        callback: standardRoute.callback,
        metadata: standardRoute.metadata,
        minSendable: standardRoute.minSendableMsat,
        maxSendable: standardRoute.maxSendableMsat,
        tag: "payRequest",
        commentAllowed: 64,
        payerData: { name: { mandatory: false } },
        arkServers: [OTHER_ARK_SERVER_PUBKEY, ARK_SERVER_PUBKEY],
      }),
    }));

    const route = await resolveLnurlPayRouteForLightningAddress("receiver@example.com");

    expect(route).toMatchObject({
      method: "ark",
      arkServerPubkey: ARK_SERVER_PUBKEY,
      commentAllowed: 0,
    });
    expect(route).not.toHaveProperty("destination");
    expect(route).not.toHaveProperty("payerData");
    expect(kyGet).toHaveBeenCalledWith("https://example.com/.well-known/lnurlp/receiver", {
      throwHttpErrors: false,
    });
  });

  test("waits for fresh LNURL-pay discovery before requesting an invoice", async () => {
    const freshRoute = {
      ...standardRoute,
      callback: "https://fresh.example/callback",
    };
    let releaseDiscovery = () => {};
    queryClientFetchQuery.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          releaseDiscovery = () => resolve(freshRoute);
        }),
    );
    const mutation = useSend("lightning-address");

    const resultPromise = mutation.mutationFn({
      destination: "receiver@example.com",
      amountSat: 2_000,
      comment: null,
      confirmedLnurlPayMethod: "lightning",
    });

    expect(queryClientFetchQuery).toHaveBeenCalledWith(
      expect.objectContaining({
        queryKey: ["lnurl-pay-route", "lightning-address", "receiver@example.com"],
        staleTime: 0,
        retry: false,
      }),
    );
    expect(expoFetch).not.toHaveBeenCalled();

    releaseDiscovery();
    const result = await resultPromise;

    expect(kyGet).not.toHaveBeenCalled();
    expect(expoFetch).toHaveBeenCalledTimes(1);
    expect(expoFetch.mock.calls[0]?.[0]).toBe("https://fresh.example/callback?amount=2000000");
    expect(expoFetch.mock.calls[0]?.[1]?.redirect).toBe("manual");
    expect(expoFetch.mock.calls[0]?.[1]?.signal).toBeDefined();
    expect(createLnurlPayCallbackUrl).toHaveBeenCalledWith(
      freshRoute.callback,
      2_000_000,
      null,
      null,
      undefined,
    );
    expect(payLightningInvoiceWithOrigin).toHaveBeenCalledWith(
      "lnbc1callbackinvoice",
      standardRoute.origin,
    );
    expect(payLightningInvoice).not.toHaveBeenCalled();
    expect(result.state).toBe("paid");
  });

  test("does not send when fresh discovery changes the confirmed payment rail", async () => {
    queryClientFetchQuery.mockImplementationOnce(async () => ({
      ...standardRoute,
      method: "ark",
      arkServerPubkey: "02abc",
    }));
    const mutation = useSend("lightning-address");

    await expect(
      mutation.mutationFn({
        destination: "receiver@example.com",
        amountSat: 2_000,
        comment: null,
        confirmedLnurlPayMethod: "lightning",
      }),
    ).rejects.toThrow("The payment route changed. Review the updated fee and try again.");

    expect(queryClientFetchQuery).toHaveBeenCalledTimes(1);
    expect(expoFetch).not.toHaveBeenCalled();
    expect(createLnurlPayCallbackUrl).not.toHaveBeenCalled();
    expect(sendArkoorPayment).not.toHaveBeenCalled();
    expect(payLightningInvoice).not.toHaveBeenCalled();
    expect(payLightningInvoiceWithOrigin).not.toHaveBeenCalled();
  });

  test("refreshes discovery without sending when no payment rail was confirmed", async () => {
    const mutation = useSend("lightning-address");

    await expect(
      mutation.mutationFn({
        destination: "receiver@example.com",
        amountSat: 2_000,
        comment: null,
      }),
    ).rejects.toThrow("The payment route changed. Review the updated fee and try again.");

    expect(queryClientFetchQuery).toHaveBeenCalledTimes(1);
    expect(expoFetch).not.toHaveBeenCalled();
    expect(sendArkoorPayment).not.toHaveBeenCalled();
    expect(payLightningInvoiceWithOrigin).not.toHaveBeenCalled();
  });

  test("omits a note when the service does not advertise comment support", async () => {
    const mutation = useSend("lightning-address");

    const result = await mutation.mutationFn({
      destination: "receiver@example.com",
      amountSat: 2_000,
      comment: "This should be omitted",
      confirmedLnurlPayMethod: "lightning",
    });

    expect(validateLnurlPayComment).toHaveBeenCalledWith(null, 0);
    expect(createLnurlPayCallbackUrl).toHaveBeenCalledWith(
      standardRoute.callback,
      2_000_000,
      null,
      null,
      undefined,
    );
    expect(result.state).toBe("paid");
  });

  test("accepts a valid callback body regardless of HTTP status", async () => {
    expoFetch.mockImplementationOnce(async () => ({
      ok: false,
      status: 503,
      json: async () => ({ pr: "lnbc1callbackinvoice" }),
    }));
    const mutation = useSend("lightning-address");

    const result = await mutation.mutationFn({
      destination: "receiver@example.com",
      amountSat: 2_000,
      comment: null,
      confirmedLnurlPayMethod: "lightning",
    });

    expect(payLightningInvoiceWithOrigin).toHaveBeenCalledWith(
      "lnbc1callbackinvoice",
      standardRoute.origin,
    );
    expect(payLightningInvoice).not.toHaveBeenCalled();
    expect(result.state).toBe("paid");
  });

  test("uses the exact serialized payer data for the callback", async () => {
    const route = {
      ...standardRoute,
      payerData: {
        name: { mandatory: false },
        identifier: { mandatory: false },
      },
    };
    const payerData = {
      name: "Alice",
      identifier: "alice@noahwallet.io",
    };
    const payerDataJson = JSON.stringify(payerData);
    buildLnurlPayerData.mockImplementationOnce(() => ok(payerData));
    queryClientFetchQuery.mockImplementationOnce(async () => route);
    const mutation = useSend("lightning-address");

    await mutation.mutationFn({
      destination: "receiver@example.com",
      amountSat: 2_000,
      comment: null,
      confirmedLnurlPayMethod: "lightning",
    });

    expect(createLnurlPayCallbackUrl).toHaveBeenCalledWith(
      route.callback,
      2_000_000,
      payerDataJson,
      null,
      undefined,
    );
    expect(validateLnurlPayInvoice).toHaveBeenCalledWith(
      "lnbc1callbackinvoice",
      2_000_000,
    );
  });

  test("propagates discovery failures instead of delegating LNURL-pay to Bark", async () => {
    kyGet.mockImplementation(() => {
      throw new Error("Discovery failed");
    });
    queryClientFetchQuery.mockImplementationOnce((options) => options.queryFn());
    const mutation = useSend("lightning-address");

    await expect(
      mutation.mutationFn({
        destination: "receiver@example.com",
        amountSat: 2_000,
        comment: null,
        confirmedLnurlPayMethod: "lightning",
      }),
    ).rejects.toThrow("Discovery failed");
    expect(payLightningInvoice).not.toHaveBeenCalled();
    expect(payLightningInvoiceWithOrigin).not.toHaveBeenCalled();
  });

  test("waits for the callback before paying a negotiated Ark address", async () => {
    const route = {
      ...standardRoute,
      method: "ark",
      arkServerPubkey: ARK_SERVER_PUBKEY,
    };
    expoFetch.mockImplementation(() => ({
      ok: true,
      json: async () => ({ ark: "ARK1RECEIVER" }),
    }));
    queryClientFetchQuery.mockImplementationOnce(async () => route);
    const mutation = useSend("lightning-address");

    await mutation.mutationFn({
      destination: "receiver@example.com",
      amountSat: 2_000,
      comment: null,
      confirmedLnurlPayMethod: "ark",
    });

    expect(kyGet).not.toHaveBeenCalled();
    expect(expoFetch).toHaveBeenCalledTimes(1);
    expect(createLnurlPayCallbackUrl).toHaveBeenCalledWith(
      route.callback,
      2_000_000,
      null,
      null,
      route.arkServerPubkey,
    );
    expect(validateArkoorPaymentAddress).toHaveBeenCalledWith("ARK1RECEIVER");
    expect(sendArkoorPayment).toHaveBeenCalledWith("ARK1RECEIVER", 2_000);
    expect(payLightningInvoice).not.toHaveBeenCalled();
    expect(payLightningInvoiceWithOrigin).not.toHaveBeenCalled();
  });

  test("rejects an Ark route when the callback omits its destination", async () => {
    const route = {
      ...standardRoute,
      method: "ark",
      arkServerPubkey: "02abc",
    };
    expoFetch.mockImplementationOnce(async () => ({
      json: async () => ({ pr: "lnbc1unexpectedinvoice" }),
    }));
    queryClientFetchQuery.mockImplementationOnce(async () => route);
    const mutation = useSend("lightning-address");

    await expect(
      mutation.mutationFn({
        destination: "receiver@example.com",
        amountSat: 2_000,
        comment: null,
        confirmedLnurlPayMethod: "ark",
      }),
    ).rejects.toThrow("did not return the negotiated Ark address");

    expect(validateArkoorPaymentAddress).not.toHaveBeenCalled();
    expect(sendArkoorPayment).not.toHaveBeenCalled();
  });

  test("rejects an invalid Ark address returned by the callback", async () => {
    const route = {
      ...standardRoute,
      method: "ark",
      arkServerPubkey: "02abc",
    };
    expoFetch.mockImplementationOnce(async () => ({
      json: async () => ({ ark: "ark1invalid" }),
    }));
    queryClientFetchQuery.mockImplementationOnce(async () => route);
    validateArkoorPaymentAddress.mockImplementationOnce(async () =>
      err(new Error("invalid address")),
    );
    const mutation = useSend("lightning-address");

    await expect(
      mutation.mutationFn({
        destination: "receiver@example.com",
        amountSat: 2_000,
        comment: null,
        confirmedLnurlPayMethod: "ark",
      }),
    ).rejects.toThrow("returned an invalid Ark address");

    expect(sendArkoorPayment).not.toHaveBeenCalled();
  });

  test("keeps direct BOLT11 payments on the ordinary invoice API", async () => {
    const mutation = useSend("lightning");

    const result = await mutation.mutationFn({
      destination: "lnbc1directinvoice",
      amountSat: 2_000,
      resolvedAmountSat: 2_000,
      comment: null,
    });

    expect(payLightningInvoice).toHaveBeenCalledWith("lnbc1directinvoice", 2_000);
    expect(payLightningInvoiceWithOrigin).not.toHaveBeenCalled();
    expect(result.state).toBe("paid");
  });
});
