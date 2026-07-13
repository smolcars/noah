import ky from "ky";
import { err, ok, ResultAsync, type Result } from "neverthrow";
import {
  ACTIVE_WALLET_CONFIG,
  getDefaultBlockheightEndpoint,
  type WalletCreationOptions,
} from "~/constants";
import { APP_VARIANT } from "~/config";
import { useEsploraStore } from "~/store/esploraStore";
import {
  getEsploraTipHeightUrl,
  normalizeEsploraEndpoint,
  parseEsploraTipHeight,
} from "~/lib/esploraUrl";

const ESPLORA_VALIDATION_TIMEOUT_MS = 5000;

export const getDefaultEsploraEndpoint = (): string | null => {
  if (APP_VARIANT === "regtest") {
    return null;
  }

  const configuredEndpoint = ACTIVE_WALLET_CONFIG.config?.esplora;
  if (!configuredEndpoint) {
    return null;
  }

  return normalizeEsploraEndpoint(configuredEndpoint).unwrapOr(null);
};

export const getEffectiveEsploraEndpoint = (): string | null =>
  useEsploraStore.getState().endpointOverride ?? getDefaultEsploraEndpoint();

export const getEsploraApiBaseUrl = getEffectiveEsploraEndpoint;

export const getActiveWalletConfig = (
  esploraEndpoint = getEffectiveEsploraEndpoint(),
): WalletCreationOptions => {
  const config = ACTIVE_WALLET_CONFIG.config;
  if (!config) {
    return { ...ACTIVE_WALLET_CONFIG };
  }

  return {
    ...ACTIVE_WALLET_CONFIG,
    config: {
      ...config,
      ...(APP_VARIANT !== "regtest" && esploraEndpoint ? { esplora: esploraEndpoint } : {}),
    },
  };
};

export const getBlockheightEndpoint = (): string => {
  const endpointOverride = useEsploraStore.getState().endpointOverride;
  if (APP_VARIANT !== "regtest" && endpointOverride) {
    return getEsploraTipHeightUrl(endpointOverride);
  }

  return getDefaultBlockheightEndpoint();
};

export const validateEsploraEndpoint = async (value: string): Promise<Result<string, Error>> => {
  const normalizedResult = normalizeEsploraEndpoint(value);
  if (normalizedResult.isErr()) {
    return err(normalizedResult.error);
  }

  const endpoint = normalizedResult.value;
  const responseResult = await ResultAsync.fromPromise(
    ky
      .get(getEsploraTipHeightUrl(endpoint), {
        retry: 0,
        timeout: ESPLORA_VALIDATION_TIMEOUT_MS,
      })
      .text(),
    (error) => new Error(`Unable to reach the Esplora endpoint: ${String(error)}`),
  );

  if (responseResult.isErr()) {
    return err(responseResult.error);
  }

  const heightResult = parseEsploraTipHeight(responseResult.value);
  if (heightResult.isErr()) {
    return err(heightResult.error);
  }

  return ok(endpoint);
};
