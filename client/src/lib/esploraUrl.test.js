import { describe, expect, test } from "bun:test";
import {
  getEsploraTipHeightUrl,
  normalizeEsploraEndpoint,
  parseEsploraTipHeight,
} from "./esploraUrl";

describe("normalizeEsploraEndpoint", () => {
  test("normalizes a bare host and trailing slash", () => {
    expect(normalizeEsploraEndpoint(" mempool.space/api/ ")._unsafeUnwrap()).toBe(
      "https://mempool.space/api",
    );
  });

  test("accepts a tip URL and stores its Esplora base URL", () => {
    expect(
      normalizeEsploraEndpoint("https://mempool.space/api/blocks/tip/height")._unsafeUnwrap(),
    ).toBe("https://mempool.space/api");
    expect(
      normalizeEsploraEndpoint("https://mempool.space/api/blocks/tip/hash")._unsafeUnwrap(),
    ).toBe("https://mempool.space/api");
  });

  test("rejects credentials and query strings", () => {
    expect(normalizeEsploraEndpoint("https://user:pass@example.com/api").isErr()).toBe(true);
    expect(normalizeEsploraEndpoint("https://example.com/api?token=secret").isErr()).toBe(true);
  });
});

describe("Esplora tip height", () => {
  test("builds the probe URL", () => {
    expect(getEsploraTipHeightUrl("https://mempool.space/api/")).toBe(
      "https://mempool.space/api/blocks/tip/height",
    );
  });

  test("parses an integer height and rejects other responses", () => {
    expect(parseEsploraTipHeight(" 900000\n")._unsafeUnwrap()).toBe(900000);
    expect(parseEsploraTipHeight("not-a-height").isErr()).toBe(true);
    expect(parseEsploraTipHeight("1.5").isErr()).toBe(true);
  });
});
