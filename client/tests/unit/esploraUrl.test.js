import { describe, expect, test } from "bun:test";
import {
  getEsploraGenesisHashUrl,
  getEsploraTipHeightUrl,
  normalizeEsploraEndpoint,
  parseEsploraBlockHash,
  validateEsploraGenesisHash,
} from "../../src/lib/esploraUrl";

const MAINNET_GENESIS_HASH =
  "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f";
const SIGNET_GENESIS_HASH =
  "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6";

describe("normalizeEsploraEndpoint", () => {
  test("normalizes a bare host and trailing slash", () => {
    expect(normalizeEsploraEndpoint(" mempool.space/api/ ")._unsafeUnwrap()).toBe(
      "https://mempool.space/api",
    );
  });

  test("accepts supported Esplora URLs and stores their base URL", () => {
    expect(
      normalizeEsploraEndpoint("https://mempool.space/api/blocks/tip/height")._unsafeUnwrap(),
    ).toBe("https://mempool.space/api");
    expect(
      normalizeEsploraEndpoint("https://mempool.space/api/blocks/tip/hash")._unsafeUnwrap(),
    ).toBe("https://mempool.space/api");
    expect(
      normalizeEsploraEndpoint("https://mempool.space/api/block-height/0")._unsafeUnwrap(),
    ).toBe("https://mempool.space/api");
  });

  test("rejects credentials and query strings", () => {
    expect(normalizeEsploraEndpoint("https://user:pass@example.com/api").isErr()).toBe(true);
    expect(normalizeEsploraEndpoint("https://example.com/api?token=secret").isErr()).toBe(true);
  });
});

describe("Esplora URLs", () => {
  test("builds tip-height and genesis-hash URLs", () => {
    expect(getEsploraTipHeightUrl("https://mempool.space/api/")).toBe(
      "https://mempool.space/api/blocks/tip/height",
    );
    expect(getEsploraGenesisHashUrl("https://mempool.space/api/")).toBe(
      "https://mempool.space/api/block-height/0",
    );
  });

  test("parses a block hash and rejects other responses", () => {
    expect(parseEsploraBlockHash(` ${SIGNET_GENESIS_HASH.toUpperCase()}\n`)._unsafeUnwrap()).toBe(
      SIGNET_GENESIS_HASH,
    );
    expect(parseEsploraBlockHash("not-a-hash").isErr()).toBe(true);
    expect(parseEsploraBlockHash("0".repeat(63)).isErr()).toBe(true);
  });

  test("accepts the expected genesis hash and rejects another network", () => {
    expect(validateEsploraGenesisHash(SIGNET_GENESIS_HASH, SIGNET_GENESIS_HASH).isOk()).toBe(true);
    expect(validateEsploraGenesisHash(MAINNET_GENESIS_HASH, SIGNET_GENESIS_HASH).isErr()).toBe(true);
  });
});
