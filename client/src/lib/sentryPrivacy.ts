const FILTERED_VALUE = "[Filtered]";
const SENSITIVE_LNURL_QUERY_KEYS = new Set(["payerdata", "comment"]);

const isSensitiveLnurlQueryKey = (key: string): boolean =>
  SENSITIVE_LNURL_QUERY_KEYS.has(key.toLowerCase());

const redactSearchParams = (params: URLSearchParams): boolean => {
  const sensitiveKeys = new Set<string>();
  params.forEach((_value, key) => {
    if (isSensitiveLnurlQueryKey(key)) {
      sensitiveKeys.add(key);
    }
  });

  sensitiveKeys.forEach((key) => params.set(key, FILTERED_VALUE));
  return sensitiveKeys.size > 0;
};

export const redactSensitiveLnurlUrl = (value: unknown): unknown => {
  if (typeof value !== "string") {
    return value;
  }

  try {
    const url = new URL(value);
    return redactSearchParams(url.searchParams) ? url.toString() : value;
  } catch {
    return value;
  }
};

export const redactSensitiveLnurlQuery = (value: unknown): unknown => {
  if (typeof value === "string") {
    const hasQuestionMark = value.startsWith("?");
    const params = new URLSearchParams(hasQuestionMark ? value.slice(1) : value);
    if (!redactSearchParams(params)) {
      return value;
    }
    return `${hasQuestionMark ? "?" : ""}${params.toString()}`;
  }

  if (Array.isArray(value)) {
    let changed = false;
    const redacted = value.map((entry) => {
      if (
        Array.isArray(entry) &&
        entry.length === 2 &&
        typeof entry[0] === "string" &&
        isSensitiveLnurlQueryKey(entry[0])
      ) {
        changed = true;
        return [entry[0], FILTERED_VALUE];
      }
      return entry;
    });
    return changed ? redacted : value;
  }

  if (typeof value === "object" && value !== null) {
    let changed = false;
    const redacted = Object.fromEntries(
      Object.entries(value).map(([key, entryValue]) => {
        if (isSensitiveLnurlQueryKey(key)) {
          changed = true;
          return [key, FILTERED_VALUE];
        }
        return [key, entryValue];
      }),
    );
    return changed ? redacted : value;
  }

  return value;
};

export const redactSentryBreadcrumbData = (
  data: Record<string, unknown> | undefined,
): Record<string, unknown> | undefined => {
  if (!data) {
    return data;
  }

  const url = redactSensitiveLnurlUrl(data.url);
  const query = redactSensitiveLnurlQuery(data["http.query"]);
  if (url === data.url && query === data["http.query"]) {
    return data;
  }

  return {
    ...data,
    ...(url !== data.url ? { url } : {}),
    ...(query !== data["http.query"] ? { "http.query": query } : {}),
  };
};

type SentryRequestData = {
  url?: string;
  query_string?: unknown;
};

export const redactSentryRequestData = <T extends SentryRequestData>(
  request: T | undefined,
): T | undefined => {
  if (!request) {
    return request;
  }

  const url = redactSensitiveLnurlUrl(request.url);
  const query = redactSensitiveLnurlQuery(request.query_string);
  if (url === request.url && query === request.query_string) {
    return request;
  }

  return {
    ...request,
    ...(url !== request.url ? { url } : {}),
    ...(query !== request.query_string ? { query_string: query } : {}),
  } as T;
};
