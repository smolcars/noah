import { Asset } from "expo-asset";
import { err, ok, Result, ResultAsync } from "neverthrow";
import RNFSTurbo from "react-native-fs-turbo";

import bundledSnapshotAsset from "../../assets/btcmap/places.btcmap";

const API_URL = "https://api.btcmap.org/v4";
const CACHE_PATH = `${RNFSTurbo.DocumentDirectoryPath}/btcmap-places-v1.btcmap`;
const PLACE_FIELDS = [
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
const DETAIL_FIELDS = [
  ...PLACE_FIELDS,
  "address",
  "opening_hours",
  "created_at",
  "osm_id",
  "osm_url",
  "phone",
  "website",
  "twitter",
  "facebook",
  "instagram",
  "telegram",
  "email",
  "required_app_url",
  "description",
  "image",
  "payment_provider",
  "osm:amenity",
  "osm:shop",
  "osm:tourism",
  "osm:cuisine",
  "osm:payment:lightning",
  "osm:payment:lightning_contactless",
  "osm:payment:onchain",
] as const;

export type BtcMapPlace = {
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

export type BtcMapSnapshot = {
  schemaVersion: 1;
  generatedAt: string;
  source: string;
  places: BtcMapPlace[];
};

export type BtcMapComment = {
  id: number;
  text: string;
  created_at: string;
};

export type BtcMapPlaceDetail = BtcMapPlace & {
  address?: string;
  opening_hours?: string;
  created_at?: string;
  osm_id?: string;
  osm_url?: string;
  phone?: string;
  website?: string;
  twitter?: string;
  facebook?: string;
  instagram?: string;
  telegram?: string;
  email?: string;
  required_app_url?: string;
  description?: string;
  image?: string;
  payment_provider?: string;
  "osm:amenity"?: string;
  "osm:shop"?: string;
  "osm:tourism"?: string;
  "osm:cuisine"?: string;
  "osm:payment:lightning"?: string;
  "osm:payment:lightning_contactless"?: string;
  "osm:payment:onchain"?: string;
};

export type BtcMapPlaceWithComments = {
  place: BtcMapPlaceDetail;
  comments: BtcMapComment[];
};

type PlacePatch = Partial<BtcMapPlace> & { id: number; deleted_at?: string };

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const optionalString = (value: unknown): string | undefined =>
  typeof value === "string" && value.length > 0 ? value : undefined;

const optionalNonNegativeInteger = (value: unknown): number | undefined =>
  typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : undefined;

const requiredString = (record: Record<string, unknown>, field: string): Result<string, Error> => {
  const value = optionalString(record[field]);
  return value ? ok(value) : err(new Error(`BTC Map place is missing ${field}.`));
};

const parsePlace = (value: unknown): Result<BtcMapPlace, Error> => {
  if (!isRecord(value)) {
    return err(new Error("BTC Map place is not an object."));
  }

  const { id, lat, lon } = value;
  const icon = requiredString(value, "icon");
  const name = requiredString(value, "name");
  const updatedAt = requiredString(value, "updated_at");
  if (
    typeof id !== "number" ||
    !Number.isInteger(id) ||
    typeof lat !== "number" ||
    !Number.isFinite(lat) ||
    typeof lon !== "number" ||
    !Number.isFinite(lon) ||
    icon.isErr() ||
    name.isErr() ||
    updatedAt.isErr()
  ) {
    return err(new Error("BTC Map place is missing required map fields."));
  }

  const comments = optionalNonNegativeInteger(value.comments);
  const boostedUntil = optionalString(value.boosted_until);
  const verifiedAt = optionalString(value.verified_at);
  return ok({
    id,
    lat,
    lon,
    icon: icon.value,
    name: name.value,
    updated_at: updatedAt.value,
    ...(comments !== undefined ? { comments } : {}),
    ...(boostedUntil ? { boosted_until: boostedUntil } : {}),
    ...(verifiedAt ? { verified_at: verifiedAt } : {}),
  });
};

export const parseBtcMapSnapshot = (value: unknown): Result<BtcMapSnapshot, Error> => {
  if (!isRecord(value) || value.schemaVersion !== 1 || !Array.isArray(value.places)) {
    return err(new Error("Unsupported BTC Map snapshot."));
  }

  const generatedAt = optionalString(value.generatedAt);
  const source = optionalString(value.source);
  if (!generatedAt || !source) {
    return err(new Error("BTC Map snapshot is missing metadata."));
  }

  const places: BtcMapPlace[] = [];
  for (const rawPlace of value.places) {
    const place = parsePlace(rawPlace);
    if (place.isErr()) {
      return err(place.error);
    }
    places.push(place.value);
  }

  return ok({ schemaVersion: 1, generatedAt, source, places });
};

const parseSnapshotJson = (json: string): Result<BtcMapSnapshot, Error> =>
  Result.fromThrowable(
    () => JSON.parse(json) as unknown,
    (error) => (error instanceof Error ? error : new Error("Could not parse BTC Map snapshot.")),
  )().andThen(parseBtcMapSnapshot);

const normalizeAssetPath = (uri: string) => decodeURI(uri.replace(/^file:\/\//, ""));

const loadBundledSnapshot = async (): Promise<Result<BtcMapSnapshot, Error>> => {
  const assetResult = await ResultAsync.fromPromise(
    Asset.fromModule(bundledSnapshotAsset).downloadAsync(),
    (error) => (error instanceof Error ? error : new Error("Could not load BTC Map asset.")),
  );
  if (assetResult.isErr()) {
    return err(assetResult.error);
  }

  const uri = assetResult.value.localUri ?? assetResult.value.uri;
  return Result.fromThrowable(
    () => RNFSTurbo.readFile(normalizeAssetPath(uri), "utf8"),
    (error) => (error instanceof Error ? error : new Error("Could not read BTC Map asset.")),
  )().andThen(parseSnapshotJson);
};

export const loadBtcMapSnapshot = async (): Promise<Result<BtcMapSnapshot, Error>> => {
  if (RNFSTurbo.exists(CACHE_PATH)) {
    const cached = Result.fromThrowable(
      () => RNFSTurbo.readFile(CACHE_PATH, "utf8"),
      (error) => (error instanceof Error ? error : new Error("Could not read BTC Map cache.")),
    )().andThen(parseSnapshotJson);
    if (cached.isOk()) {
      return cached;
    }
  }

  return loadBundledSnapshot();
};

const parsePatch = (value: unknown): Result<PlacePatch, Error> => {
  if (!isRecord(value) || typeof value.id !== "number" || !Number.isInteger(value.id)) {
    return err(new Error("BTC Map patch is missing an ID."));
  }
  if (optionalString(value.deleted_at)) {
    return ok({ id: value.id, deleted_at: String(value.deleted_at) });
  }
  return parsePlace(value);
};

export const mergeBtcMapPatches = (
  snapshot: BtcMapSnapshot,
  patches: ReadonlyArray<PlacePatch>,
  generatedAt: string,
): BtcMapSnapshot => {
  const placesById = new Map(snapshot.places.map((place) => [place.id, place]));
  for (const patch of patches) {
    if (patch.deleted_at) {
      placesById.delete(patch.id);
    } else {
      placesById.set(patch.id, patch as BtcMapPlace);
    }
  }
  return {
    ...snapshot,
    generatedAt,
    places: [...placesById.values()].sort((left, right) => left.id - right.id),
  };
};

export const syncBtcMapSnapshot = async (
  snapshot: BtcMapSnapshot,
): Promise<Result<BtcMapSnapshot, Error>> => {
  const syncStartedAt = new Date().toISOString();
  const url = new URL(`${API_URL}/places`);
  url.searchParams.set("fields", [...PLACE_FIELDS, "deleted_at"].join(","));
  url.searchParams.set("updated_since", snapshot.generatedAt);
  url.searchParams.set("include_deleted", "true");

  const response = await ResultAsync.fromPromise(fetch(url.toString()), (error) =>
    error instanceof Error ? error : new Error("BTC Map sync failed."),
  );
  if (response.isErr()) {
    return err(response.error);
  }
  if (!response.value.ok) {
    return err(new Error(`BTC Map sync returned HTTP ${response.value.status}.`));
  }

  const body = await ResultAsync.fromPromise(response.value.json() as Promise<unknown>, (error) =>
    error instanceof Error ? error : new Error("BTC Map sync returned invalid JSON."),
  );
  if (body.isErr() || !Array.isArray(body.value)) {
    return err(body.isErr() ? body.error : new Error("BTC Map sync returned invalid data."));
  }

  const patches: PlacePatch[] = [];
  for (const rawPatch of body.value) {
    const patch = parsePatch(rawPatch);
    if (patch.isErr()) {
      return err(patch.error);
    }
    patches.push(patch.value);
  }

  const merged = mergeBtcMapPatches(snapshot, patches, syncStartedAt);
  const writeResult = Result.fromThrowable(
    () => RNFSTurbo.writeFile(CACHE_PATH, JSON.stringify(merged), "utf8"),
    (error) => (error instanceof Error ? error : new Error("Could not save BTC Map cache.")),
  )();
  return writeResult.map(() => merged);
};

const readDetailField = (
  record: Record<string, unknown>,
  field: keyof BtcMapPlaceDetail,
): string | undefined => optionalString(record[field]);

const parsePlaceDetail = (value: unknown): Result<BtcMapPlaceDetail, Error> => {
  const place = parsePlace(value);
  if (place.isErr() || !isRecord(value)) {
    return err(place.isErr() ? place.error : new Error("Invalid BTC Map place detail."));
  }

  const details: BtcMapPlaceDetail = { ...place.value };
  for (const field of DETAIL_FIELDS) {
    if (field in place.value || field === "id" || field === "lat" || field === "lon") {
      continue;
    }
    const detailValue = readDetailField(value, field);
    if (detailValue) {
      details[field] = detailValue as never;
    }
  }
  return ok(details);
};

const parseComments = (value: unknown): Result<BtcMapComment[], Error> => {
  if (!Array.isArray(value)) {
    return err(new Error("BTC Map comments are not an array."));
  }
  const comments: BtcMapComment[] = [];
  for (const rawComment of value) {
    if (!isRecord(rawComment)) {
      return err(new Error("Invalid BTC Map comment."));
    }
    const { id } = rawComment;
    const text = optionalString(rawComment.text);
    const createdAt = optionalString(rawComment.created_at);
    if (typeof id !== "number" || !Number.isInteger(id) || !text || !createdAt) {
      return err(new Error("Invalid BTC Map comment."));
    }
    comments.push({ id, text, created_at: createdAt });
  }
  return ok(comments);
};

export const fetchBtcMapPlace = async (
  id: number,
  commentCount = 0,
): Promise<Result<BtcMapPlaceWithComments, Error>> => {
  const detailUrl = new URL(`${API_URL}/places/${id}`);
  detailUrl.searchParams.set("fields", DETAIL_FIELDS.join(","));
  const commentsUrl = `${API_URL}/places/${id}/comments`;

  const responses = await ResultAsync.fromPromise(
    Promise.all([
      fetch(detailUrl.toString()),
      commentCount > 0 ? fetch(commentsUrl) : Promise.resolve(null),
    ]),
    (error) => (error instanceof Error ? error : new Error("Could not reach BTC Map.")),
  );
  if (responses.isErr()) {
    return err(responses.error);
  }
  const [detailResponse, commentsResponse] = responses.value;
  if (!detailResponse.ok || (commentsResponse && !commentsResponse.ok)) {
    return err(new Error("BTC Map place details are unavailable."));
  }

  const bodies = await ResultAsync.fromPromise(
    Promise.all([
      detailResponse.json() as Promise<unknown>,
      commentsResponse ? (commentsResponse.json() as Promise<unknown>) : Promise.resolve([]),
    ]),
    (error) => (error instanceof Error ? error : new Error("BTC Map returned invalid JSON.")),
  );
  if (bodies.isErr()) {
    return err(bodies.error);
  }

  return Result.combine([parsePlaceDetail(bodies.value[0]), parseComments(bodies.value[1])]).map(
    ([place, comments]) => ({ place, comments }),
  );
};
