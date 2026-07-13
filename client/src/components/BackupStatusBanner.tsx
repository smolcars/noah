import React, { useEffect, useState } from "react";
import { AlertCircle, CheckCircle } from "lucide-react-native";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { StatusBannerStrip, type StatusBannerTone } from "~/components/StatusBannerStrip";
import { useBackupStore } from "~/store/backupStore";
import { AUTO_BACKUP_SUCCESS_BANNER_MS } from "~/constants";
import { flushBackup } from "~/lib/backupCoordinator";
import logger from "~/lib/log";
import { redactSensitiveErrorMessage } from "~/lib/errorUtils";

const log = logger("BackupStatusBanner");

const formatBackupTime = (timestamp: number) =>
  new Date(timestamp).toLocaleTimeString([], {
    hour: "numeric",
    minute: "2-digit",
  });

export const BackupStatusBanner: React.FC = () => {
  const { backupPending, lastBackupAt, lastBackupStatus, lastBackupError } = useBackupStore();
  const [isRetrying, setIsRetrying] = useState(false);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!lastBackupAt) {
      return undefined;
    }

    const now = Date.now();
    const successExpiresAt = lastBackupAt + AUTO_BACKUP_SUCCESS_BANNER_MS;
    if (lastBackupStatus !== "success" || now >= successExpiresAt) {
      return undefined;
    }

    const delayMs = Math.max(0, successExpiresAt - now);
    const timeoutId = setTimeout(() => {
      setTick((value) => value + 1);
    }, delayMs);

    return () => clearTimeout(timeoutId);
  }, [lastBackupAt, lastBackupStatus, tick]);

  const now = Date.now();
  const showSuccess =
    !backupPending &&
    lastBackupStatus === "success" &&
    lastBackupAt !== null &&
    now - lastBackupAt < AUTO_BACKUP_SUCCESS_BANNER_MS;
  const showInProgress = lastBackupStatus === "in_progress";
  const showFailed = lastBackupStatus === "failed";

  const banner = (() => {
    if (showInProgress) {
      return {
        title: "Backing up wallet",
        message: "Running in background",
        icon: <NoahActivityIndicator size="small" />,
        tone: "info" as StatusBannerTone,
        actionLabel: null,
      };
    }

    if (showFailed) {
      return {
        title: "Backup failed",
        message: lastBackupError ?? "An unknown error occurred while backing up.",
        icon: <AlertCircle size={16} color="#ef4444" />,
        tone: "failed" as StatusBannerTone,
        actionLabel: "Retry",
      };
    }

    if (showSuccess) {
      return {
        title: "Backup completed",
        message: lastBackupAt ? `Last backup ${formatBackupTime(lastBackupAt)}` : "Saved",
        icon: <CheckCircle size={16} color="#22c55e" />,
        tone: "success" as StatusBannerTone,
        actionLabel: null,
      };
    }

    return null;
  })();

  if (!banner) {
    return null;
  }

  const { title, message, icon, tone, actionLabel } = banner;

  const handleBackupNow = () => {
    setIsRetrying(true);
    void flushBackup("manual", { requireEnabled: false })
      .then((result) => {
        if (result.isErr()) {
          log.w("Manual backup failed", [redactSensitiveErrorMessage(result.error)]);
        }
      })
      .finally(() => {
        setIsRetrying(false);
      });
  };

  return (
    <StatusBannerStrip
      className="mx-4 mt-3 mb-1"
      title={title}
      message={message}
      icon={icon}
      tone={tone}
      actionLabel={actionLabel}
      isActionLoading={isRetrying}
      onActionPress={actionLabel ? handleBackupNow : undefined}
    />
  );
};
