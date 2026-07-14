import { describe, expect, test } from "bun:test";

import {
  cityDisplayLabel,
  normalizeSearchText,
  parseGeoNamesSnapshot,
  searchCities,
} from "../../src/lib/citySearch";

const snapshotPath = new URL("../../assets/geonames/cities.geonames", import.meta.url);
const loadSnapshot = async () => JSON.parse(await Bun.file(snapshotPath).text());

describe("city search", () => {
  test("normalizes accents and punctuation", () => {
    expect(normalizeSearchText("  São-Paulo, BR ")).toBe("sao paulo br");
  });

  test("finds and labels cities from prefixes", async () => {
    const parsed = parseGeoNamesSnapshot(await loadSnapshot());
    expect(parsed.isOk()).toBe(true);
    if (parsed.isErr()) {
      return;
    }

    const atlanta = searchCities(parsed.value.cities, "atl", 3)[0];
    expect(atlanta?.countryCode).toBe("US");
    expect(atlanta && cityDisplayLabel(atlanta)).toBe("Atlanta, GA");

    const saoPaulo = searchCities(parsed.value.cities, "sao paulo", 3)[0];
    expect(saoPaulo?.name).toBe("São Paulo");
  });

  test("rejects malformed city tuples", () => {
    const parsed = parseGeoNamesSnapshot({
      schemaVersion: 1,
      generatedAt: "2026-01-01T00:00:00.000Z",
      license: "CC BY 4.0",
      countries: { US: "United States" },
      admin1: { "US.GA": "Georgia" },
      cities: [[1, "Atlanta"]],
    });
    expect(parsed.isErr()).toBe(true);
  });
});

describe("bundled GeoNames snapshot", () => {
  test("contains a bounded, deterministic global city set", async () => {
    const file = Bun.file(snapshotPath);
    const snapshot = JSON.parse(await file.text());

    expect(file.size).toBeLessThan(5 * 1024 * 1024);
    expect(snapshot.schemaVersion).toBe(1);
    expect(snapshot.license).toBe("CC BY 4.0");
    expect(snapshot.cities.length).toBeGreaterThan(25_000);
    expect(snapshot.cities.every((city) => Number.isInteger(city[0]))).toBe(true);
    expect(snapshot.cities.every((city) => city[3] >= -90 && city[3] <= 90)).toBe(true);
    expect(snapshot.cities.every((city) => city[4] >= -180 && city[4] <= 180)).toBe(true);

    const ids = snapshot.cities.map((city) => city[0]);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual([...ids].sort((left, right) => left - right));
    expect(
      snapshot.cities.some((city) => city[1] === "Atlanta" && city[5] === "US" && city[6] === "GA"),
    ).toBe(true);
  });
});
