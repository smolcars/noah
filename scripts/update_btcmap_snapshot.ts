import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const API_URL = "https://api.btcmap.org/v4/places";
const FIELDS = [
  "id",
  "lat",
  "lon",
  "icon",
  "name",
  "comments",
  "boosted_until",
  "verified_at",
  "updated_at",
] as const;

type SnapshotPlace = {
  id: number;
  lat: number;
  lon: number;
  icon: string;
  name: string;
  comments?: number;
  boosted_until?: string;
  verified_at?: string;
  updated_at: string;
};

type Snapshot = {
  schemaVersion: 1;
  generatedAt: string;
  source: string;
  places: SnapshotPlace[];
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const optionalString = (value: unknown): string | undefined =>
  typeof value === "string" && value.length > 0 ? value : undefined;

const optionalNonNegativeInteger = (value: unknown): number | undefined =>
  typeof value === "number" && Number.isInteger(value) && value >= 0
    ? value
    : undefined;

const parsePlace = (value: unknown, index: number): SnapshotPlace => {
  if (!isRecord(value)) {
    throw new Error(`Place at index ${index} is not an object.`);
  }

  const { id, lat, lon, icon, name, updated_at: updatedAt } = value;
  const comments = optionalNonNegativeInteger(value.comments);
  const boostedUntil = optionalString(value.boosted_until);
  const verifiedAt = optionalString(value.verified_at);
  if (
    typeof id !== "number" ||
    !Number.isInteger(id) ||
    typeof lat !== "number" ||
    !Number.isFinite(lat) ||
    typeof lon !== "number" ||
    !Number.isFinite(lon) ||
    typeof icon !== "string" ||
    icon.length === 0 ||
    typeof name !== "string" ||
    name.length === 0 ||
    typeof updatedAt !== "string" ||
    Number.isNaN(Date.parse(updatedAt))
  ) {
    throw new Error(`Place at index ${index} is missing a required field.`);
  }

  return {
    id,
    lat,
    lon,
    icon,
    name,
    updated_at: updatedAt,
    ...(comments !== undefined ? { comments } : {}),
    ...(boostedUntil ? { boosted_until: boostedUntil } : {}),
    ...(verifiedAt ? { verified_at: verifiedAt } : {}),
  };
};

const updateSnapshot = async () => {
  const generatedAt = new Date().toISOString();
  const url = new URL(API_URL);
  url.searchParams.set("fields", FIELDS.join(","));

  const response = await fetch(url, {
    headers: {
      Accept: "application/json",
      "User-Agent":
        "NoahWallet BTC Map snapshot builder (https://noahwallet.io)",
    },
  });

  if (!response.ok) {
    throw new Error(`BTC Map returned HTTP ${response.status}.`);
  }

  const body: unknown = await response.json();
  if (!Array.isArray(body)) {
    throw new Error("BTC Map returned a non-array response.");
  }

  const places = body.map(parsePlace).sort((left, right) => left.id - right.id);
  if (places.length < 10_000) {
    throw new Error(
      `Refusing to write suspicious snapshot with only ${places.length} places.`,
    );
  }

  const ids = new Set(places.map((place) => place.id));
  if (ids.size !== places.length) {
    throw new Error("BTC Map snapshot contains duplicate place IDs.");
  }

  const snapshot: Snapshot = {
    schemaVersion: 1,
    generatedAt,
    source: API_URL,
    places,
  };

  const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
  const outputDirectory = path.resolve(
    scriptDirectory,
    "../client/assets/btcmap",
  );
  const outputPath = path.join(outputDirectory, "places.btcmap");
  fs.mkdirSync(outputDirectory, { recursive: true });
  fs.writeFileSync(outputPath, JSON.stringify(snapshot), "utf8");

  const sizeMiB = fs.statSync(outputPath).size / 1024 / 1024;
  console.log(
    `Wrote ${places.length} BTC Map places to ${outputPath} (${sizeMiB.toFixed(2)} MiB).`,
  );
};

await updateSnapshot();
