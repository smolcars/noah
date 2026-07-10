import React, { useState } from "react";
import { View, ScrollView, Pressable } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useBackupManager } from "../hooks/useBackupManager";
import { NoahSafeAreaView } from "../components/NoahSafeAreaView";
import { Text } from "../components/ui/text";
import { Label } from "../components/ui/label";
import { Alert, AlertTitle, AlertDescription } from "../components/ui/alert";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { CheckCircle } from "lucide-react-native";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import * as Haptics from "expo-haptics";
import { AlertCircle } from "lucide-react-native";
import { NativeSwitch } from "~/components/ui/native-switch";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";

export const BackupSettingsScreen = () => {
  const navigation = useNavigation();
  const iconColor = useIconColor();
  const {
    isBackupEnabled,
    setBackupEnabled,
    triggerBackup,
    listBackups,
    deleteBackup,
    isLoading,
    backupsList,
  } = useBackupManager();

  const [showBackups, setShowBackups] = useState(false);
  const [showSuccessAlert, setShowSuccessAlert] = useState(false);
  const [showErrorAlert, setShowErrorAlert] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <ScrollView contentContainerClassName="p-4 flex-1">
        <View className="flex-row items-center mb-8">
          <Pressable onPress={() => navigation.goBack()} className="mr-4">
            <Icon name="arrow-back-outline" size={24} color={iconColor} />
          </Pressable>
          <Text className="text-2xl font-bold text-foreground">Backup</Text>
        </View>
        <Text className="text-muted-foreground mb-8">
          Backups are encrypted with your seed phrase and stored securely on our servers. We can
          never access your funds or data.
        </Text>

        <View className="flex-row justify-between items-center p-4 border-b border-border bg-card rounded-lg mb-4">
          <Label className="text-foreground text-lg">Enable Automatic Backups</Label>
          <NativeSwitch
            value={isBackupEnabled}
            onValueChange={setBackupEnabled}
            disabled={isLoading}
          />
        </View>

        {showSuccessAlert && (
          <Alert icon={CheckCircle} className="mb-4">
            <AlertTitle>Backup Complete!</AlertTitle>
            <AlertDescription>Your wallet has been backed up successfully.</AlertDescription>
          </Alert>
        )}

        {showErrorAlert && (
          <Alert icon={AlertCircle} variant="destructive" className="mb-4">
            <AlertTitle>Backup Failed</AlertTitle>
            <AlertDescription>{errorMessage ?? "An unknown error occurred"}</AlertDescription>
          </Alert>
        )}

        <NativeNoahButton
          label="Backup Now"
          onPress={async () => {
            const result = await triggerBackup();
            if (result.isOk()) {
              await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
              setShowSuccessAlert(true);
              setTimeout(() => setShowSuccessAlert(false), 3000);
            } else {
              await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
              setErrorMessage(result.error.message);
              setShowErrorAlert(true);
              setTimeout(() => setShowErrorAlert(false), 5000);
            }
          }}
          className="mb-4"
          disabled={isLoading}
          fullWidth
        />

        <View className="mt-8">
          <NativeNoahSecondaryButton
            label="List Backups"
            onPress={async () => {
              const result = await listBackups();
              if (result.isOk()) {
                setShowBackups(true);
              } else {
                await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
                setErrorMessage(result.error.message);
                setShowErrorAlert(true);
                setTimeout(() => setShowErrorAlert(false), 5000);
              }
            }}
            className="mb-8"
            disabled={isLoading}
            fullWidth
          />

          {showBackups && backupsList && (
            <View className="mb-4 p-4 bg-card rounded-lg border border-border">
              <Text className="text-lg font-semibold mb-2">Available Backups</Text>
              {backupsList.length === 0 ? (
                <Text className="text-muted-foreground">No backups found</Text>
              ) : (
                backupsList.map((backup) => (
                  <View
                    key={backup.backup_id}
                    className="flex-row justify-between items-center py-2 border-b border-border"
                  >
                    <View>
                      <Text className="font-medium">Encrypted wallet snapshot</Text>
                      <Text className="text-sm text-muted-foreground">
                        {new Date(backup.created_at).toLocaleString()} -{" "}
                        {(backup.encrypted_size / 1024).toFixed(1)} KB
                      </Text>
                    </View>
                    <View className="flex-row gap-2">
                      <NativeNoahButton
                        label="Delete"
                        variant="destructive"
                        size="sm"
                        onPress={() => deleteBackup(backup.backup_id)}
                        disabled={isLoading}
                        width={88}
                      />
                    </View>
                  </View>
                ))
              )}
            </View>
          )}
        </View>
      </ScrollView>

      {isLoading && (
        <View className="absolute inset-0 bg-black/50 items-center justify-center">
          <View className="bg-card p-6 rounded-lg items-center">
            <NoahActivityIndicator size="large" />
            <Text className="text-foreground mt-4">Loading...</Text>
          </View>
        </View>
      )}
    </NoahSafeAreaView>
  );
};
