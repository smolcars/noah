import { describe, expect, mock, test } from "bun:test";

globalThis.__DEV__ = false;

mock.module("noah-tools", () => ({
  getAppVariant: () => "mainnet",
  isGooglePlayServicesAvailable: () => true,
  nativeLog: () => {},
}));
mock.module("react-native", () => ({
  Platform: { OS: "ios" },
}));
mock.module("react-native-fs-turbo", () => ({
  default: {
    CachesDirectoryPath: "/tmp",
    DocumentDirectoryPath: "/tmp",
  },
}));
mock.module("expo-device", () => ({
  isDevice: true,
}));

const { isValidDestination, parseDestination } = await import("../../src/lib/sendUtils");

// Fixture from LDK's BOLT12 parser tests. BOLT12 uses Bech32 encoding without a checksum.
const BOLT12_OFFER =
  "lno1pqps7sjqpgtyzm3qv4uxzmtsd3jjqer9wd3hy6tsw35k7msjzfpy7nz5yqcnygrfdej82um5wf5k2uckyypwa3eyt44h6txtxquqh7lz5djge4afgfjn7k4rgrkuag0jsd5xvxg";

describe("BOLT12 send destinations", () => {
  test("accepts a bare BOLT12 offer", () => {
    expect(isValidDestination(BOLT12_OFFER)).toBe(true);
    expect(parseDestination(BOLT12_OFFER)).toEqual({
      destinationType: "offer",
      isAmountEditable: true,
    });
  });

  test("accepts lightning-prefixed and uppercase offers", () => {
    expect(parseDestination(`lightning:${BOLT12_OFFER}`)).toEqual({
      destinationType: "offer",
      isAmountEditable: true,
    });
    expect(parseDestination(BOLT12_OFFER.toUpperCase())).toEqual({
      destinationType: "offer",
      isAmountEditable: true,
    });
  });

  test("accepts BOLT12 continuation markers", () => {
    const continuedOffer = `${BOLT12_OFFER.slice(0, 40)}+ \n  ${BOLT12_OFFER.slice(40)}`;

    expect(isValidDestination(continuedOffer)).toBe(true);
    expect(parseDestination(continuedOffer)).toEqual({
      destinationType: "offer",
      isAmountEditable: true,
    });
  });

  test("extracts a real offer from a BIP-321 URI", () => {
    const uri = `bitcoin:?lno=${BOLT12_OFFER}`;

    expect(isValidDestination(uri)).toBe(true);
    expect(parseDestination(uri)).toEqual({
      destinationType: "bip321",
      isAmountEditable: true,
      bip321: {
        offer: BOLT12_OFFER,
      },
    });
  });

  test("rejects malformed offer encodings", () => {
    const mixedCaseOffer = `${BOLT12_OFFER.slice(0, 10).toUpperCase()}${BOLT12_OFFER.slice(10)}`;

    expect(isValidDestination("lno1")).toBe(false);
    expect(isValidDestination("lno1not-an-offer")).toBe(false);
    expect(isValidDestination(mixedCaseOffer)).toBe(false);
    expect(isValidDestination("bitcoin:?lno=lno1not-an-offer")).toBe(false);
    expect(parseDestination("bitcoin:?lno=lno1not-an-offer").destinationType).toBeNull();
  });
});
