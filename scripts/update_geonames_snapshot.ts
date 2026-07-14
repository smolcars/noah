import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const CITIES_URL = "https://download.geonames.org/export/dump/cities15000.zip";
const ADMIN1_URL =
  "https://download.geonames.org/export/dump/admin1CodesASCII.txt";
const COUNTRIES_URL =
  "https://download.geonames.org/export/dump/countryInfo.txt";
const MAX_SNAPSHOT_BYTES = 5 * 1024 * 1024;

type CityTuple = [
  id: number,
  name: string,
  asciiName: string,
  lat: number,
  lon: number,
  countryCode: string,
  admin1Code: string,
  population: number,
];

type GeoNamesSnapshot = {
  schemaVersion: 1;
  generatedAt: string;
  source: {
    cities: string;
    admin1: string;
    countries: string;
  };
  license: "CC BY 4.0";
  countries: Record<string, string>;
  admin1: Record<string, string>;
  cities: CityTuple[];
};

const fetchOrThrow = async (url: string) => {
  const response = await fetch(url, {
    headers: {
      Accept: "*/*",
      "User-Agent":
        "NoahWallet GeoNames snapshot builder (https://noahwallet.io)",
    },
  });
  if (!response.ok) {
    throw new Error(`GeoNames returned HTTP ${response.status} for ${url}.`);
  }
  return response;
};

const extractCities = async (archive: ArrayBuffer) => {
  const temporaryDirectory = fs.mkdtempSync(
    path.join(os.tmpdir(), "noah-geonames-"),
  );
  const archivePath = path.join(temporaryDirectory, "cities15000.zip");
  try {
    fs.writeFileSync(archivePath, Buffer.from(archive));
    const process = Bun.spawn(["unzip", "-p", archivePath, "cities15000.txt"], {
      stdout: "pipe",
      stderr: "pipe",
    });
    const [text, errorOutput, exitCode] = await Promise.all([
      new Response(process.stdout).text(),
      new Response(process.stderr).text(),
      process.exited,
    ]);
    if (exitCode !== 0) {
      throw new Error(
        `Could not extract GeoNames cities: ${errorOutput.trim()}`,
      );
    }
    return text;
  } finally {
    fs.rmSync(temporaryDirectory, { recursive: true, force: true });
  }
};

const parseCountries = (text: string) => {
  const countries = new Map<string, string>();
  for (const line of text.split("\n")) {
    if (!line || line.startsWith("#")) {
      continue;
    }
    const fields = line.split("\t");
    const code = fields[0]?.trim();
    const name = fields[4]?.trim();
    if (code && name) {
      countries.set(code, name);
    }
  }
  return countries;
};

const parseAdmin1 = (text: string) => {
  const regions = new Map<string, string>();
  for (const line of text.split("\n")) {
    if (!line) {
      continue;
    }
    const fields = line.split("\t");
    const code = fields[0]?.trim();
    const name = fields[1]?.trim();
    if (code && name) {
      regions.set(code, name);
    }
  }
  return regions;
};

const parseCities = (text: string) => {
  const cities: CityTuple[] = [];
  for (const [index, line] of text.split("\n").entries()) {
    if (!line) {
      continue;
    }
    const fields = line.split("\t");
    const id = Number(fields[0]);
    const name = fields[1]?.trim();
    const asciiName = fields[2]?.trim();
    const lat = Number(fields[4]);
    const lon = Number(fields[5]);
    const countryCode = fields[8]?.trim();
    const admin1Code = fields[10]?.trim() ?? "";
    const population = Number(fields[14]);
    if (
      !Number.isInteger(id) ||
      !name ||
      !asciiName ||
      !Number.isFinite(lat) ||
      lat < -90 ||
      lat > 90 ||
      !Number.isFinite(lon) ||
      lon < -180 ||
      lon > 180 ||
      !countryCode ||
      !Number.isFinite(population) ||
      population < 0
    ) {
      throw new Error(`Invalid GeoNames city at line ${index + 1}.`);
    }
    cities.push([
      id,
      name,
      asciiName === name ? "" : asciiName,
      lat,
      lon,
      countryCode,
      admin1Code,
      population,
    ]);
  }
  return cities.sort((left, right) => left[0] - right[0]);
};

const toSortedRecord = (entries: Iterable<readonly [string, string]>) =>
  Object.fromEntries(
    [...entries].sort(([left], [right]) => left.localeCompare(right)),
  );

const updateSnapshot = async () => {
  const generatedAt = new Date().toISOString();
  const [citiesResponse, admin1Response, countriesResponse] = await Promise.all(
    [
      fetchOrThrow(CITIES_URL),
      fetchOrThrow(ADMIN1_URL),
      fetchOrThrow(COUNTRIES_URL),
    ],
  );
  const [citiesText, admin1Text, countriesText] = await Promise.all([
    extractCities(await citiesResponse.arrayBuffer()),
    admin1Response.text(),
    countriesResponse.text(),
  ]);

  const allCountries = parseCountries(countriesText);
  const allRegions = parseAdmin1(admin1Text);
  const cities = parseCities(citiesText);
  if (cities.length < 25_000) {
    throw new Error(
      `Refusing to write suspicious GeoNames snapshot with ${cities.length} cities.`,
    );
  }
  if (new Set(cities.map((city) => city[0])).size !== cities.length) {
    throw new Error("GeoNames snapshot contains duplicate city IDs.");
  }
  const atlanta = cities.find(
    (city) => city[1] === "Atlanta" && city[5] === "US" && city[6] === "GA",
  );
  if (
    !atlanta ||
    Math.abs(atlanta[3] - 33.749) > 0.25 ||
    Math.abs(atlanta[4] + 84.388) > 0.25
  ) {
    throw new Error(
      "GeoNames snapshot is missing the expected Atlanta, Georgia record.",
    );
  }

  const usedCountryCodes = new Set(cities.map((city) => city[5]));
  const usedRegionCodes = new Set(
    cities.filter((city) => city[6]).map((city) => `${city[5]}.${city[6]}`),
  );
  const countries = toSortedRecord(
    [...usedCountryCodes].map((code) => {
      const name = allCountries.get(code);
      if (!name) {
        throw new Error(`GeoNames country ${code} has no display name.`);
      }
      return [code, name] as const;
    }),
  );
  const admin1 = toSortedRecord(
    [...usedRegionCodes].flatMap((code) => {
      const name = allRegions.get(code);
      return name ? ([[code, name]] as const) : [];
    }),
  );

  const snapshot: GeoNamesSnapshot = {
    schemaVersion: 1,
    generatedAt,
    source: {
      cities: CITIES_URL,
      admin1: ADMIN1_URL,
      countries: COUNTRIES_URL,
    },
    license: "CC BY 4.0",
    countries,
    admin1,
    cities,
  };
  const serialized = JSON.stringify(snapshot);
  if (Buffer.byteLength(serialized) > MAX_SNAPSHOT_BYTES) {
    throw new Error(
      `Refusing to write ${Math.ceil(Buffer.byteLength(serialized) / 1024 / 1024)} MiB GeoNames snapshot.`,
    );
  }

  const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
  const outputDirectory = path.resolve(
    scriptDirectory,
    "../client/assets/geonames",
  );
  const outputPath = path.join(outputDirectory, "cities.geonames");
  fs.mkdirSync(outputDirectory, { recursive: true });
  fs.writeFileSync(outputPath, serialized, "utf8");

  const sizeMiB = fs.statSync(outputPath).size / 1024 / 1024;
  console.log(
    `Wrote ${cities.length} GeoNames cities to ${outputPath} (${sizeMiB.toFixed(2)} MiB).`,
  );
};

await updateSnapshot();
