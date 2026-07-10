import { memo, useEffect, useState } from "react";
import { useSyncManager } from "~/hooks/useSyncManager";
import { useServerRegistration } from "~/hooks/useServerRegistration";
import { usePushNotifications } from "~/hooks/usePushNotifications";
import { useMailboxAuthorization } from "~/hooks/useMailboxAuthorization";
import { useBackupCoordinator } from "~/hooks/useBackupCoordinator";
import { reportLastLogin } from "~/lib/api";
import logger from "~/lib/log";
import { AutoBoardingService } from "~/components/AutoBoardingService";

const log = logger("AppServices");

const AppServices = memo(() => {
  const [isReady, setIsReady] = useState(false);

  // Initialize all app-level services here
  useSyncManager(60_000);
  useServerRegistration(isReady);
  useMailboxAuthorization(isReady);
  usePushNotifications(isReady);
  useBackupCoordinator(isReady);

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

  return <AutoBoardingService isReady={isReady} />;
});

AppServices.displayName = "AppServices";

export default AppServices;
