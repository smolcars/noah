import { authorizeMailbox, registerWithServer, reportLastLogin } from "~/lib/api";
import logger from "~/lib/log";
import { useServerStore } from "~/store/serverStore";
import { useProfileStore } from "~/store/profileStore";
import { type Result, err, ok } from "neverthrow";
import { RegisterResponse } from "~/types/serverTypes";
import { peakAddress } from "~/lib/paymentsApi";
import {
  registerForPushNotificationsAsync,
  registerPushTokenWithServer,
  registerUnifiedPushTokenWithServer,
} from "~/lib/pushNotifications";
import { getMailboxAuthorization, loadWalletIfNeeded } from "~/lib/walletApi";
import {
  getCurrentDeviceInfo,
  getDeviceInfoFingerprint,
  shouldReportDeviceInfo,
} from "~/lib/deviceInfo";

const log = logger("server");
export const MAILBOX_AUTH_TTL_SECS = 89 * 24 * 60 * 60;

const recordDeviceInfoReport = (fingerprint: string) => {
  useServerStore.getState().setDeviceReportState(fingerprint, Date.now());
};

const applyServerRegistrationResult = (response: RegisterResponse) => {
  const { setRegisteredWithServer, setEmailAddress, setEmailVerified } = useServerStore.getState();
  const { setDisplayName } = useProfileStore.getState();
  const { lightning_address, display_name, email, is_email_verified } = response;

  setRegisteredWithServer(true, lightning_address, true);
  setDisplayName(display_name ?? "");
  setEmailAddress(email);
  setEmailVerified(is_email_verified);
};

export const performServerRegistration = async (
  ln_address: string | null,
  options?: { updateStore?: boolean },
): Promise<Result<RegisterResponse, Error>> => {
  const addressResult = await peakAddress(0);
  if (addressResult.isErr()) {
    log.e("Failed to generate Ark address for registration", [addressResult.error]);
    return err(addressResult.error);
  }
  const ark_address = addressResult.value.address;
  const deviceInfo = getCurrentDeviceInfo();
  const deviceInfoFingerprint = getDeviceInfoFingerprint(deviceInfo);

  // Register with server and pass user device information.
  const result = await registerWithServer({
    device_info: deviceInfo,
    ln_address,
    ark_address,
  });

  if (result.isErr()) {
    log.w("Failed to register with server", [result.error]);
    return result;
  }

  recordDeviceInfoReport(deviceInfoFingerprint);
  log.d("Successfully registered with server", [result.value.is_email_verified]);
  if (options?.updateStore ?? true) {
    applyServerRegistrationResult(result.value);
  }

  return result;
};

export const reportLastLoginForServer = async () => {
  const {
    isRegisteredWithServer,
    lastReportedDeviceFingerprint,
    lastDeviceReportAt,
    setDeviceReportState,
  } = useServerStore.getState();

  const deviceInfo = getCurrentDeviceInfo();
  const shouldIncludeDeviceInfo =
    isRegisteredWithServer &&
    shouldReportDeviceInfo(deviceInfo, lastReportedDeviceFingerprint, lastDeviceReportAt);

  const result = await reportLastLogin(shouldIncludeDeviceInfo ? { device_info: deviceInfo } : {});
  if (result.isOk() && shouldIncludeDeviceInfo) {
    setDeviceReportState(getDeviceInfoFingerprint(deviceInfo), Date.now());
  }

  return result;
};

export const registerPushNotificationsForServer = async (): Promise<Result<void, Error>> => {
  const tokenResult = await registerForPushNotificationsAsync();
  if (tokenResult.isErr()) {
    log.w("Failed to register for push notifications", [tokenResult.error]);
    return err(tokenResult.error);
  }

  const tokenPayload = tokenResult.value;
  if (tokenPayload.kind === "device_not_supported") {
    log.d("Skipping push notification registration on unsupported device");
    return ok(undefined);
  }

  if (tokenPayload.kind !== "success") {
    const error = new Error(
      `Push notification registration did not complete: ${tokenPayload.kind}`,
    );
    log.w("Push notification registration did not complete", [tokenPayload.kind]);
    return err(error);
  }

  const registerResult =
    tokenPayload.pushType === "unified"
      ? await registerUnifiedPushTokenWithServer(tokenPayload.pushToken)
      : await registerPushTokenWithServer(tokenPayload.pushToken);
  if (registerResult.isErr()) {
    log.w("Failed to register push token with server", [registerResult.error]);
    return err(registerResult.error);
  }

  return ok(undefined);
};

export const authorizeMailboxForServer = async (options?: {
  requestedExpiry?: number;
  shouldAbort?: () => boolean;
}): Promise<Result<number, Error>> => {
  const { isMailboxAuthorizationEnabled, setMailboxAuthorizationExpiry } =
    useServerStore.getState();
  if (!isMailboxAuthorizationEnabled) {
    return err(new Error("Mailbox authorization is disabled"));
  }

  if (options?.shouldAbort?.()) {
    return err(new Error("Mailbox authorization cancelled"));
  }

  const requestedExpiry =
    options?.requestedExpiry ?? Math.floor(Date.now() / 1000) + MAILBOX_AUTH_TTL_SECS;
  const mailboxAuthorizationResult = await getMailboxAuthorization(requestedExpiry);
  if (mailboxAuthorizationResult.isErr()) {
    log.w("Failed to generate mailbox authorization", [mailboxAuthorizationResult.error]);
    return err(mailboxAuthorizationResult.error);
  }

  if (options?.shouldAbort?.()) {
    return err(new Error("Mailbox authorization cancelled"));
  }

  const authorizeResult = await authorizeMailbox(mailboxAuthorizationResult.value);
  if (authorizeResult.isErr()) {
    log.w("Failed to store mailbox authorization on server", [authorizeResult.error]);
    return err(authorizeResult.error);
  }

  if (options?.shouldAbort?.()) {
    return err(new Error("Mailbox authorization cancelled"));
  }

  setMailboxAuthorizationExpiry(mailboxAuthorizationResult.value.expiry);
  return ok(mailboxAuthorizationResult.value.expiry);
};

export const resetAndReRegisterWithServer = async (): Promise<Result<RegisterResponse, Error>> => {
  useServerStore.getState().resetRegistration();

  const registrationResult = await performServerRegistration(null, { updateStore: false });
  if (registrationResult.isErr()) {
    return registrationResult;
  }

  const loadResult = await loadWalletIfNeeded();
  if (loadResult.isErr()) {
    log.w("Failed to load wallet before service registration", [loadResult.error]);
    return err(loadResult.error);
  }

  const pushResult = await registerPushNotificationsForServer();
  if (pushResult.isErr()) {
    return err(pushResult.error);
  }

  const mailboxResult = await authorizeMailboxForServer();
  if (mailboxResult.isErr()) {
    return err(mailboxResult.error);
  }

  applyServerRegistrationResult(registrationResult.value);
  return registrationResult;
};
