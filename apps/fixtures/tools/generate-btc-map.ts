// bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-btc-map.ts
import { mock } from "bun:test";
import { writeFileSync } from "node:fs";
import { distanceKm, formatDistance, getPlaceCategory, parseBtcMapViewport } from "../../../client/src/lib/btcMapUtils";
import { cityDisplayLabel, citySecondaryLabel, normalizeSearchText, parseGeoNamesSnapshot, searchCities } from "../../../client/src/lib/citySearch";
mock.module("expo-asset", () => ({ Asset: {} }));
mock.module("react-native-fs-turbo", () => ({ default: { DocumentDirectoryPath: "/unused" } }));
mock.module("../../../client/assets/btcmap/places.btcmap", () => ({ default: 0 }));
const { parseBtcMapSnapshot, mergeBtcMapPatches } = await import("../../../client/src/lib/btcMap");
const bundled = await Bun.file(new URL("../../../client/assets/btcmap/places.btcmap", import.meta.url)).json();
const geo: { schemaVersion: number; license: string; cities: [number, string, string, number, number, string, string, number][] } = await Bun.file(new URL("../../../client/assets/geonames/cities.geonames", import.meta.url)).json();
const parsed = parseGeoNamesSnapshot(geo);
if (parsed.isErr()) throw parsed.error;
type Case = { name: string; input: object; expected: unknown };
const btc: Case[] = [];
for (const epochMilliseconds of [1800000000000, 1800000000123]) btc.push({ name: `UTC sync cursor ${epochMilliseconds}`, input: { operation: "syncTimestamp", epochMilliseconds }, expected: new Date(epochMilliseconds).toISOString() });
for (const icon of ["restaurant", "local_bar", "local_grocery_store", "hotel", "local_atm", "medical_services", "currency_exchange", "attach_money", "bakery_dining", "camp", "unknown"]) {
  btc.push({ name: `Category ${icon}`, input: { operation: "category", icon }, expected: getPlaceCategory(icon) });
}
const from = { latitude: 40.7128, longitude: -74.006 };
const to = { lat: 40.73061, lon: -73.935242 };
btc.push({ name: "New York distance", input: { operation: "distance", from: [from.longitude, from.latitude], to: [to.lon, to.lat] }, expected: distanceKm(from, to) });
for (const km of [distanceKm(from, to), 1, 16, 0.25, 0, 0.9999]) {
  btc.push({ name: `Format ${km} km`, input: { operation: "format", km }, expected: formatDistance(km, "en-US") });
}
for (const value of [{ center: [-73.9857, 40.7484], zoom: 14.5 }, { center: [-181, 40], zoom: 12 }, { center: [-73, 91], zoom: 12 }, { center: [-73, 40], zoom: 20 }, { center: [0], zoom: 12 }, null, { center: ["0", 0], zoom: 12 }, { center: [180, -90], zoom: 1 }, { center: [0, 0], zoom: 19 }]) {
  btc.push({ name: `Viewport ${JSON.stringify(value)}`, input: { operation: "viewport", value }, expected: parseBtcMapViewport(value) ?? null });
}
const sample = { schemaVersion: 1 as const, source: bundled.source, generatedAt: "2026-01-01T00:00:00.000Z", places: bundled.places.slice(0, 3) };
for (const value of [sample, { ...sample, schemaVersion: 2 }, { ...sample, source: "" }, { ...sample, places: [{ ...sample.places[0], lat: "0" }] }, { ...sample, places: [{ ...sample.places[0], name: "" }] }]) {
  const result = parseBtcMapSnapshot(value);
  btc.push({ name: `Snapshot validation ${btc.length}`, input: { operation: "parse", value }, expected: result.isOk() ? result.value : null });
}
const patches = [{ id: sample.places[0].id, deleted_at: "2026-02-01" }, { ...sample.places[1], name: "Updated merchant" }, { ...sample.places[2], id: 99999999, name: "New merchant" }];
btc.push({ name: "Patch deletes, replaces and adds sorted places", input: { operation: "merge", snapshot: sample, patches, generatedAt: "2026-02-01T00:00:00.000Z" }, expected: mergeBtcMapPatches(sample, patches, "2026-02-01T00:00:00.000Z") });
const stats = (ids: number[], coords: number[][]) => ({ count: ids.length, unique: new Set(ids).size === ids.length, sorted: JSON.stringify(ids) === JSON.stringify([...ids].sort((a,b) => a-b)), bounded: coords.every(([lon, lat]) => lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90) });
btc.push({ name: "Bundled global merchant set", input: { operation: "bundle" }, expected: { ...stats(bundled.places.map((p: {id: number}) => p.id), bundled.places.map((p: {lon: number; lat: number}) => [p.lon,p.lat])), source: bundled.source, schemaVersion: bundled.schemaVersion } });
const city: Case[] = [];
for (const value of ["  São-Paulo, BR ", "München", "東京", "Ａｔｌａｎｔａ", "á"]) city.push({ name: `Normalize ${value}`, input: { operation: "normalize", value }, expected: normalizeSearchText(value) });
for (const query of ["atl", "sao paulo", "atl ga", "london", "san", "東京", "a", "zzzzzz"]) {
  for (const bias of [undefined, [-84.39,33.75] as [number, number]]) {
    const results = searchCities(parsed.value.cities, query, 3, bias);
    city.push({ name: `Search ${query}${bias ? " near Atlanta" : ""}`, input: { operation: "search", query, limit: 3, ...(bias ? { bias } : {}) }, expected: results.map(c => ({ id: c.id, name: c.name, countryCode: c.countryCode, displayLabel: cityDisplayLabel(c), secondaryLabel: citySecondaryLabel(c), center: c.center })) });
  }
}
const small = { schemaVersion: 1, generatedAt: "2026-01-01T00:00:00.000Z", license: "CC BY 4.0", countries: { US: "United States" }, admin1: { "US.GA": "Georgia" }, cities: [[1,"Atlanta"]] };
for (const value of [small, { ...small, cities: [[1,"Atlanta","Atlanta",91,0,"US","GA",1]] }, { ...small, cities: [[1,"Atlanta","Atlanta",33,-84,"US","GA",1]] }]) city.push({ name: `City validation ${city.length}`, input: { operation: "parse", value }, expected: parseGeoNamesSnapshot(value).isOk() });
city.push({ name: "Bundled global city set", input: { operation: "bundle" }, expected: { ...stats(geo.cities.map(c => c[0]), geo.cities.map(c => [c[4],c[3]])), schemaVersion: geo.schemaVersion, license: geo.license, under5MiB: Bun.file(new URL("../../../client/assets/geonames/cities.geonames", import.meta.url)).size < 5 * 1024 * 1024, hasAtlanta: geo.cities.some(c => c[1] === "Atlanta" && c[5] === "US" && c[6] === "GA") } });
for (const [name, cases] of [["btc-map",btc],["city-search",city]] as const) writeFileSync(new URL(`../${name}.json`, import.meta.url), JSON.stringify({ description: "Generated by tools/generate-btc-map.ts by executing RN TypeScript; ports btcMap.test.js and citySearch.test.js with additional validation/ranking cases.", cases }, null, 2) + "\n");
