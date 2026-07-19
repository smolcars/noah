export const MAX_INVOICE_DESCRIPTION_BYTES = 639;

export const normalizeInvoiceDescription = (description?: string) =>
  description?.trim() || undefined;

const utf8ByteLength = (value: string) => {
  let byteLength = 0;

  for (const character of value) {
    const codePoint = character.codePointAt(0)!;
    byteLength += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
  }

  return byteLength;
};

export const getInvoiceDescriptionByteLength = (description?: string) =>
  utf8ByteLength(normalizeInvoiceDescription(description) ?? "");

export const isInvoiceDescriptionValid = (description?: string) =>
  getInvoiceDescriptionByteLength(description) <= MAX_INVOICE_DESCRIPTION_BYTES;
