import { describe, expect, test } from "bun:test";

import {
  distanceKm,
  formatDistance,
  getPlaceCategory,
} from "../../src/lib/btcMapUtils";

describe("BTC Map categories", () => {
  test("groups common BTC Map icons", () => {
    expect(getPlaceCategory("restaurant")).toBe("food");
    expect(getPlaceCategory("local_bar")).toBe("food");
    expect(getPlaceCategory("local_grocery_store")).toBe("shop");
    expect(getPlaceCategory("hotel")).toBe("stay");
    expect(getPlaceCategory("local_atm")).toBe("atm");
    expect(getPlaceCategory("medical_services")).toBe("services");
  });
});

describe("BTC Map distance", () => {
  test("calculates and formats nearby distances", () => {
    const distance = distanceKm(
      { latitude: 40.7128, longitude: -74.006 },
      { lat: 40.73061, lon: -73.935242 },
    );

    expect(distance).toBeGreaterThan(6);
    expect(distance).toBeLessThan(7);
    expect(formatDistance(distance)).toBe(`${distance.toFixed(1)} km`);
    expect(formatDistance(0.25)).toBe("250 m");
  });
});

describe("bundled BTC Map snapshot", () => {
  test("contains a valid, deterministic global place set", async () => {
    const path = new URL("../../assets/btcmap/places.btcmap", import.meta.url);
    const snapshot = JSON.parse(await Bun.file(path).text());

    expect(snapshot.schemaVersion).toBe(1);
    expect(snapshot.source).toBe("https://api.btcmap.org/v4/places");
    expect(snapshot.places.length).toBeGreaterThan(10_000);
    expect(snapshot.places.every((place) => Number.isInteger(place.id))).toBe(true);
    expect(snapshot.places.every((place) => place.lat >= -90 && place.lat <= 90)).toBe(true);
    expect(snapshot.places.every((place) => place.lon >= -180 && place.lon <= 180)).toBe(true);

    const ids = snapshot.places.map((place) => place.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual([...ids].sort((left, right) => left - right));
  });
});
