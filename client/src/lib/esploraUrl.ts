import { err, ok, type Result } from "neverthrow";

const ESPLORA_ENDPOINT_SUFFIXES = [
  "/blocks/tip/height",
  "/blocks/tip/hash",
  "/block-height/0",
] as const;

export const normalizeEsploraEndpoint = (value: string): Result<string, Error> => {
  const trimmed = value.trim();
  if (!trimmed) {
    return err(new Error("Enter an Esplora API endpoint."));
  }

  const withProtocol = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;

  let url: URL;
  try {
    url = new URL(withProtocol);
  } catch {
    return err(new Error("Enter a valid Esplora API URL."));
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    return err(new Error("The Esplora endpoint must use HTTP or HTTPS."));
  }

  if (url.username || url.password) {
    return err(new Error("The Esplora endpoint cannot include credentials."));
  }

  if (url.search || url.hash) {
    return err(new Error("The Esplora endpoint cannot include a query string or fragment."));
  }

  const matchedSuffix = ESPLORA_ENDPOINT_SUFFIXES.find((suffix) =>
    url.pathname.replace(/\/+$/, "").endsWith(suffix),
  );
  if (matchedSuffix) {
    url.pathname = url.pathname.replace(/\/+$/, "").slice(0, -matchedSuffix.length) || "/";
  }

  return ok(url.toString().replace(/\/+$/, ""));
};

export const getEsploraTipHeightUrl = (endpoint: string): string =>
  `${endpoint.replace(/\/+$/, "")}/blocks/tip/height`;

export const getEsploraGenesisHashUrl = (endpoint: string): string =>
  `${endpoint.replace(/\/+$/, "")}/block-height/0`;

export const parseEsploraBlockHash = (value: string): Result<string, Error> => {
  const normalized = value.trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(normalized)) {
    return err(new Error("The endpoint returned an invalid block hash."));
  }

  return ok(normalized);
};

export const validateEsploraGenesisHash = (
  value: string,
  expectedGenesisHash: string,
): Result<void, Error> => {
  const hashResult = parseEsploraBlockHash(value);
  if (hashResult.isErr()) {
    return err(hashResult.error);
  }

  if (hashResult.value !== expectedGenesisHash) {
    return err(new Error("The Esplora endpoint is for a different Bitcoin network."));
  }

  return ok(undefined);
};
