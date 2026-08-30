import { describe, expect, test } from "bun:test";

const { buildReceiveRequestUri } = await import("../../src/lib/receiveRequest");

const ONCHAIN_ADDRESS = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
const ARK_ADDRESS =
  "ark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqjl5wpy";
const LIGHTNING_INVOICE =
  "lnbc15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvtgjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsq4gj5hs";

describe("receive request", () => {
  test("builds an amountless request with Ark and on-chain payment methods", () => {
    expect(
      buildReceiveRequestUri({
        amountSat: null,
        arkAddress: ARK_ADDRESS,
        onchainAddress: ONCHAIN_ADDRESS,
      }),
    ).toBe(`bitcoin:${ONCHAIN_ADDRESS}?ark=${ARK_ADDRESS}`);
  });

  test("builds an amountful request with Lightning and an exact sats amount", () => {
    expect(
      buildReceiveRequestUri({
        amountSat: 500,
        arkAddress: ARK_ADDRESS,
        lightningInvoice: LIGHTNING_INVOICE,
        onchainAddress: ONCHAIN_ADDRESS,
      }),
    ).toBe(
      `bitcoin:${ONCHAIN_ADDRESS}?amount=0.000005&lightning=${LIGHTNING_INVOICE}&ark=${ARK_ADDRESS}`,
    );
  });

  test("rejects a zero-sat Lightning request", () => {
    expect(() =>
      buildReceiveRequestUri({
        amountSat: 0,
        arkAddress: ARK_ADDRESS,
        lightningInvoice: LIGHTNING_INVOICE,
        onchainAddress: ONCHAIN_ADDRESS,
      }),
    ).toThrow("Receive amount must be a positive whole number of sats");
  });
});
