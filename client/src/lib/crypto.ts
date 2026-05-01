import {
  signMessage as signMessageNitro,
  peekKeyPair as peekKeyPairNitro,
  deriveStoreNextKeypair as deriveStoreNextKeypairNitro,
  verifyMessage as verifyMessageNitro,
  type KeyPairResult,
} from "react-native-nitro-ark";
import { atob } from "react-native-quick-base64";
import { Result, ok, err, ResultAsync } from "neverthrow";
import logger from "~/lib/log";

const log = logger("crypto");
import * as Keychain from "react-native-keychain";
import { APP_VARIANT } from "~/config";
import {
  ARK_SERVER_ACCESS_TOKEN_KEYCHAIN_SERVICE,
  AUTH_TOKEN_KEYCHAIN_SERVICE,
  KEYCHAIN_USERNAME,
} from "~/constants";

const MNEMONIC_KEYCHAIN_SERVICE = `com.noah.mnemonic.${APP_VARIANT}`;
let inMemoryServerAuthToken: string | null = null;
let inMemoryArkServerAccessToken: string | null | undefined;

const decodeBase64Url = (value: string): Result<string, Error> => {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");

  return Result.fromThrowable(
    () => atob(padded),
    (e) => new Error(`Failed to decode JWT payload: ${(e as Error).message}`),
  )();
};

export const signMessage = async (
  message: string,
  index: number,
): Promise<Result<string, Error>> => {
  return ResultAsync.fromPromise(signMessageNitro(message, index), (e) => e as Error);
};

export const peakKeyPair = async (index: number): Promise<Result<KeyPairResult, Error>> => {
  return ResultAsync.fromPromise(peekKeyPairNitro(index), (e) => e as Error);
};

export const deriveStoreNextKeypair = async (): Promise<Result<KeyPairResult, Error>> => {
  return ResultAsync.fromPromise(deriveStoreNextKeypairNitro(), (e) => e as Error);
};

/**
 * Verifies a message signature using the native crypto implementation.
 * This prevents tampering as only the owner of the private key can produce a valid signature.
 */
export const verifyMessage = async (
  message: string,
  signature: string,
  index: number,
): Promise<Result<boolean, Error>> => {
  try {
    // Get the public key for the given index
    const keyPairResult = await peakKeyPair(index);
    if (keyPairResult.isErr()) {
      log.e("Failed to get public key for verification", [keyPairResult.error]);
      return err(keyPairResult.error);
    }

    const publicKey = keyPairResult.value.public_key;

    // Verify the signature using the native implementation
    const isValid = await verifyMessageNitro(message, signature, publicKey);

    if (!isValid) {
      log.w("Signature verification failed - message may have been tampered with");
    }

    return ok(isValid);
  } catch (e) {
    return err(e as Error);
  }
};

export const setMnemonic = async (mnemonic: string): Promise<Result<void, Error>> => {
  await ResultAsync.fromPromise(
    Keychain.setGenericPassword(KEYCHAIN_USERNAME, mnemonic, {
      service: MNEMONIC_KEYCHAIN_SERVICE,
    }),
    (e) => e as Error,
  );

  return ok(undefined);
};

export const getMnemonic = async (): Promise<Result<string, Error>> => {
  const credentialsResult = await ResultAsync.fromPromise(
    Keychain.getGenericPassword({ service: MNEMONIC_KEYCHAIN_SERVICE }),
    (e) => e as Error,
  );

  if (credentialsResult.isErr()) {
    return err(credentialsResult.error);
  }

  const credentials = credentialsResult.value;
  if (!credentials || !credentials.password) {
    return err(new Error("No wallet found. Please create a wallet first."));
  }

  return ok(credentials.password);
};

export const setServerAuthToken = async (token: string): Promise<Result<void, Error>> => {
  const result = await ResultAsync.fromPromise(
    Keychain.setGenericPassword(KEYCHAIN_USERNAME, token, {
      service: AUTH_TOKEN_KEYCHAIN_SERVICE,
    }),
    (e) => e as Error,
  );

  if (result.isErr()) {
    log.w("Failed to store server auth token", [result.error]);
    return err(result.error);
  }

  inMemoryServerAuthToken = token;
  return ok(undefined);
};

export const getServerAuthToken = async (): Promise<Result<string | null, Error>> => {
  if (inMemoryServerAuthToken) {
    return ok(inMemoryServerAuthToken);
  }

  const credentialsResult = await ResultAsync.fromPromise(
    Keychain.getGenericPassword({ service: AUTH_TOKEN_KEYCHAIN_SERVICE }),
    (e) => e as Error,
  );

  if (credentialsResult.isErr()) {
    log.w("Failed to read server auth token", [credentialsResult.error]);
    return err(credentialsResult.error);
  }

  const credentials = credentialsResult.value;
  if (!credentials || !credentials.password) {
    inMemoryServerAuthToken = null;
    return ok(null);
  }

  inMemoryServerAuthToken = credentials.password;
  return ok(credentials.password);
};

export const resetServerAuthToken = async (): Promise<Result<void, Error>> => {
  const result = await ResultAsync.fromPromise(
    Keychain.resetGenericPassword({ service: AUTH_TOKEN_KEYCHAIN_SERVICE }),
    (e) => e as Error,
  );

  if (result.isErr()) {
    log.w("Failed to clear server auth token", [result.error]);
    return err(result.error);
  }

  inMemoryServerAuthToken = null;
  return ok(undefined);
};

export const setArkServerAccessToken = async (token: string): Promise<Result<void, Error>> => {
  const trimmed = token.trim();
  if (!trimmed) {
    return resetArkServerAccessToken();
  }

  const result = await ResultAsync.fromPromise(
    Keychain.setGenericPassword(KEYCHAIN_USERNAME, trimmed, {
      service: ARK_SERVER_ACCESS_TOKEN_KEYCHAIN_SERVICE,
    }),
    (e) => e as Error,
  );

  if (result.isErr()) {
    log.w("Failed to store Ark server access token", [result.error]);
    return err(result.error);
  }

  inMemoryArkServerAccessToken = trimmed;
  return ok(undefined);
};

export const getArkServerAccessToken = async (): Promise<Result<string | null, Error>> => {
  if (inMemoryArkServerAccessToken !== undefined) {
    return ok(inMemoryArkServerAccessToken);
  }

  const credentialsResult = await ResultAsync.fromPromise(
    Keychain.getGenericPassword({ service: ARK_SERVER_ACCESS_TOKEN_KEYCHAIN_SERVICE }),
    (e) => e as Error,
  );

  if (credentialsResult.isErr()) {
    log.w("Failed to read Ark server access token", [credentialsResult.error]);
    return err(credentialsResult.error);
  }

  const credentials = credentialsResult.value;
  const token = credentials && credentials.password ? credentials.password : null;
  inMemoryArkServerAccessToken = token;
  return ok(token);
};

export const resetArkServerAccessToken = async (): Promise<Result<void, Error>> => {
  const result = await ResultAsync.fromPromise(
    Keychain.resetGenericPassword({ service: ARK_SERVER_ACCESS_TOKEN_KEYCHAIN_SERVICE }),
    (e) => e as Error,
  );

  if (result.isErr()) {
    log.w("Failed to clear Ark server access token", [result.error]);
    return err(result.error);
  }

  inMemoryArkServerAccessToken = null;
  return ok(undefined);
};

export const shouldRefreshServerAuthToken = (
  token: string,
  refreshWindowSeconds: number,
  clockSkewSeconds: number,
): Result<boolean, Error> => {
  const segments = token.split(".");
  if (segments.length !== 3) {
    return err(new Error("JWT must have three segments"));
  }

  const payloadResult = decodeBase64Url(segments[1]);
  if (payloadResult.isErr()) {
    return err(payloadResult.error);
  }

  const parsedResult = Result.fromThrowable(
    () => JSON.parse(payloadResult.value) as { exp?: unknown },
    (e) => new Error(`Failed to parse JWT payload: ${(e as Error).message}`),
  )();
  if (parsedResult.isErr()) {
    return err(parsedResult.error);
  }

  if (typeof parsedResult.value.exp !== "number" || !Number.isFinite(parsedResult.value.exp)) {
    return err(new Error("JWT payload is missing a valid exp claim"));
  }

  const now = Math.floor(Date.now() / 1000);
  const secondsRemaining = parsedResult.value.exp - now;
  return ok(secondsRemaining <= refreshWindowSeconds + clockSkewSeconds);
};
