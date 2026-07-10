import { useState } from "react";
import Share from "react-native-share";
import RNFSTurbo from "react-native-fs-turbo";
import { ResultAsync } from "neverthrow";
import { CACHES_DIRECTORY_PATH } from "~/constants";
import { BackupService } from "~/lib/backupService";
import logger from "~/lib/log";

const log = logger("useExportDatabase");

export const useExportDatabase = () => {
  const [isExporting, setIsExporting] = useState(false);
  const [showExportSuccess, setShowExportSuccess] = useState(false);
  const [showExportError, setShowExportError] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const exportDatabase = async () => {
    setIsExporting(true);
    const now = new Date();
    const timestamp = now.toISOString().replace(/[:.]/g, "-");
    const filename = `noah_backup_${timestamp}.noahbackup`;
    const outputPath = `${CACHES_DIRECTORY_PATH}/${filename}`;
    try {
      const backupResult = await new BackupService().createEncryptedBackupFile(outputPath);
      if (backupResult.isErr()) {
        log.e("Error creating backup:", [backupResult.error]);
        setExportError("Failed to create backup file. Please try again.");
        setShowExportError(true);
        return;
      }

      const shareResult = await ResultAsync.fromPromise(
        Share.open({
          title: "Export Encrypted Backup",
          url: `file://${outputPath}`,
          type: "application/octet-stream",
          filename,
          subject: "Noah Wallet Encrypted Backup",
        }),
        (e) => e as Error,
      );

      if (shareResult.isErr()) {
        if (!shareResult.error.message.includes("User did not share")) {
          log.e("Error sharing backup file:", [shareResult.error]);
          setExportError("Failed to share the backup file. Please try again.");
          setShowExportError(true);
        }
      } else {
        setShowExportSuccess(true);
        setTimeout(() => setShowExportSuccess(false), 3000);
      }
    } finally {
      try {
        if (RNFSTurbo.exists(outputPath)) {
          await RNFSTurbo.unlink(outputPath);
        }
      } catch (error) {
        log.w("Failed to clean up exported backup file", [error]);
      }
      setIsExporting(false);
    }
  };

  return {
    isExporting,
    showExportSuccess,
    showExportError,
    exportError,
    exportDatabase,
  };
};
