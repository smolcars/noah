import { useQuery } from "@tanstack/react-query";
import {
  mempoolPriceEndpoint,
  mempoolHistoricalPriceEndpoint,
  getBlockheightEndpoint,
  REGTEST_CONFIG,
} from "~/constants";
import ky from "ky";
import logger from "~/lib/log";

const log = logger("useMarketData");

import { err, ok, Result, ResultAsync } from "neverthrow";
import { APP_VARIANT } from "~/config";
import type { FiatCurrencyCode, FiatRates } from "~/lib/fiatCurrency";
import { useProfileStore } from "~/store/profileStore";

export const getBtcToFiatRate = (currency: FiatCurrencyCode): ResultAsync<number, Error> => {
  return ResultAsync.fromPromise(
    ky.get(mempoolPriceEndpoint).json<FiatRates>(),
    (e) => new Error(`Failed to fetch BTC to ${currency} rate: ${e}`),
  ).andThen((data) => {
    const rate = data[currency];
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
    const auth = {
      username: REGTEST_CONFIG.config?.bitcoind_user,
      password: REGTEST_CONFIG.config?.bitcoind_pass,
    };

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
    (e) => new Error(`Failed to fetch blockheight from mempool.space: ${e}`),
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
  return useQuery({
    queryKey: ["getBlockHeight"],
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

export const getHistoricalBtcToFiatRate = (
  date: string,
  currency: FiatCurrencyCode,
): ResultAsync<number, Error> => {
  const timestamp = Math.floor(new Date(date).getTime() / 1000);
  return ResultAsync.fromPromise(
    ky
      .get(mempoolHistoricalPriceEndpoint, {
        searchParams: {
          currency,
          timestamp: timestamp.toString(),
        },
      })
      .json<{ prices?: FiatRates[] }>(),
    (e) => new Error(`Failed to fetch historical BTC to ${currency} rate: ${e}`),
  )
    .andThen((data) => {
      const prices = data.prices;
      const rate = prices?.[0]?.[currency];
      if (rate) {
        return ok(rate);
      }
      // If no price is available for that day, fetch the current price as a fallback.
      return getBtcToFiatRate(currency);
    })
    .orElse((error) => {
      log.e(`Failed to fetch historical BTC to ${currency} rate:`, [error]);
      // Fallback to current price on error
      return getBtcToFiatRate(currency);
    });
};
