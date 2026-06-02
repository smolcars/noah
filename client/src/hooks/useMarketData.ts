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
export const getBtcToUsdRate = (): ResultAsync<number, Error> => {
  return ResultAsync.fromPromise(
    ky.get(mempoolPriceEndpoint).json<{ USD?: number }>(),
    (e) => new Error(`Failed to fetch BTC to USD rate: ${e}`),
  ).andThen((data) => {
    const rate = data.USD;
    if (rate) {
      return ok(rate);
    }
    return err(new Error("Invalid response from exchange rate API"));
  });
};

export function useBtcToUsdRate() {
  return useQuery({
    queryKey: ["btcToUsdRate"],
    queryFn: async () => {
      const result = await getBtcToUsdRate();
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

export const getHistoricalBtcToUsdRate = (date: string): ResultAsync<number, Error> => {
  const timestamp = Math.floor(new Date(date).getTime() / 1000);
  return ResultAsync.fromPromise(
    ky
      .get(mempoolHistoricalPriceEndpoint, {
        searchParams: {
          currency: "USD",
          timestamp: timestamp.toString(),
        },
      })
      .json<{ prices?: { USD?: number }[] }>(),
    (e) => new Error(`Failed to fetch historical BTC to USD rate: ${e}`),
  )
    .andThen((data) => {
      const prices = data.prices;
      if (prices && prices.length > 0 && prices[0].USD) {
        return ok(prices[0].USD);
      }
      // If no price is available for that day, fetch the current price as a fallback.
      return getBtcToUsdRate();
    })
    .orElse((error) => {
      log.e("Failed to fetch historical BTC to USD rate:", [error]);
      // Fallback to current price on error
      return getBtcToUsdRate();
    });
};
