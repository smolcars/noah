export const MAX_INVOICE_DESCRIPTION_LENGTH = 639;

export const normalizeInvoiceDescription = (description?: string) =>
  description?.trim() || undefined;

export const getInvoiceDescriptionLength = (description?: string) =>
  normalizeInvoiceDescription(description)?.length ?? 0;

export const isInvoiceDescriptionValid = (description?: string) =>
  getInvoiceDescriptionLength(description) <= MAX_INVOICE_DESCRIPTION_LENGTH;
