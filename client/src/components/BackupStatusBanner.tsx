import React, { useEffect, useState } from "react";
import { Pressable, View } from "react-native";
import { AlertCircle, CheckCircle, CloudUpload } from "lucide-react-native";
import { Text } from "~/components/ui/text";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { useBackupStore } from "~/store/backupStore";
import { useServerStore } from "~/store/serverStore";
import {
  AUTO_BACKUP_FRESHNESS_MS,
  AUTO_BACKUP_SUCCESS_BANNER_MS,
} from "~/constants";
import { BackupService } from "~/lib/backupService";
import logger from "~/lib/log";
import { redactSensitiveErrorMessage } from "~/lib/errorUtils";

const log = logger("BackupStatusBanner");

type BannerTone = "info" | "success" | "failed";

const formatBackupTime = (timestamp: number) =>
  new Date(timestamp).toLocaleTimeString([], {
    hour: "numeric",
    minute: "2-digit",
  });

export const BackupStatusBanner: React.FC = () => {
  const { isBackupEnabled } = useServerStore();
  const { lastBackupAt, lastBackupStatus, lastBackupError } = useBackupStore();
  const [isRetrying, setIsRetrying] = useState(false);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!lastBackupAt) {
      return undefined;
    }

    const now = Date.now();
    const successExpiresAt =
      lastBackupStatus === "success"
        ? lastBackupAt + AUTO_BACKUP_SUCCESS_BANNER_MS
        : null;
    const staleAt = lastBackupAt + AUTO_BACKUP_FRESHNESS_MS;

    let nextAt: number | null = null;
    if (successExpiresAt !== null && now < successExpiresAt) {
      nextAt = successExpiresAt;
    }

    if (now < staleAt) {
      nextAt = nextAt === null ? staleAt : Math.min(nextAt, staleAt);
    }

    if (nextAt === null) {
      return undefined;
    }

    const delayMs = Math.max(0, nextAt - now);
    const timeoutId = setTimeout(() => {
      setTick((value) => value + 1);
    }, delayMs);

    return () => clearTimeout(timeoutId);
  }, [lastBackupAt, lastBackupStatus, tick]);

  const now = Date.now();
  const isStale = !lastBackupAt || now - lastBackupAt > AUTO_BACKUP_FRESHNESS_MS;
  const showSuccess =
    lastBackupStatus === "success" &&
    lastBackupAt !== null &&
    now - lastBackupAt < AUTO_BACKUP_SUCCESS_BANNER_MS;
  const showInProgress = lastBackupStatus === "in_progress";
  const showFailed = lastBackupStatus === "failed";
  const showStale = !showInProgress && !showFailed && isStale;

  const { title, message, icon, tone, actionLabel } = (() => {
    if (showInProgress) {
      return {
        title: "Backing up wallet",
        message: "Running in background",
        icon: <NoahActivityIndicator size="small" />,
        tone: "info" as BannerTone,
        actionLabel: null,
      };
    }

    if (showFailed) {
      return {
        title: "Backup failed",
        message: lastBackupError ?? "An unknown error occurred while backing up.",
        icon: <AlertCircle size={16} color="#ef4444" />,
        tone: "failed" as BannerTone,
        actionLabel: "Retry",
      };
    }

    if (showSuccess) {
      return {
        title: "Backup completed",
        message: lastBackupAt ? `Last backup ${formatBackupTime(lastBackupAt)}` : "Saved",
        icon: <CheckCircle size={16} color="#22c55e" />,
        tone: "success" as BannerTone,
        actionLabel: null,
      };
    }

    const isFirstBackup = !lastBackupAt;
    return {
      title: isFirstBackup ? "Backup pending" : "Backup recommended",
      message: isFirstBackup
        ? "Waiting for first backup"
        : lastBackupAt
          ? `Last backup ${formatBackupTime(lastBackupAt)}`
          : "Backup is due",
      icon: <CloudUpload size={16} color="#60a5fa" />,
      tone: "info" as BannerTone,
      actionLabel: "Back up",
    };
  })();

  if (!isBackupEnabled && lastBackupStatus === "idle" && !lastBackupAt) {
    return null;
  }

  if (!showSuccess && !showInProgress && !showFailed && !showStale) {
    return null;
  }

  const handleBackupNow = () => {
    setIsRetrying(true);
    const backupService = new BackupService();
    void backupService
      .performBackup()
      .then((result) => {
        if (result.isErr()) {
          log.w("Manual backup failed", [redactSensitiveErrorMessage(result.error)]);
        }
      })
      .finally(() => {
        setIsRetrying(false);
      });
  };

  const containerClassName =
    tone === "failed"
      ? "border-red-500/30 bg-red-500/5"
      : tone === "success"
        ? "border-green-500/25 bg-green-500/5"
        : "border-border/70 bg-card/70";

  const iconContainerClassName =
    tone === "failed"
      ? "bg-red-500/10"
      : tone === "success"
        ? "bg-green-500/10"
        : "bg-blue-500/10";

  const actionTextClassName = tone === "failed" ? "text-red-500" : "text-foreground";

  return (
    <View className="mx-4 mt-3 mb-1">
      <View
        className={`min-h-[52px] flex-row items-center rounded-xl border px-3 py-2 ${containerClassName}`}
      >
        <View
          className={`mr-3 h-8 w-8 items-center justify-center rounded-full ${iconContainerClassName}`}
        >
          {icon}
        </View>

        <View className="min-w-0 flex-1">
          <Text className="text-sm font-semibold text-foreground" numberOfLines={1}>
            {title}
          </Text>
          <Text className="text-xs text-muted-foreground" numberOfLines={1}>
            {message}
          </Text>
        </View>

        {actionLabel && (
          <Pressable
            onPress={handleBackupNow}
            disabled={isRetrying}
            accessibilityRole="button"
            accessibilityLabel={actionLabel}
            className="ml-3 h-8 items-center justify-center rounded-full border border-border/70 bg-background/60 px-3 active:opacity-80 disabled:opacity-50"
          >
            <Text className={`text-xs font-semibold ${actionTextClassName}`}>
              {isRetrying ? "Working" : actionLabel}
            </Text>
          </Pressable>
        )}
      </View>
    </View>
  );
};
