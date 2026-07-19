import { describe, expect, test } from "bun:test";

import { normalizeInvoiceDescription } from "../../src/lib/lightningInvoice";

describe("BOLT11 invoice descriptions", () => {
  test("trims an invoice description", () => {
    expect(normalizeInvoiceDescription("  Coffee beans  ")).toBe("Coffee beans");
  });

  test("omits blank or missing descriptions", () => {
    expect(normalizeInvoiceDescription("   ")).toBeUndefined();
    expect(normalizeInvoiceDescription()).toBeUndefined();
  });
});
