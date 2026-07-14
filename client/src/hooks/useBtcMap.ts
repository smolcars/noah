import { useQuery } from "@tanstack/react-query";

import {
  fetchBtcMapPlace,
  loadBtcMapSnapshot,
  syncBtcMapSnapshot,
  type BtcMapSnapshot,
} from "~/lib/btcMap";

const unwrapSnapshot = async () => {
  const snapshot = await loadBtcMapSnapshot();
  if (snapshot.isErr()) {
    throw snapshot.error;
  }
  return snapshot.value;
};

const syncSnapshot = async (snapshot: BtcMapSnapshot) => {
  const result = await syncBtcMapSnapshot(snapshot);
  if (result.isErr()) {
    throw result.error;
  }
  return result.value;
};

export function useBtcMapPlaces() {
  const localSnapshot = useQuery({
    queryKey: ["btc-map", "local-snapshot"],
    queryFn: unwrapSnapshot,
    staleTime: Number.POSITIVE_INFINITY,
    retry: false,
  });
  const syncSnapshotQuery = useQuery({
    queryKey: ["btc-map", "sync", localSnapshot.data?.generatedAt],
    enabled: localSnapshot.data !== undefined,
    queryFn: () => {
      if (!localSnapshot.data) {
        throw new Error("BTC Map snapshot is not loaded.");
      }
      return syncSnapshot(localSnapshot.data);
    },
    staleTime: 5 * 60 * 1000,
    retry: 1,
  });

  return {
    ...localSnapshot,
    data: syncSnapshotQuery.data ?? localSnapshot.data,
    isSyncing: syncSnapshotQuery.isFetching,
  };
}

export function useBtcMapPlace(id: number | undefined, commentCount: number | undefined) {
  return useQuery({
    queryKey: ["btc-map", "place", id],
    enabled: id !== undefined,
    queryFn: async () => {
      if (id === undefined) {
        throw new Error("BTC Map place ID is missing.");
      }
      const result = await fetchBtcMapPlace(id, commentCount);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    staleTime: 15 * 60 * 1000,
    retry: 1,
  });
}
