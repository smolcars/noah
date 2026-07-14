import { useQuery } from "@tanstack/react-query";
import { Asset } from "expo-asset";
import { Result, ResultAsync } from "neverthrow";
import RNFSTurbo from "react-native-fs-turbo";

import bundledCityIndex from "../../assets/geonames/cities.geonames";
import { parseGeoNamesSnapshot } from "~/lib/citySearch";

const normalizeAssetPath = (uri: string) => decodeURI(uri.replace(/^file:\/\//, ""));

const loadCityIndex = async () => {
  const asset = await ResultAsync.fromPromise(
    Asset.fromModule(bundledCityIndex).downloadAsync(),
    (error) => (error instanceof Error ? error : new Error("Could not load the city index.")),
  );
  if (asset.isErr()) {
    throw asset.error;
  }

  const uri = asset.value.localUri ?? asset.value.uri;
  const parsed = Result.fromThrowable(
    () => JSON.parse(RNFSTurbo.readFile(normalizeAssetPath(uri), "utf8")) as unknown,
    (error) => (error instanceof Error ? error : new Error("Could not read the city index.")),
  )().andThen(parseGeoNamesSnapshot);
  if (parsed.isErr()) {
    throw parsed.error;
  }
  return parsed.value;
};

export function useCitySearch() {
  return useQuery({
    queryKey: ["btc-map", "city-index"],
    queryFn: loadCityIndex,
    staleTime: Number.POSITIVE_INFINITY,
    retry: false,
  });
}
