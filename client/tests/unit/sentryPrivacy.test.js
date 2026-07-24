import { describe, expect, test } from "bun:test";

const {
  redactSensitiveLnurlQuery,
  redactSensitiveLnurlUrl,
  redactSentryBreadcrumbData,
  redactSentryRequestData,
} = await import("../../src/lib/sentryPrivacy");

describe("Sentry LNURL privacy", () => {
  test("redacts sensitive callback values without dropping URL context", () => {
    const redacted = redactSensitiveLnurlUrl(
      "https://pay.example/callback?amount=2000&payerdata=%7B%22name%22%3A%22Alice%22%7D&comment=Thanks#receipt",
    );
    const url = new URL(redacted);

    expect(url.origin + url.pathname).toBe("https://pay.example/callback");
    expect(url.searchParams.get("amount")).toBe("2000");
    expect(url.searchParams.get("payerdata")).toBe("[Filtered]");
    expect(url.searchParams.get("comment")).toBe("[Filtered]");
    expect(url.hash).toBe("#receipt");
  });

  test("preserves URLs and query strings without sensitive LNURL fields", () => {
    const url = "https://pay.example/callback?amount=2000";
    const query = "amount=2000";

    expect(redactSensitiveLnurlUrl(url)).toBe(url);
    expect(redactSensitiveLnurlQuery(query)).toBe(query);
  });

  test("redacts standalone string, object, and tuple query representations", () => {
    const stringQuery = redactSensitiveLnurlQuery(
      "?amount=2000&PayerData=alice&COMMENT=private",
    );
    const stringParams = new URLSearchParams(stringQuery.slice(1));

    expect(stringParams.get("amount")).toBe("2000");
    expect(stringParams.get("PayerData")).toBe("[Filtered]");
    expect(stringParams.get("COMMENT")).toBe("[Filtered]");
    expect(redactSensitiveLnurlQuery({ amount: "2000", payerdata: "alice" })).toEqual({
      amount: "2000",
      payerdata: "[Filtered]",
    });
    expect(
      redactSensitiveLnurlQuery([
        ["amount", "2000"],
        ["comment", "private"],
      ]),
    ).toEqual([
      ["amount", "2000"],
      ["comment", "[Filtered]"],
    ]);
  });

  test("sanitizes Sentry breadcrumb and request query fields", () => {
    expect(
      redactSentryBreadcrumbData({
        url: "https://pay.example/callback",
        "http.query": "amount=2000&comment=private",
        status_code: 200,
      }),
    ).toEqual({
      url: "https://pay.example/callback",
      "http.query": "amount=2000&comment=%5BFiltered%5D",
      status_code: 200,
    });

    expect(
      redactSentryRequestData({
        url: "https://pay.example/callback?payerdata=alice",
        query_string: { payerdata: "alice", amount: "2000" },
        method: "GET",
      }),
    ).toEqual({
      url: "https://pay.example/callback?payerdata=%5BFiltered%5D",
      query_string: { payerdata: "[Filtered]", amount: "2000" },
      method: "GET",
    });
  });
});
