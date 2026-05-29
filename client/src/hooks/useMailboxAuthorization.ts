import { useEffect, useState } from "react";
import { loadWalletIfNeeded } from "~/lib/walletApi";
import logger from "~/lib/log";
import { useServerStore } from "~/store/serverStore";
import { authorizeMailboxForServer, MAILBOX_AUTH_TTL_SECS } from "~/lib/server";

const log = logger("useMailboxAuthorization");

const MAILBOX_AUTH_REFRESH_WINDOW_SECS = 7 * 24 * 60 * 60;

export const useMailboxAuthorization = (isReady: boolean) => {
  const [refreshTick, setRefreshTick] = useState(0);
  const {
    isRegisteredWithServer,
    mailboxAuthorizationExpiry,
    isMailboxAuthorizationEnabled,
  } = useServerStore();

  useEffect(() => {
    let isCancelled = false;
    const shouldAbort = () => {
      const { isMailboxAuthorizationEnabled: isEnabled, isRegisteredWithServer: isRegistered } =
        useServerStore.getState();
      return isCancelled || !isEnabled || !isRegistered;
    };

    const registerMailboxAuthorization = async () => {
      if (!isReady || !isRegisteredWithServer || !isMailboxAuthorizationEnabled) {
        return;
      }

      const now = Math.floor(Date.now() / 1000);
      if (
        mailboxAuthorizationExpiry &&
        mailboxAuthorizationExpiry > now + MAILBOX_AUTH_REFRESH_WINDOW_SECS
      ) {
        return;
      }

      const loadResult = await loadWalletIfNeeded();
      if (loadResult.isErr()) {
        log.w("Failed to load wallet before granting mailbox authorization", [loadResult.error]);
        return;
      }
      if (shouldAbort()) {
        return;
      }

      const requestedExpiry = now + MAILBOX_AUTH_TTL_SECS;
      const authorizeResult = await authorizeMailboxForServer({
        requestedExpiry,
        shouldAbort,
      });
      if (authorizeResult.isErr()) {
        if (!shouldAbort()) {
          log.w("Failed to grant mailbox authorization on server", [authorizeResult.error]);
        }
        return;
      }

      if (shouldAbort()) {
        return;
      }

      log.d("Successfully granted mailbox authorization", [authorizeResult.value]);
    };

    registerMailboxAuthorization();

    return () => {
      isCancelled = true;
    };
  }, [
    isReady,
    isRegisteredWithServer,
    isMailboxAuthorizationEnabled,
    mailboxAuthorizationExpiry,
    refreshTick,
  ]);

  useEffect(() => {
    if (
      !isReady ||
      !isRegisteredWithServer ||
      !isMailboxAuthorizationEnabled ||
      !mailboxAuthorizationExpiry
    ) {
      return;
    }

    const now = Math.floor(Date.now() / 1000);
    const refreshAt = mailboxAuthorizationExpiry - MAILBOX_AUTH_REFRESH_WINDOW_SECS;
    const delayMs = Math.max((refreshAt - now) * 1000, 0);

    const timeout = setTimeout(() => {
      setRefreshTick((tick) => tick + 1);
    }, delayMs);

    return () => {
      clearTimeout(timeout);
    };
  }, [
    isReady,
    isRegisteredWithServer,
    isMailboxAuthorizationEnabled,
    mailboxAuthorizationExpiry,
  ]);
};
