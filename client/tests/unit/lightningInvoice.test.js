import { describe, expect, test } from "bun:test";

import {
  getInvoiceDescriptionLength,
  isInvoiceDescriptionValid,
  MAX_INVOICE_DESCRIPTION_LENGTH,
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

  test("accepts a description at the character limit", () => {
    expect(isInvoiceDescriptionValid("a".repeat(MAX_INVOICE_DESCRIPTION_LENGTH))).toBe(true);
  });

  test("rejects a description over the character limit", () => {
    expect(isInvoiceDescriptionValid("a".repeat(MAX_INVOICE_DESCRIPTION_LENGTH + 1))).toBe(false);
  });

  test("counts emoji as characters rather than UTF-8 bytes", () => {
    expect(getInvoiceDescriptionLength("☕️")).toBe(2);
  });

  test("trims before measuring description characters", () => {
    expect(getInvoiceDescriptionLength("  coffee  ")).toBe(6);
  });
});
