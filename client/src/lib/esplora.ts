import ky from "ky";
import { err, ok, ResultAsync, type Result } from "neverthrow";
import { APP_VARIANT } from "~/config";
import { useEsploraStore } from "~/store/esploraStore";
import {
  getEsploraGenesisHashUrl,
  getEsploraTipHeightUrl,
  normalizeEsploraEndpoint,
  validateEsploraGenesisHash,
} from "~/lib/esploraUrl";
import { getDefaultBlockheightEndpoint, getDefaultEsploraEndpoint } from "~/lib/walletConfig";

const ESPLORA_VALIDATION_TIMEOUT_MS = 5000;
const ESPLORA_GENESIS_HASHES = {
  mainnet: "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
  signet: "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6",
} as const;

export const getEffectiveEsploraEndpoint = (): string | null =>
  useEsploraStore.getState().endpointOverride ?? getDefaultEsploraEndpoint();

export const getEsploraApiBaseUrl = getEffectiveEsploraEndpoint;

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

  if (APP_VARIANT === "regtest") {
    return err(new Error("Regtest wallets use Bitcoin Core instead of Esplora."));
  }

  const endpoint = normalizedResult.value;
  const responseResult = await ResultAsync.fromPromise(
    ky
      .get(getEsploraGenesisHashUrl(endpoint), {
        retry: 0,
        timeout: ESPLORA_VALIDATION_TIMEOUT_MS,
      })
      .text(),
    (error) => new Error(`Unable to reach the Esplora endpoint: ${String(error)}`),
  );

  if (responseResult.isErr()) {
    return err(responseResult.error);
  }

  const genesisHashResult = validateEsploraGenesisHash(
    responseResult.value,
    ESPLORA_GENESIS_HASHES[APP_VARIANT],
  );
  if (genesisHashResult.isErr()) {
    return err(genesisHashResult.error);
  }

  return ok(endpoint);
};
