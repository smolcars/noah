import { memo, useEffect, useState } from "react";
import { useSyncManager } from "~/hooks/useSyncManager";
import { useServerRegistration } from "~/hooks/useServerRegistration";
import { usePushNotifications } from "~/hooks/usePushNotifications";
import { useMailboxAuthorization } from "~/hooks/useMailboxAuthorization";
import { useAutoBackup } from "~/hooks/useAutoBackup";
import { useTransactionStore } from "~/store/transactionStore";
import { useAutoBoardThreshold, useBalance } from "~/hooks/useWallet";
import { useBoardAllAmountArk } from "~/hooks/usePayments";
import { useAlert } from "~/contexts/AlertProvider";
import { reportLastLogin } from "~/lib/api";
import logger from "~/lib/log";
import { AUTO_BOARD_FLOOR_AMOUNT, formatAutoBoardThreshold } from "~/lib/autoBoarding";

const log = logger("AppServices");

const AppServices = memo(() => {
  const [isReady, setIsReady] = useState(false);
  const [hasReportedAutoBoardThresholdError, setHasReportedAutoBoardThresholdError] =
    useState(false);

  const { isAutoBoardingEnabled, hasAttemptedAutoBoarding, setHasAttemptedAutoBoarding } =
    useTransactionStore();
  const { data: balance } = useBalance();
  const { mutate: boardAllArk, isPending: isBoardingAll } = useBoardAllAmountArk();
  const { showAlert } = useAlert();
  const shouldLoadAutoBoardThreshold =
    isReady &&
    isAutoBoardingEnabled &&
    !!balance &&
    balance.onchain.confirmed >= AUTO_BOARD_FLOOR_AMOUNT &&
    !isBoardingAll &&
    !hasAttemptedAutoBoarding;
  const {
    data: autoBoardThreshold,
    error: autoBoardThresholdError,
    isError: isAutoBoardThresholdError,
  } = useAutoBoardThreshold(shouldLoadAutoBoardThreshold);

  // Initialize all app-level services here
  useSyncManager(60_000);
  useServerRegistration(isReady);
  useMailboxAuthorization(isReady);
  usePushNotifications(isReady);
  useAutoBackup(isReady);

  useEffect(() => {
    setIsReady(true);
  }, []);

  useEffect(() => {
    if (isReady) {
      reportLastLogin().then((result) => {
        if (result.isErr()) {
          log.w("Failed to report last login", [result.error]);
        }
      });
    }
  }, [isReady]);

  // Auto-boarding logic
  useEffect(() => {
    if (
      !isReady ||
      !isAutoBoardingEnabled ||
      !balance ||
      isBoardingAll ||
      hasAttemptedAutoBoarding
    ) {
      return;
    }

    const onchainConfirmedBalance = balance.onchain.confirmed;

    if (onchainConfirmedBalance < AUTO_BOARD_FLOOR_AMOUNT) {
      return;
    }

    if (isAutoBoardThresholdError) {
      if (!hasReportedAutoBoardThresholdError) {
        setHasReportedAutoBoardThresholdError(true);
        log.e("Auto-boarding failed to load Ark info", [autoBoardThresholdError]);
        showAlert({
          title: "Auto-Boarding Failed",
          description: "Unable to load Ark server info. Please try again later.",
        });
      }
      return;
    }

    if (hasReportedAutoBoardThresholdError) {
      setHasReportedAutoBoardThresholdError(false);
    }

    if (autoBoardThreshold === undefined || onchainConfirmedBalance < autoBoardThreshold) {
      return;
    }

    if (onchainConfirmedBalance >= autoBoardThreshold) {
      setHasAttemptedAutoBoarding(true);
      log.d("Auto-boarding triggered", [
        `Balance: ${onchainConfirmedBalance} sats`,
        `Threshold: ${autoBoardThreshold} sats`,
      ]);

      boardAllArk(undefined, {
        onSuccess: () => {
          log.d("Auto-boarding successful");

          showAlert({
            title: "Auto-Boarded to Ark",
            description: `Successfully boarded ${formatAutoBoardThreshold(onchainConfirmedBalance)} to Ark.`,
          });
        },
        onError: (error) => {
          log.e("Auto-boarding failed", [error]);
        },
      });
    }
  }, [
    isReady,
    isAutoBoardingEnabled,
    hasAttemptedAutoBoarding,
    balance,
    autoBoardThreshold,
    autoBoardThresholdError,
    hasReportedAutoBoardThresholdError,
    isAutoBoardThresholdError,
    boardAllArk,
    isBoardingAll,
    setHasAttemptedAutoBoarding,
    showAlert,
  ]);

  return null;
});

AppServices.displayName = "AppServices";

export default AppServices;
