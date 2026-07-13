import { err, ok, type Result } from "neverthrow";

const TIP_ENDPOINT_SUFFIXES = ["/blocks/tip/height", "/blocks/tip/hash"] as const;

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

  const matchedSuffix = TIP_ENDPOINT_SUFFIXES.find((suffix) =>
    url.pathname.replace(/\/+$/, "").endsWith(suffix),
  );
  if (matchedSuffix) {
    url.pathname = url.pathname.replace(/\/+$/, "").slice(0, -matchedSuffix.length) || "/";
  }

  return ok(url.toString().replace(/\/+$/, ""));
};

export const getEsploraTipHeightUrl = (endpoint: string): string =>
  `${endpoint.replace(/\/+$/, "")}/blocks/tip/height`;

export const parseEsploraTipHeight = (value: string): Result<number, Error> => {
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) {
    return err(new Error("The endpoint returned an invalid block height."));
  }

  const height = Number(trimmed);
  if (!Number.isSafeInteger(height)) {
    return err(new Error("The endpoint returned an invalid block height."));
  }

  return ok(height);
};
