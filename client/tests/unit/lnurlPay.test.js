import { describe, expect, mock, test } from "bun:test";

globalThis.__DEV__ = false;

mock.module("noah-tools", () => ({
  getAppVariant: () => "mainnet",
  isGooglePlayServicesAvailable: () => true,
  nativeLog: () => {},
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
  buildLnurlPayerData,
  createLnurlPayCallbackUrl,
  normalizeLnurlPayCommentAllowed,
  parseLnurlPayCallbackResponse,
  parseLnurlPayRequestResponse,
  validateLnurlPayComment,
  validateLnurlPayInvoice,
  validateMatchingArkAddress,
} = await import("../../src/lib/lnurlPay");

const MAINNET_INVOICE =
  "lnbc20u1p3y0x3hpp5743k2g0fsqqxj7n8qzuhns5gmkk4djeejk3wkp64ppevgekvc0jsdqcve5kzar2v9nr5gpqd4hkuetesp5ez2g297jduwc20t6lmqlsg3man0vf2jfd8ar9fh8fhn2g8yttfkqxqy9gcqcqzys9qrsgqrzjqtx3k77yrrav9hye7zar2rtqlfkytl094dsp0ms5majzth6gt7ca6uhdkxl983uywgqqqqlgqqqvx5qqjqrzjqd98kxkpyw0l9tyy8r8q57k7zpy9zjmh6sez752wj6gcumqnj3yxzhdsmg6qq56utgqqqqqqqqqqqeqqjq7jd56882gtxhrjm03c93aacyfy306m4fq0tskf83c0nmet8zc2lxyyg3saz8x6vwcp26xnrlagf9semau3qm2glysp7sv95693fphvsp54l567";
const TESTNET_INVOICE =
  "lntb2500n1pwxlkl5pp5g8hz28tlf950ps942lu3dknfete8yax2ctywpwjs872x9kngvvuqdqage5hyum5yp6x2um5yp5kuan0d93k2cqzyskdc5s2ltgm9kklz42x3e4tggdd9lcep2s9t2yk54gnfxg48wxushayrt52zjmua43gdnxmuc5s0c8g29ja9vnxs6x3kxgsha07htcacpmdyl64";
const AMOUNTLESS_MAINNET_INVOICE =
  "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql";

const identity = {
  name: "  Alice  ",
  identifier: "  alice@noahwallet.io  ",
};

describe("LNURL-pay requests", () => {
  const validRequest = {
    callback: "https://pay.example/callback",
    maxSendable: 10_000_000,
    minSendable: 1_000,
    metadata: "[]",
    tag: "payRequest",
  };

  test("accepts a valid payment request", () => {
    expect(parseLnurlPayRequestResponse(validRequest)._unsafeUnwrap()).toEqual(validRequest);
  });

  test("parses payer data requirements and preserves descriptor properties", () => {
    const payerData = {
      name: { mandatory: false },
      auth: { mandatory: true, k1: "00" },
    };

    expect(
      parseLnurlPayRequestResponse({ ...validRequest, payerData })._unsafeUnwrap().payerData,
    ).toEqual(payerData);
  });

  test("surfaces discovery errors", () => {
    expect(
      parseLnurlPayRequestResponse({ status: "ERROR", reason: "Address unavailable" })
        ._unsafeUnwrapErr()
        .message,
    ).toBe("Address unavailable");
    expect(
      parseLnurlPayRequestResponse({ status: "ERROR", reason: " " })._unsafeUnwrapErr().message,
    ).toContain("rejected");
  });

  test("rejects malformed amount ranges and required fields", () => {
    expect(parseLnurlPayRequestResponse(null).isErr()).toBe(true);
    expect(parseLnurlPayRequestResponse({ ...validRequest, callback: "" }).isErr()).toBe(true);
    expect(parseLnurlPayRequestResponse({ ...validRequest, metadata: null }).isErr()).toBe(true);
    expect(parseLnurlPayRequestResponse({ ...validRequest, minSendable: 0 }).isErr()).toBe(true);
    expect(parseLnurlPayRequestResponse({ ...validRequest, minSendable: 1.5 }).isErr()).toBe(true);
    expect(
      parseLnurlPayRequestResponse({ ...validRequest, minSendable: 2_000, maxSendable: 1_000 })
        .isErr(),
    ).toBe(true);
    expect(parseLnurlPayRequestResponse({ ...validRequest, maxSendable: "10000" }).isErr()).toBe(
      true,
    );
  });

  test("rejects malformed payer data requirements during discovery", () => {
    expect(parseLnurlPayRequestResponse({ ...validRequest, payerData: null }).isErr()).toBe(true);
    expect(parseLnurlPayRequestResponse({ ...validRequest, payerData: [] }).isErr()).toBe(true);
    expect(
      parseLnurlPayRequestResponse({ ...validRequest, payerData: { name: [] } }).isErr(),
    ).toBe(true);
    expect(
      parseLnurlPayRequestResponse({
        ...validRequest,
        payerData: { name: { mandatory: "yes" } },
      }).isErr(),
    ).toBe(true);
  });
});

describe("LNURL-pay payer data negotiation", () => {
  test("returns null when payerData is absent or empty", () => {
    expect(buildLnurlPayerData(undefined, identity)._unsafeUnwrap()).toBeNull();
    expect(buildLnurlPayerData({}, identity)._unsafeUnwrap()).toBeNull();
  });

  test("includes only requested supported fields and trims their values", () => {
    expect(buildLnurlPayerData({ name: { mandatory: false } }, identity)._unsafeUnwrap()).toEqual({
      name: "Alice",
    });
    expect(
      buildLnurlPayerData({ identifier: { mandatory: false } }, identity)._unsafeUnwrap(),
    ).toEqual({ identifier: "alice@noahwallet.io" });
    expect(
      buildLnurlPayerData(
        {
          name: { mandatory: true },
          identifier: { mandatory: true },
        },
        identity,
      )._unsafeUnwrap(),
    ).toEqual({
      name: "Alice",
      identifier: "alice@noahwallet.io",
    });
  });

  test("omits unavailable optional supported fields", () => {
    expect(
      buildLnurlPayerData(
        {
          name: { mandatory: false },
          identifier: { mandatory: false },
        },
        { name: " ", identifier: null },
      )._unsafeUnwrap(),
    ).toBeNull();
  });

  test("rejects unavailable mandatory supported fields", () => {
    const missingName = buildLnurlPayerData(
      { name: { mandatory: true } },
      { name: " ", identifier: "alice@noahwallet.io" },
    );
    const missingIdentifier = buildLnurlPayerData(
      { identifier: { mandatory: true } },
      { name: "Alice", identifier: null },
    );

    expect(missingName._unsafeUnwrapErr().message).toContain("saved name");
    expect(missingIdentifier._unsafeUnwrapErr().message).toContain("saved identifier");
  });

  test("ignores optional unsupported fields and rejects mandatory ones", () => {
    expect(
      buildLnurlPayerData({ email: { mandatory: false } }, identity)._unsafeUnwrap(),
    ).toBeNull();

    const mandatoryUnsupported = buildLnurlPayerData(
      { auth: { mandatory: true, k1: "00" } },
      identity,
    );
    expect(mandatoryUnsupported._unsafeUnwrapErr().message).toContain(
      "unsupported payer data: auth",
    );
  });

  test("rejects malformed requirements and descriptors", () => {
    expect(buildLnurlPayerData(null, identity).isErr()).toBe(true);
    expect(buildLnurlPayerData([], identity).isErr()).toBe(true);
    expect(buildLnurlPayerData({ name: [] }, identity).isErr()).toBe(true);
    expect(buildLnurlPayerData({ name: { mandatory: "yes" } }, identity).isErr()).toBe(true);
  });
});

describe("LNURL-pay callback URL", () => {
  test("preserves parameters and encodes payer data and comments once", () => {
    const result = createLnurlPayCallbackUrl(
      "https://pay.example/callback?foo=bar&amount=1&amount=2&payerdata=old&ark=old&comment=old#receipt",
      2_000_000,
      JSON.stringify({
        name: "Ålice & Bob",
        identifier: "alice+wallet@example.com",
      }),
      "Thanks & cheers",
      "02ABC",
    );
    const callback = new URL(result._unsafeUnwrap());

    expect(callback.searchParams.get("foo")).toBe("bar");
    expect(callback.searchParams.getAll("amount")).toEqual(["2000000"]);
    expect(callback.searchParams.get("payerdata")).toBe(
      JSON.stringify({
        name: "Ålice & Bob",
        identifier: "alice+wallet@example.com",
      }),
    );
    expect(callback.searchParams.get("ark")).toBe("02ABC");
    expect(callback.searchParams.get("comment")).toBe("Thanks & cheers");
    expect(callback.hash).toBe("#receipt");
    expect(callback.toString()).not.toContain("%25C3");
  });

  test("removes payerdata for a null payload and does not add Ark or comment", () => {
    const result = createLnurlPayCallbackUrl(
      "https://pay.example/callback?foo=bar&payerdata=old&ark=old&comment=old",
      1_000,
      null,
      null,
    );
    const callback = new URL(result._unsafeUnwrap());

    expect(callback.searchParams.get("foo")).toBe("bar");
    expect(callback.searchParams.get("amount")).toBe("1000");
    expect(callback.searchParams.has("payerdata")).toBe(false);
    expect(callback.searchParams.has("ark")).toBe(false);
    expect(callback.searchParams.has("comment")).toBe(false);
  });

  test("supports an Ark callback with a comment and no payer data", () => {
    const result = createLnurlPayCallbackUrl(
      "https://pay.example/callback?payerdata=old&comment=old",
      1_000,
      null,
      "Thank you",
      "02ABC",
    );
    const callback = new URL(result._unsafeUnwrap());

    expect(callback.searchParams.has("payerdata")).toBe(false);
    expect(callback.searchParams.get("comment")).toBe("Thank you");
    expect(callback.searchParams.get("ark")).toBe("02ABC");
  });

  test("rejects invalid callback URLs and amounts", () => {
    expect(createLnurlPayCallbackUrl("not a URL", 1_000, null, null).isErr()).toBe(true);
    expect(
      createLnurlPayCallbackUrl("http://pay.example/callback", 1_000, null, null).isErr(),
    ).toBe(true);
    expect(createLnurlPayCallbackUrl("https://pay.example/callback", 0, null, null).isErr()).toBe(
      true,
    );
    expect(createLnurlPayCallbackUrl("https://pay.example/callback", 1.5, null, null).isErr()).toBe(
      true,
    );
  });
});

describe("LNURL-pay comments", () => {
  test("normalizes absent and invalid limits to zero", () => {
    expect(normalizeLnurlPayCommentAllowed(undefined)).toBe(0);
    expect(normalizeLnurlPayCommentAllowed(null)).toBe(0);
    expect(normalizeLnurlPayCommentAllowed("10")).toBe(0);
    expect(normalizeLnurlPayCommentAllowed(0)).toBe(0);
    expect(normalizeLnurlPayCommentAllowed(-1)).toBe(0);
    expect(normalizeLnurlPayCommentAllowed(1.5)).toBe(0);
    expect(normalizeLnurlPayCommentAllowed(10)).toBe(10);
  });

  test("accepts empty comments and comments at the exact limit", () => {
    expect(validateLnurlPayComment(null, 0)._unsafeUnwrap()).toBeNull();
    expect(validateLnurlPayComment("", 0)._unsafeUnwrap()).toBeNull();
    expect(validateLnurlPayComment("hello", 5)._unsafeUnwrap()).toBe("hello");
    expect(validateLnurlPayComment("👍", 1)._unsafeUnwrap()).toBe("👍");
  });

  test("rejects unsupported and over-limit comments", () => {
    expect(validateLnurlPayComment("hello", 0)._unsafeUnwrapErr().message).toContain(
      "does not accept",
    );
    expect(validateLnurlPayComment("hello!", 5)._unsafeUnwrapErr().message).toContain(
      "up to 5 characters",
    );
    expect(validateLnurlPayComment("👍👍", 1)._unsafeUnwrapErr().message).toContain(
      "up to 1 characters",
    );
  });
});

describe("LNURL-pay callback responses", () => {
  test("surfaces LNURL errors", () => {
    const result = parseLnurlPayCallbackResponse({
      status: "ERROR",
      reason: "Payment is unavailable",
    });

    expect(result._unsafeUnwrapErr().message).toBe("Payment is unavailable");
  });

  test("rejects non-objects and returns only non-empty payment fields", () => {
    expect(parseLnurlPayCallbackResponse(null).isErr()).toBe(true);
    expect(parseLnurlPayCallbackResponse([]).isErr()).toBe(true);
    expect(
      parseLnurlPayCallbackResponse({
        pr: `  ${MAINNET_INVOICE}  `,
        ark: "  ARK1ADDRESS  ",
        ignored: true,
      })._unsafeUnwrap(),
    ).toEqual({
      pr: MAINNET_INVOICE,
      ark: "ARK1ADDRESS",
    });
    expect(parseLnurlPayCallbackResponse({ pr: " ", ark: 1 })._unsafeUnwrap()).toEqual({});
  });
});

describe("LNURL-pay callback validation", () => {
  test("accepts an exact-amount invoice", () => {
    expect(validateLnurlPayInvoice(MAINNET_INVOICE, 2_000_000).isOk()).toBe(true);
  });

  test("rejects an invoice with a different amount", () => {
    const result = validateLnurlPayInvoice(MAINNET_INVOICE, 2_000_001);

    expect(result._unsafeUnwrapErr().message).toContain("does not match");
  });

  test("rejects malformed and amountless invoices", () => {
    expect(validateLnurlPayInvoice("lnbc1invalid", 2_000_000).isErr()).toBe(true);
    expect(validateLnurlPayInvoice(AMOUNTLESS_MAINNET_INVOICE, 2_000_000).isErr()).toBe(true);
  });

  test("rejects an invoice for a different network", () => {
    const result = validateLnurlPayInvoice(TESTNET_INVOICE, 250_000);

    expect(result._unsafeUnwrapErr().message).toContain("different network");
  });

  test("accepts only the negotiated Ark address", () => {
    expect(validateMatchingArkAddress("  ARK1ABC  ", "ark1abc")._unsafeUnwrap()).toBe("ARK1ABC");
    expect(validateMatchingArkAddress(undefined, "ark1abc").isErr()).toBe(true);
    expect(validateMatchingArkAddress("ark1def", "ark1abc").isErr()).toBe(true);
  });
});
