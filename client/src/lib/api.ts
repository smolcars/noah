/// <reference lib="dom" />
import { Result, ok, err, ResultAsync } from "neverthrow";
import { getServerEndpoint } from "~/constants";
import {
  getMnemonic,
  getServerAuthToken,
  resetServerAuthToken,
  setServerAuthToken,
  shouldRefreshServerAuthToken,
} from "./crypto";
import { deriveKeypairFromMnemonic, signMesssageWithMnemonic } from "./walletApi";
import { APP_VARIANT } from "~/config";
import {
  ApiErrorResponse,
  AuthLoginPayload,
  AuthLoginResponse,
  AuthorizeMailboxPayload,
  AppVersionCheckPayload,
  AppVersionInfo,
  BackupInfo,
  BackupSettingsPayload,
  CompleteUploadPayload,
  DeleteBackupPayload,
  DownloadUrlResponse,
  GetDownloadUrlPayload,
  GetUploadUrlPayload,
  HeartbeatResponsePayload,
  RegisterResponse,
  RegisterPushToken,
  LightningAddressSuggestionsPayload,
  LightningAddressSuggestionsResponse,
  UpdateLnAddressPayload,
  UpdateProfilePayload,
  UploadUrlResponse,
  ReportJobStatusPayload,
  DefaultSuccessPayload,
  SubmitInvoicePayload,
  RegisterPayload,
  UserInfoResponse,
  SendEmailVerificationPayload,
  VerifyEmailPayload,
  EmailVerificationResponse,
  FiatPricesPayload,
  FiatPricesResponse,
  HistoricalFiatPricePayload,
  HistoricalFiatPriceResponse,
  SubmitSupportTicketPayload,
  SubmitSupportTicketResponse,
} from "~/types/serverTypes";
import logger from "~/lib/log";
import { nativeGet, nativePost } from "noah-tools";

const log = logger("serverApi");

const API_URL = getServerEndpoint();
const SERVER_AUTH_KEY_INDEX = 0;
const REQUEST_TIMEOUT_SECONDS = 30;
const TOKEN_REFRESH_WINDOW_SECONDS = 3 * 60 * 60;
const TOKEN_CLOCK_SKEW_SECONDS = 60;

let loginInFlight: Promise<Result<string, Error>> | null = null;

class ApiError extends Error {
  status: number;
  code: string;
  reason: string;

  constructor(message: string, status: number, code: string, reason: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.reason = reason;
  }
}

const isApiErrorResponse = (value: unknown): value is ApiErrorResponse => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const obj = value as Record<string, unknown>;
  return (
    typeof obj.status === "string" &&
    typeof obj.code === "string" &&
    typeof obj.message === "string" &&
    typeof obj.reason === "string"
  );
};

const buildApiError = (status: number, body?: string | null): Error => {
  if (!body) {
    return new Error(`HTTP ${status}: Empty response body`);
  }

  const parseResult = Result.fromThrowable(
    () => JSON.parse(body) as unknown,
    (e) => e as Error,
  )();

  if (parseResult.isOk() && isApiErrorResponse(parseResult.value)) {
    const parsed = parseResult.value;
    return new ApiError(parsed.message, status, parsed.code, parsed.reason);
  }

  return new Error(`HTTP ${status}: ${body}`);
};

const parseJsonResponse = <T>(body: string, context: string): Result<T, Error> =>
  Result.fromThrowable(
    () => JSON.parse(body) as T,
    (e) => new Error(`Failed to parse JSON response for ${context}: ${(e as Error).message}`),
  )();

const postJson = async <T>(
  endpoint: string,
  payload: unknown,
  headers: Record<string, string>,
): Promise<Result<T, Error>> => {
  const responseResult = await ResultAsync.fromPromise(
    nativePost(
      `${API_URL}/v0${endpoint}`,
      JSON.stringify(payload),
      headers,
      REQUEST_TIMEOUT_SECONDS,
    ),
    (e) => e as Error,
  );

  if (responseResult.isErr()) {
    return err(responseResult.error);
  }

  const response = responseResult.value;

  if (response.status < 200 || response.status >= 300) {
    return err(buildApiError(response.status, response.body));
  }

  if (!response.body || response.body === "") {
    return ok(undefined as T);
  }

  const parsed = parseJsonResponse<T>(response.body, endpoint);
  if (parsed.isErr()) {
    log.e("Failed to parse JSON response", [parsed.error, response.body]);
    return err(parsed.error);
  }

  return ok(parsed.value);
};

const clearStoredAccessToken = async (): Promise<void> => {
  const resetResult = await resetServerAuthToken();
  if (resetResult.isErr()) {
    log.w("Failed to clear stored server auth token", [resetResult.error]);
  }
};

const authenticateWithMnemonic = async (
  mnemonic: string,
  persistToken: boolean,
): Promise<Result<string, Error>> => {
  const k1Result = await getK1();
  if (k1Result.isErr()) {
    return err(k1Result.error);
  }

  const keypairResult = await deriveKeypairFromMnemonic(
    mnemonic,
    APP_VARIANT,
    SERVER_AUTH_KEY_INDEX,
  );
  if (keypairResult.isErr()) {
    log.w("Failed to derive public key for server authentication", [keypairResult.error]);
    return err(keypairResult.error);
  }

  const signatureResult = await signMesssageWithMnemonic(
    k1Result.value,
    mnemonic,
    APP_VARIANT,
    SERVER_AUTH_KEY_INDEX,
  );
  if (signatureResult.isErr()) {
    log.w("Failed to sign login challenge", [signatureResult.error]);
    return err(signatureResult.error);
  }

  const loginPayload: AuthLoginPayload = {
    key: keypairResult.value.public_key,
    sig: signatureResult.value,
    k1: k1Result.value,
  };

  const loginResult = await postJson<AuthLoginResponse>("/auth/login", loginPayload, {
    "Content-Type": "application/json",
  });
  if (loginResult.isErr()) {
    return err(loginResult.error);
  }

  const accessToken = loginResult.value.access_token;

  if (persistToken) {
    const storeResult = await setServerAuthToken(accessToken);
    if (storeResult.isErr()) {
      await clearStoredAccessToken();
      return err(storeResult.error);
    }
  }

  return ok(accessToken);
};

const getAccessToken = async (options?: {
  forceRefresh?: boolean;
  mnemonic?: string;
  persistToken?: boolean;
}): Promise<Result<string, Error>> => {
  const forceRefresh = options?.forceRefresh ?? false;
  const persistToken = options?.persistToken ?? true;

  if (options?.mnemonic) {
    return authenticateWithMnemonic(options.mnemonic, persistToken);
  }

  if (!forceRefresh) {
    const storedTokenResult = await getServerAuthToken();
    if (storedTokenResult.isErr()) {
      log.w("Stored server auth token is unreadable, re-authenticating", [
        storedTokenResult.error,
      ]);
      await clearStoredAccessToken();
    } else if (storedTokenResult.value) {
      const shouldRefreshResult = shouldRefreshServerAuthToken(
        storedTokenResult.value,
        TOKEN_REFRESH_WINDOW_SECONDS,
        TOKEN_CLOCK_SKEW_SECONDS,
      );
      if (shouldRefreshResult.isErr()) {
        log.w("Stored server auth token is invalid, re-authenticating", [
          shouldRefreshResult.error,
        ]);
        await clearStoredAccessToken();
      } else if (!shouldRefreshResult.value) {
        return ok(storedTokenResult.value);
      } else {
        log.d("Stored server auth token is near expiry, refreshing before request");
        await clearStoredAccessToken();
      }
    }
  }

  if (loginInFlight) {
    return loginInFlight;
  }

  loginInFlight = (async () => {
    const mnemonicResult = await getMnemonic();
    if (mnemonicResult.isErr()) {
      log.w("Failed to read mnemonic for server authentication", [mnemonicResult.error]);
      return err(mnemonicResult.error);
    }

    return authenticateWithMnemonic(mnemonicResult.value, true);
  })();

  try {
    return await loginInFlight;
  } finally {
    loginInFlight = null;
  }
};

async function post<T, U>(
  endpoint: string,
  payload: T,
  options?: {
    accessToken?: string;
    authenticated?: boolean;
    retryOnAuthFailure?: boolean;
  },
): Promise<Result<U, Error>> {
  const authenticated = options?.authenticated ?? true;
  const retryOnAuthFailure = options?.retryOnAuthFailure ?? true;
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };

  if (!authenticated) {
    return postJson<U>(endpoint, payload, headers);
  }

  const tokenResult =
    options?.accessToken !== undefined ? ok(options.accessToken) : await getAccessToken();
  if (tokenResult.isErr()) {
    return err(tokenResult.error);
  }

  headers.Authorization = `Bearer ${tokenResult.value}`;

  const responseResult = await postJson<U>(endpoint, payload, headers);
  if (
    retryOnAuthFailure &&
    options?.accessToken === undefined &&
    responseResult.isErr() &&
    responseResult.error instanceof ApiError &&
    ["AUTH_REQUIRED", "INVALID_TOKEN", "TOKEN_EXPIRED"].includes(responseResult.error.code)
  ) {
    await clearStoredAccessToken();
    const refreshedTokenResult = await getAccessToken({ forceRefresh: true });
    if (refreshedTokenResult.isErr()) {
      return err(refreshedTokenResult.error);
    }

    return post<T, U>(endpoint, payload, {
      ...options,
      accessToken: refreshedTokenResult.value,
      retryOnAuthFailure: false,
    });
  }

  return responseResult;
}

export const getFiatPrices = () =>
  post<FiatPricesPayload, FiatPricesResponse>("/prices", {});

export const getHistoricalFiatPrice = (payload: HistoricalFiatPricePayload) =>
  post<HistoricalFiatPricePayload, HistoricalFiatPriceResponse>("/historical-price", payload);

export const getUploadUrl = (payload: GetUploadUrlPayload) =>
  post<GetUploadUrlPayload, UploadUrlResponse>("/backup/upload_url", payload);

export const completeUpload = (payload: CompleteUploadPayload) =>
  post<CompleteUploadPayload, DefaultSuccessPayload>("/backup/complete_upload", payload);

export const listBackups = () => post<object, BackupInfo[]>("/backup/list", {});

export const getDownloadUrl = (payload: GetDownloadUrlPayload) =>
  post<GetDownloadUrlPayload, DownloadUrlResponse>("/backup/download_url", payload);

export const deleteBackup = (payload: DeleteBackupPayload) =>
  post<DeleteBackupPayload, DefaultSuccessPayload>("/backup/delete", payload);

export const updateBackupSettings = (payload: BackupSettingsPayload) =>
  post<BackupSettingsPayload, DefaultSuccessPayload>("/backup/settings", payload);

export const registerWithServer = (payload: RegisterPayload) =>
  post<RegisterPayload, RegisterResponse>("/register", payload);

export const updateLightningAddress = (payload: UpdateLnAddressPayload) =>
  post<UpdateLnAddressPayload, DefaultSuccessPayload>("/update_ln_address", payload);

export const updateProfile = (payload: UpdateProfilePayload) =>
  post<UpdateProfilePayload, DefaultSuccessPayload>("/update_profile", payload);

export const getUserInfo = () => post<object, UserInfoResponse>("/user_info", {});

export const getLightningAddressSuggestions = (payload: LightningAddressSuggestionsPayload) =>
  post<LightningAddressSuggestionsPayload, LightningAddressSuggestionsResponse>(
    "/ln_address_suggestions",
    payload,
  );

export const registerPushToken = (payload: RegisterPushToken) =>
  post<RegisterPushToken, DefaultSuccessPayload>("/register_push_token", payload);

export const authorizeMailbox = (payload: AuthorizeMailboxPayload) =>
  post<AuthorizeMailboxPayload, DefaultSuccessPayload>("/mailbox/authorize", payload);

export const revokeMailboxAuthorization = () =>
  post<object, DefaultSuccessPayload>("/mailbox/revoke", {});

type ReportCompletionStatus = Extract<ReportJobStatusPayload["status"], "success" | "failure">;
type ReportJobCompletionPayload = Omit<ReportJobStatusPayload, "status"> & {
  status: ReportCompletionStatus;
};

export const reportJobStatus = (payload: ReportJobCompletionPayload) =>
  post<ReportJobCompletionPayload, DefaultSuccessPayload>("/report_job_status", payload);

export const submitInvoice = (payload: SubmitInvoicePayload) =>
  post<SubmitInvoicePayload, DefaultSuccessPayload>("/lnurlp/submit_invoice", payload);

export const submitSupportTicket = (payload: SubmitSupportTicketPayload) =>
  post<SubmitSupportTicketPayload, SubmitSupportTicketResponse>("/support/ticket", payload);

export const heartbeatResponse = (payload: HeartbeatResponsePayload) =>
  post<HeartbeatResponsePayload, DefaultSuccessPayload>("/heartbeat_response", payload);

export const sendVerificationEmail = (payload: SendEmailVerificationPayload) =>
  post<SendEmailVerificationPayload, EmailVerificationResponse>(
    "/email/send_verification",
    payload,
  );

export const verifyEmail = (payload: VerifyEmailPayload) =>
  post<VerifyEmailPayload, EmailVerificationResponse>("/email/verify", payload);

export const getK1 = async (): Promise<Result<string, Error>> => {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };

  const responseResult = await ResultAsync.fromPromise(
    nativeGet(`${API_URL}/v0/getk1`, headers, 30),
    (e) => e as Error,
  );

  if (responseResult.isErr()) {
    return err(responseResult.error);
  }

  const response = responseResult.value;

  if (response.status < 200 || response.status >= 300) {
    return err(buildApiError(response.status, response.body));
  }

  if (!response.body) {
    return err(new Error("Empty response body from getk1"));
  }

  const parseResult = Result.fromThrowable(
    () => JSON.parse(response.body) as { k1: string },
    (e) => new Error(`Failed to parse JSON response: ${(e as Error).message}`),
  )();

  if (parseResult.isErr()) {
    return err(parseResult.error);
  }

  return ok(parseResult.value.k1);
};

export const getDownloadUrlForRestore = async (payload: {
  backup_version?: number;
  mnemonic: string;
}): Promise<Result<DownloadUrlResponse, Error>> => {
  const authResult = await getAccessToken({
    mnemonic: payload.mnemonic,
    persistToken: false,
  });

  if (authResult.isErr()) {
    return err(authResult.error);
  }

  return post<GetDownloadUrlPayload, DownloadUrlResponse>(
    "/backup/download_url",
    {
      backup_version: payload.backup_version ?? null,
    },
    {
      accessToken: authResult.value,
      retryOnAuthFailure: false,
    },
  );
};

export const deregister = () => post<object, DefaultSuccessPayload>("/deregister", {});

export const reportLastLogin = () => post<object, DefaultSuccessPayload>("/report_last_login", {});

export const checkAppVersion = async (
  clientVersion: string,
): Promise<Result<AppVersionInfo, Error>> => {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };

  const payload: AppVersionCheckPayload = {
    client_version: clientVersion,
  };

  const body = JSON.stringify(payload);
  const url = `${API_URL}/v0/app_version`;

  const responseResult = await ResultAsync.fromPromise(
    nativePost(url, body, headers, 30),
    (e) => e as Error,
  );

  if (responseResult.isErr()) {
    return err(responseResult.error);
  }

  const response = responseResult.value;

  if (response.status < 200 || response.status >= 300) {
    return err(buildApiError(response.status, response.body));
  }

  if (!response.body) {
    return err(new Error("Empty response body from app_version"));
  }

  const parseResult = Result.fromThrowable(
    () => JSON.parse(response.body) as AppVersionInfo,
    (e) => new Error(`Failed to parse JSON response: ${(e as Error).message}`),
  )();

  if (parseResult.isErr()) {
    return err(parseResult.error);
  }

  return ok(parseResult.value);
};
