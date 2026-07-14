import { err, ok, Result } from "neverthrow";

export type CitySearchEntry = {
  id: number;
  name: string;
  asciiName: string;
  center: [longitude: number, latitude: number];
  countryCode: string;
  countryName: string;
  admin1Code: string;
  regionName: string | undefined;
  population: number;
  normalizedNames: string[];
  searchTokens: string[];
  searchText: string;
};

export type CitySearchIndex = {
  generatedAt: string;
  license: "CC BY 4.0";
  cities: CitySearchEntry[];
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const parseStringRecord = (value: unknown): Record<string, string> | undefined => {
  if (!isRecord(value)) {
    return undefined;
  }
  const entries = Object.entries(value);
  if (entries.some(([key, item]) => key.length === 0 || typeof item !== "string" || !item)) {
    return undefined;
  }
  return Object.fromEntries(entries) as Record<string, string>;
};

export const normalizeSearchText = (value: string) =>
  value
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLocaleLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ");

export const parseGeoNamesSnapshot = (value: unknown): Result<CitySearchIndex, Error> => {
  if (
    !isRecord(value) ||
    value.schemaVersion !== 1 ||
    value.license !== "CC BY 4.0" ||
    typeof value.generatedAt !== "string" ||
    !value.generatedAt ||
    !Array.isArray(value.cities)
  ) {
    return err(new Error("Unsupported GeoNames city snapshot."));
  }

  const countries = parseStringRecord(value.countries);
  const regions = parseStringRecord(value.admin1);
  if (!countries || !regions) {
    return err(new Error("GeoNames city snapshot is missing place names."));
  }

  const cities: CitySearchEntry[] = [];
  for (const rawCity of value.cities) {
    if (!Array.isArray(rawCity) || rawCity.length !== 8) {
      return err(new Error("GeoNames city has an invalid shape."));
    }
    const [id, name, asciiName, lat, lon, countryCode, admin1Code, population] = rawCity;
    const countryName = typeof countryCode === "string" ? countries[countryCode] : undefined;
    if (
      typeof id !== "number" ||
      !Number.isInteger(id) ||
      typeof name !== "string" ||
      !name ||
      typeof asciiName !== "string" ||
      typeof lat !== "number" ||
      !Number.isFinite(lat) ||
      lat < -90 ||
      lat > 90 ||
      typeof lon !== "number" ||
      !Number.isFinite(lon) ||
      lon < -180 ||
      lon > 180 ||
      typeof countryCode !== "string" ||
      !countryName ||
      typeof admin1Code !== "string" ||
      typeof population !== "number" ||
      !Number.isInteger(population) ||
      population < 0
    ) {
      return err(new Error("GeoNames city is missing required search fields."));
    }

    const regionName = admin1Code ? regions[`${countryCode}.${admin1Code}`] : undefined;
    const normalizedNames = [...new Set([name, asciiName || name].map(normalizeSearchText))];
    const searchText = normalizeSearchText(
      [name, asciiName, regionName, admin1Code, countryName, countryCode]
        .filter((item): item is string => Boolean(item))
        .join(" "),
    );
    cities.push({
      id,
      name,
      asciiName: asciiName || name,
      center: [lon, lat],
      countryCode,
      countryName,
      admin1Code,
      regionName,
      population,
      normalizedNames,
      searchTokens: searchText.split(" "),
      searchText,
    });
  }

  return ok({ generatedAt: value.generatedAt, license: "CC BY 4.0", cities });
};

const matchScore = (city: CitySearchEntry, query: string) => {
  if (city.normalizedNames.some((name) => name === query)) {
    return 0;
  }
  if (city.normalizedNames.some((name) => name.startsWith(query))) {
    return 1;
  }
  const queryTokens = query.split(" ");
  if (queryTokens.every((token) => city.searchTokens.some((item) => item.startsWith(token)))) {
    return 2;
  }
  return city.searchText.includes(query) ? 3 : undefined;
};

const distanceKm = (
  [fromLongitude, fromLatitude]: [longitude: number, latitude: number],
  [toLongitude, toLatitude]: [longitude: number, latitude: number],
) => {
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180;
  const latitudeDelta = toRadians(toLatitude - fromLatitude);
  const longitudeDelta = toRadians(toLongitude - fromLongitude);
  const fromLatitudeRadians = toRadians(fromLatitude);
  const toLatitudeRadians = toRadians(toLatitude);
  const haversine =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(fromLatitudeRadians) * Math.cos(toLatitudeRadians) * Math.sin(longitudeDelta / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
};

export const searchCities = (
  cities: ReadonlyArray<CitySearchEntry>,
  rawQuery: string,
  limit = 3,
  biasCenter?: [longitude: number, latitude: number],
) => {
  const query = normalizeSearchText(rawQuery);
  if (query.length < 2 || limit <= 0) {
    return [];
  }

  return cities
    .flatMap((city) => {
      const score = matchScore(city, query);
      return score === undefined
        ? []
        : [{ city, score, distance: biasCenter ? distanceKm(biasCenter, city.center) : 0 }];
    })
    .sort(
      (left, right) =>
        left.score - right.score ||
        (biasCenter ? left.distance - right.distance : 0) ||
        right.city.population - left.city.population ||
        left.city.name.localeCompare(right.city.name),
    )
    .slice(0, limit)
    .map(({ city }) => city);
};

export const citySecondaryLabel = (city: CitySearchEntry) =>
  [city.regionName, city.countryName].filter(Boolean).join(", ");

export const cityDisplayLabel = (city: CitySearchEntry) => {
  const region = city.countryCode === "US" ? city.admin1Code : city.regionName;
  return [city.name, region || city.countryName].filter(Boolean).join(", ");
};
