import { useQuery } from "@tanstack/react-query";
import { getBlockheightEndpoint } from "~/lib/esplora";
import ky from "ky";
import logger from "~/lib/log";

const log = logger("useMarketData");

import { err, ok, Result, ResultAsync } from "neverthrow";
import { APP_VARIANT } from "~/config";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { getFiatPrices, getHistoricalFiatPrice } from "~/lib/api";
import { useProfileStore } from "~/store/profileStore";
import { useEsploraStore } from "~/store/esploraStore";
import { getWalletRpcAuth } from "~/lib/walletConfig";

const HISTORICAL_RATE_CACHE_MAX_SIZE = 200;
type HistoricalRateLookup = {
  rate: number;
  cacheable: boolean;
};
const historicalBtcToFiatRateCache = new Map<
  string,
  Promise<Result<HistoricalRateLookup, Error>>
>();

export const getBtcToFiatRate = (currency: FiatCurrencyCode): ResultAsync<number, Error> => {
  return ResultAsync.fromSafePromise(getFiatPrices()).andThen((result) => {
    if (result.isErr()) {
      return err(new Error(`Failed to fetch BTC to ${currency} rate: ${result.error.message}`));
    }

    const data = result.value;
    const rate = data.rates[currency];
    if (rate) {
      return ok(rate);
    }
    return err(new Error("Invalid response from exchange rate API"));
  });
};

export function useBtcToFiatRate() {
  const preferredCurrency = useProfileStore((state) => state.preferredCurrency);

  return useQuery({
    queryKey: ["btcToFiatRate", preferredCurrency],
    queryFn: async () => {
      const result = await getBtcToFiatRate(preferredCurrency);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    staleTime: 1000 * 60 * 5, // 5 minutes
  });
}

export const getBlockHeight = async (): Promise<Result<number, Error>> => {
  const url = getBlockheightEndpoint();

  if (APP_VARIANT === "regtest") {
    const auth = getWalletRpcAuth();

    return ResultAsync.fromPromise(
      ky
        .post(url, {
          json: {
            jsonrpc: "1.0",
            id: "curltest",
            method: "getblockcount",
          },
          headers: {
            Authorization: `Basic ${btoa(`${auth.username}:${auth.password}`)}`,
          },
        })
        .json<{ result: number }>(),
      (e) => new Error(`Failed to fetch blockheight from Bitcoin Core: ${e}`),
    ).andThen((data) => {
      return ok(data.result);
    });
  }

  const result = await ResultAsync.fromPromise(
    ky.get(url).text(),
    (e) => new Error(`Failed to fetch blockheight from Esplora: ${e}`),
  ).andThen((data) => {
    const height = parseInt(data, 10);
    if (!isNaN(height)) {
      return ok(height);
    }
    return err(new Error("Invalid blockheight response"));
  });

  return result;
};

export function useGetBlockHeight() {
  const endpointOverride = useEsploraStore((state) => state.endpointOverride);

  return useQuery({
    queryKey: ["getBlockHeight", endpointOverride],
    queryFn: async () => {
      const result = await getBlockHeight();
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    staleTime: 1000 * 60 * 5, // 5 minutes
  });
}

const getHistoricalRateCacheKey = (date: string, currency: FiatCurrencyCode): string => {
  const timestampMs = new Date(date).getTime();
  if (Number.isNaN(timestampMs)) {
    return `${currency}:invalid:${date}`;
  }

  return `${currency}:${new Date(timestampMs).toISOString().slice(0, 10)}`;
};

const trimHistoricalRateCache = () => {
  while (historicalBtcToFiatRateCache.size > HISTORICAL_RATE_CACHE_MAX_SIZE) {
    const oldestKey = historicalBtcToFiatRateCache.keys().next().value;
    if (oldestKey === undefined) {
      return;
    }
    historicalBtcToFiatRateCache.delete(oldestKey);
  }
};

const fetchHistoricalBtcToFiatRate = async (
  date: string,
  currency: FiatCurrencyCode,
): Promise<Result<HistoricalRateLookup, Error>> => {
  const timestamp = Math.floor(new Date(date).getTime() / 1000);
  return ResultAsync.fromPromise(getHistoricalFiatPrice({ currency, timestamp }), (e) => e as Error)
    .andThen((result) => {
      if (result.isErr()) {
        return err(
          new Error(`Failed to fetch historical BTC to ${currency} rate: ${result.error.message}`),
        );
      }

      const data = result.value;
      const rate = data.rate;
      if (rate) {
        return ok({ rate, cacheable: true });
      }
      // If no price is available for that day, fetch the current price as a fallback.
      return getBtcToFiatRate(currency).map((fallbackRate) => ({
        rate: fallbackRate,
        cacheable: false,
      }));
    })
    .orElse((error) => {
      log.e(`Failed to fetch historical BTC to ${currency} rate:`, [error]);
      // Fallback to current price on error
      return getBtcToFiatRate(currency).map((fallbackRate) => ({
        rate: fallbackRate,
        cacheable: false,
      }));
    });
};

export const getHistoricalBtcToFiatRate = (
  date: string,
  currency: FiatCurrencyCode,
): ResultAsync<number, Error> => {
  const cacheKey = getHistoricalRateCacheKey(date, currency);
  const cachedRate = historicalBtcToFiatRateCache.get(cacheKey);
  if (cachedRate) {
    return ResultAsync.fromSafePromise(cachedRate).andThen((result) =>
      result.map(({ rate }) => rate),
    );
  }

  const ratePromise = fetchHistoricalBtcToFiatRate(date, currency);
  historicalBtcToFiatRateCache.set(cacheKey, ratePromise);
  trimHistoricalRateCache();

  ratePromise
    .then((result) => {
      if (result.isErr() || !result.value.cacheable) {
        historicalBtcToFiatRateCache.delete(cacheKey);
      }
    })
    .catch(() => {
      historicalBtcToFiatRateCache.delete(cacheKey);
    });

  return ResultAsync.fromSafePromise(ratePromise).andThen((result) =>
    result.map(({ rate }) => rate),
  );
};
