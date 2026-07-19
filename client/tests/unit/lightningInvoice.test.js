import { describe, expect, test } from "bun:test";

import {
  getInvoiceDescriptionByteLength,
  isInvoiceDescriptionValid,
  MAX_INVOICE_DESCRIPTION_BYTES,
  normalizeInvoiceDescription,
} from "../../src/lib/lightningInvoice";

describe("BOLT11 invoice descriptions", () => {
  test("trims an invoice description", () => {
    expect(normalizeInvoiceDescription("  Coffee beans  ")).toBe("Coffee beans");
  });

  test("omits blank or missing descriptions", () => {
    expect(normalizeInvoiceDescription("   ")).toBeUndefined();
    expect(normalizeInvoiceDescription()).toBeUndefined();
  });

  test("accepts a description at the BOLT11 byte limit", () => {
    expect(isInvoiceDescriptionValid("a".repeat(MAX_INVOICE_DESCRIPTION_BYTES))).toBe(true);
  });

  test("rejects a description over the BOLT11 byte limit", () => {
    expect(isInvoiceDescriptionValid("a".repeat(MAX_INVOICE_DESCRIPTION_BYTES + 1))).toBe(false);
  });

  test("counts multibyte Unicode descriptions as UTF-8 bytes", () => {
    expect(getInvoiceDescriptionByteLength("☕️")).toBe(6);
  });

  test("trims before measuring description bytes", () => {
    expect(getInvoiceDescriptionByteLength("  coffee  ")).toBe(6);
  });
});
