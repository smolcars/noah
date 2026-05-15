import { Pressable, ScrollView, View, Switch, Image } from "react-native";
import Constants from "expo-constants";
import { useWalletStore } from "../store/walletStore";
import { useBiometrics } from "../hooks/useBiometrics";
import { ACTIVE_WALLET_CONFIG, PLATFORM, hasGooglePlayServices } from "../constants";
import { useServerStore } from "../store/serverStore";
import { useTransactionStore } from "../store/transactionStore";
import { APP_VARIANT } from "../config";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Text } from "../components/ui/text";
import React, { useState, useEffect, useRef } from "react";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { OnboardingStackParamList, SettingsStackParamList } from "../Navigators";
import Icon from "@react-native-vector-icons/ionicons";
import { useAutoBoardThreshold, useDeleteWallet, useSuspendWallet } from "../hooks/useWallet";
import { useExportDatabase } from "../hooks/useExportDatabase";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { copyToClipboard } from "../lib/clipboardUtils";
import { ConfirmationDialog, DangerZoneRow } from "../components/ConfirmationDialog";
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { AlertTriangle, CheckCircle } from "lucide-react-native";
import { useDeriveKeyPairFromMnemonic } from "~/hooks/useCrypto";
import logoImageDark from "../../assets/1024_no_background.png";
import logoImageLight from "../../assets/All_Files/light_dark_tinted/icon_clear_tinted_ios.png";
import { COLORS } from "~/lib/styleConstants";
import { useIconColor, useTheme } from "~/hooks/useTheme";
import { FeedbackModal } from "~/components/FeedbackModal";
import { performServerRegistration } from "../lib/server";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { revokeMailboxAuthorization } from "~/lib/api";
import { formatAutoBoardThreshold } from "~/lib/autoBoarding";

type Setting = {
  id:
    | "bitcoind"
    | "ark"
    | "esplora"
    | "showMnemonic"
    | "showLogs"
    | "staticVtxoPubkey"
    | "resetRegistration"
    | "backup"
    | "arkInfo"
    | "vtxos"
    | "feedback"
    | "unifiedPush"
    | "debug";
  title: string;
  value?: string;
  description?: string;
  isPressable: boolean;
};

const CopyableSettingRow = ({ label, value }: { label: string; value: string }) => {
  const [copied, setCopied] = useState(false);

  const onCopy = async () => {
    await copyToClipboard(value, {
      onCopy: () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      },
    });
  };

  return (
    <Pressable
      onPress={onCopy}
      className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center"
    >
      <View className="flex-1">
        <Label className="text-foreground text-lg">{label}</Label>
        <Text className={`text-base mt-1 text-muted-foreground`}>{copied ? "Copied!" : value}</Text>
      </View>
      {copied && <Icon name="checkmark-circle" size={24} color={COLORS.BITCOIN_ORANGE} />}
    </Pressable>
  );
};

const SettingsScreen = () => {
  const iconColor = useIconColor();
  const { isDark } = useTheme();
  const logoImage = isDark ? logoImageDark : logoImageLight;
  const [confirmText, setConfirmText] = useState("");
  const [showFeedback, setShowFeedback] = useState(false);
  const [copiedLightningAddress, setCopiedLightningAddress] = useState(false);
  const {
    isInitialized,
    setBiometricsEnabled,
    isDebugModeEnabled,
    setDebugModeEnabled,
    isWalletSuspended,
  } = useWalletStore();
  const { authenticate, checkAvailability, isBiometricsEnabled } = useBiometrics();
  const suspendWalletMutation = useSuspendWallet();
  const [versionTapCount, setVersionTapCount] = useState(0);
  const {
    lightningAddress,
    resetRegistration,
    isMailboxAuthorizationEnabled,
    setMailboxAuthorizationExpiry,
    setMailboxAuthorizationEnabled,
  } = useServerStore();
  const { isAutoBoardingEnabled, setAutoBoardingEnabled } = useTransactionStore();
  const {
    data: autoBoardThreshold,
    isError: isAutoBoardThresholdError,
    isLoading: isAutoBoardThresholdLoading,
  } = useAutoBoardThreshold(isInitialized);
  const [showResetSuccess, setShowResetSuccess] = useState(false);
  const [resetError, setResetError] = useState<string | null>(null);
  const [showMailboxSuccess, setShowMailboxSuccess] = useState(false);
  const [mailboxError, setMailboxError] = useState<string | null>(null);
  const [isMailboxTogglePending, setIsMailboxTogglePending] = useState(false);
  const [isBiometricsAvailable, setIsBiometricsAvailable] = useState(false);
  const deleteWalletMutation = useDeleteWallet();
  const { isExporting, showExportSuccess, showExportError, exportError, exportDatabase } =
    useExportDatabase();
  const { data: derivedKeyPair } = useDeriveKeyPairFromMnemonic();
  const tabBarHeight = useBottomTabBarHeight();
  const { bottom: safeBottomInset } = useSafeAreaInsets();

  const navigation =
    useNavigation<NativeStackNavigationProp<SettingsStackParamList & OnboardingStackParamList>>();

  const autoBoardDescription = isAutoBoardThresholdError
    ? "Auto-board threshold unavailable"
    : isAutoBoardThresholdLoading || autoBoardThreshold === undefined
      ? "Loading auto-board threshold..."
      : `Automatically board to Ark when onchain balance reaches ${formatAutoBoardThreshold(autoBoardThreshold)}`;

  useEffect(() => {
    const check = async () => {
      const { available } = await checkAvailability();
      setIsBiometricsAvailable(available);
    };
    check();
  }, [checkAvailability]);

  const versionTapTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  const handleVersionTap = () => {
    if (isDebugModeEnabled) return;

    if (versionTapTimeoutRef.current) {
      clearTimeout(versionTapTimeoutRef.current);
    }

    const newCount = versionTapCount + 1;
    setVersionTapCount(newCount);

    if (newCount >= 5) {
      setDebugModeEnabled(true);
      setVersionTapCount(0);
    } else {
      versionTapTimeoutRef.current = setTimeout(() => {
        setVersionTapCount(0);
      }, 2000);
    }
  };

  const handleBiometricsToggle = async (value: boolean) => {
    const promptMessage = value
      ? "Authenticate to enable biometrics"
      : "Authenticate to disable biometrics";
    const result = await authenticate(promptMessage);
    if (result.isOk()) {
      setBiometricsEnabled(value);
    }
  };

  const handleMailboxAuthorizationToggle = async (value: boolean) => {
    if (isMailboxTogglePending) {
      return;
    }

    setMailboxError(null);
    setShowMailboxSuccess(false);
    setIsMailboxTogglePending(true);

    if (!value) {
      const result = await revokeMailboxAuthorization();
      if (result.isErr()) {
        setMailboxError(result.error.message || "Failed to revoke mailbox authorization");
        setTimeout(() => {
          setMailboxError(null);
        }, 3000);
        setIsMailboxTogglePending(false);
        return;
      }

      setMailboxAuthorizationExpiry(null);
    }

    setMailboxAuthorizationEnabled(value);
    setShowMailboxSuccess(true);
    setTimeout(() => {
      setShowMailboxSuccess(false);
    }, 3000);
    setIsMailboxTogglePending(false);
  };

  const handlePress = (item: Setting) => {
    if (!item.isPressable) return;

    if (item.id === "showMnemonic") {
      navigation.navigate("Mnemonic", { fromOnboarding: false });
    } else if (item.id === "showLogs") {
      navigation.navigate("Logs");
    } else if (item.id === "resetRegistration") {
      // This is handled by the AlertDialog now
    } else if (item.id === "backup") {
      navigation.navigate("BackupSettings");
    } else if (item.id === "arkInfo") {
      navigation.navigate("ArkInfo");
    } else if (item.id === "vtxos") {
      navigation.navigate("VTXOs");
    } else if (item.id === "feedback") {
      setShowFeedback(true);
    } else if (item.id === "unifiedPush") {
      navigation.navigate("UnifiedPush", { fromOnboarding: false });
    } else if (item.id === "debug") {
      navigation.navigate("Debug");
    }
  };

  const infoData: Setting[] = [];
  const walletData: Setting[] = [];
  const debugData: Setting[] = [];

  if (isInitialized && derivedKeyPair?.public_key) {
    infoData.push({
      id: "staticVtxoPubkey",
      title: "Public Key",
      value: derivedKeyPair.public_key,
      isPressable: false,
    });
  }

  if (isInitialized) {
    infoData.push({
      id: "arkInfo",
      title: "Ark Info",
      description: "View Ark server parameters and limits.",
      isPressable: true,
    });
  }

  if (APP_VARIANT === "regtest") {
    infoData.push(
      {
        id: "bitcoind",
        title: "Bitcoind RPC",
        value: ACTIVE_WALLET_CONFIG.config?.bitcoind,
        isPressable: false,
      },
      {
        id: "ark",
        title: "Ark Server",
        value: ACTIVE_WALLET_CONFIG.config?.ark,
        isPressable: false,
      },
    );
  } else {
    infoData.push(
      {
        id: "esplora",
        title: "Esplora Server",
        value: ACTIVE_WALLET_CONFIG.config?.esplora,
        isPressable: false,
      },
      {
        id: "ark",
        title: "Ark Server",
        value: ACTIVE_WALLET_CONFIG.config?.ark,
        isPressable: false,
      },
    );
  }

  if (isInitialized) {
    walletData.push({
      id: "showMnemonic",
      title: "Show Seed Phrase",
      description:
        "Never share your seed phrase with anyone. It is important to keep it safe and secure.",
      isPressable: true,
    });
    walletData.push({
      id: "vtxos",
      title: "Show VTXOs",
      description: "VTXOs are to Ark like UTXOs are to Bitcoin",
      isPressable: true,
    });
    walletData.push({
      id: "backup",
      title: "Backup & Restore",
      description: "Automatically or manually backup your wallet after encrypting it.",
      isPressable: true,
    });

    if (!hasGooglePlayServices()) {
      walletData.push({
        id: "unifiedPush",
        title: "UnifiedPush Setup",
        description: "Configure push notifications using UnifiedPush",
        isPressable: true,
      });
    }

    debugData.push({
      id: "showLogs",
      title: "Show Logs",
      description: "View application logs for debugging purposes",
      isPressable: true,
    });
    debugData.push({
      id: "resetRegistration",
      title: "Reset Server Registration",
      description: "Clear your registration with the server. You will need to register again.",
      isPressable: true,
    });
    debugData.push({
      id: "feedback",
      title: "Send Feedback",
      description: "Report bugs or share feedback with the Noah team",
      isPressable: true,
    });
    if (isDebugModeEnabled) {
      debugData.push({
        id: "debug",
        title: "Debug Screen",
        description: "Advanced debug actions for developers",
        isPressable: true,
      });
    }
  }

  const renderSettingItem = (item: Setting) => {
    if (
      item.id === "staticVtxoPubkey" ||
      item.id === "ark" ||
      item.id === "esplora" ||
      item.id === "bitcoind"
    ) {
      return <CopyableSettingRow key={item.id} label={item.title} value={item.value!} />;
    }

    if (item.id === "resetRegistration") {
      return (
        <ConfirmationDialog
          key={item.id}
          trigger={
            <DangerZoneRow
              title={item.title}
              description="Attempt to reset the connection with our server if you're experiencing issues."
              isPressable={item.isPressable}
              onPress={() => {}}
            />
          }
          title="Reset Server Registration"
          description="Are you sure you want to reset your server registration? This will not delete your wallet, but you will need to register with the server again."
          onConfirm={async () => {
            resetRegistration();
            setResetError(null);
            setShowResetSuccess(false);
            const result = await performServerRegistration(null);
            if (result.isOk()) {
              setShowResetSuccess(true);
              setTimeout(() => {
                setShowResetSuccess(false);
              }, 3000);
            } else {
              setResetError(result.error.message || "Failed to reset registration");
              setTimeout(() => {
                setResetError(null);
              }, 3000);
            }
          }}
        />
      );
    }
    return (
      <Pressable
        key={item.id}
        onPress={() => handlePress(item)}
        disabled={!item.isPressable}
        className="flex-row justify-between items-center p-4 border-b border-border bg-card rounded-lg mb-2"
      >
        <View className="flex-1">
          <Label className="text-foreground text-lg">{item.title}</Label>
          {item.value && <Text className="text-muted-foreground text-base mt-1">{item.value}</Text>}
          {item.description && (
            <Text className="text-muted-foreground text-base mt-1">{item.description}</Text>
          )}
        </View>
        {item.isPressable && <Icon name="chevron-forward-outline" size={24} color={iconColor} />}
      </Pressable>
    );
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background" style={{ paddingBottom: 0 }}>
      <View className="px-4 pt-4">
        <View className="flex-row items-center mb-4">
          <Pressable onPress={() => navigation.goBack()} className="mr-4">
            <Icon name="arrow-back-outline" size={24} color={iconColor} />
          </Pressable>
          <Text className="text-2xl font-bold text-foreground">Settings</Text>
        </View>

        {showResetSuccess && (
          <Alert icon={CheckCircle} className="mb-4">
            <AlertTitle>Success!</AlertTitle>
            <AlertDescription>Server registration has been reset.</AlertDescription>
          </Alert>
        )}
        {resetError && (
          <Alert icon={AlertTriangle} variant="destructive" className="mb-4">
            <AlertTitle>Reset Failed!</AlertTitle>
            <AlertDescription>{resetError}</AlertDescription>
          </Alert>
        )}
        {showMailboxSuccess && (
          <Alert icon={CheckCircle} className="mb-4">
            <AlertTitle>Mailbox Access Updated!</AlertTitle>
            <AlertDescription>
              {isMailboxAuthorizationEnabled
                ? "Mailbox authorization will be granted again shortly."
                : "Mailbox authorization has been revoked."}
            </AlertDescription>
          </Alert>
        )}
        {mailboxError && (
          <Alert icon={AlertTriangle} variant="destructive" className="mb-4">
            <AlertTitle>Mailbox Update Failed!</AlertTitle>
            <AlertDescription>{mailboxError}</AlertDescription>
          </Alert>
        )}
        {showExportSuccess && (
          <Alert icon={CheckCircle} className="mb-4">
            <AlertTitle>Export Complete!</AlertTitle>
            <AlertDescription>Database has been exported successfully.</AlertDescription>
          </Alert>
        )}
        {showExportError && (
          <Alert icon={AlertTriangle} variant="destructive" className="mb-4">
            <AlertTitle>Export Failed!</AlertTitle>
            <AlertDescription>{exportError}</AlertDescription>
          </Alert>
        )}
      </View>
      <ScrollView
        className="flex-1 px-4"
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{
          paddingBottom: safeBottomInset + (PLATFORM === "android" ? 0 : tabBarHeight),
        }}
      >
        <View className="items-center mb-6">
          <Pressable onPress={() => navigation.navigate("NoahStory")}>
            <Image
              source={logoImage}
              style={{ width: 120, height: 120, borderRadius: 12 }}
              resizeMode="contain"
            />
          </Pressable>
        </View>

        {(lightningAddress || infoData.length > 0) && (
          <View className="mb-6">
            <Text
              className="text-lg font-bold text-foreground mb-2"
              style={{ color: COLORS.BITCOIN_ORANGE }}
            >
              Info
            </Text>
            {lightningAddress && (
              <Pressable
                onPress={() => navigation.navigate("LightningAddress", { fromOnboarding: false })}
                onLongPress={async () => {
                  await copyToClipboard(lightningAddress, {
                    onCopy: () => {
                      setCopiedLightningAddress(true);
                      setTimeout(() => setCopiedLightningAddress(false), 2000);
                    },
                  });
                }}
                className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center"
              >
                <View className="flex-1">
                  <View className="flex-row items-center">
                    <Label className="text-foreground text-lg">Lightning Address</Label>
                    <Text className="text-xs text-zinc-500 ml-2">(Long press to copy)</Text>
                  </View>
                  <Text className="text-base mt-1 text-muted-foreground">
                    {copiedLightningAddress ? "Copied!" : lightningAddress}
                  </Text>
                </View>
                {copiedLightningAddress ? (
                  <Icon name="checkmark-circle" size={24} color={COLORS.BITCOIN_ORANGE} />
                ) : (
                  <Icon name="chevron-forward-outline" size={24} color={iconColor} />
                )}
              </Pressable>
            )}
            {infoData.map(renderSettingItem)}
          </View>
        )}

        {walletData.length > 0 && (
          <View className="mb-6">
            <Text
              className="text-lg font-bold text-foreground mb-2"
              style={{ color: COLORS.BITCOIN_ORANGE }}
            >
              Wallet
            </Text>
            {walletData.map(renderSettingItem)}
            <View className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center">
              <View className="flex-1">
                <Label className="text-foreground text-lg">Auto-Board to Ark</Label>
                <Text className="text-base mt-1 text-muted-foreground">
                  {autoBoardDescription}
                </Text>
              </View>
              <Switch
                value={isAutoBoardingEnabled}
                onValueChange={setAutoBoardingEnabled}
                trackColor={{ false: "#767577", true: "#F7931A" }}
                thumbColor={isAutoBoardingEnabled ? "#ffffff" : "#f4f3f4"}
              />
            </View>
            {isBiometricsAvailable && (
              <View className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center">
                <View className="flex-1">
                  <Label className="text-foreground text-lg">Biometric Authentication</Label>
                  <Text className="text-base mt-1 text-muted-foreground">
                    Require biometric authentication to unlock your wallet
                  </Text>
                </View>
                <Switch
                  value={isBiometricsEnabled}
                  onValueChange={handleBiometricsToggle}
                  trackColor={{ false: "#767577", true: "#F7931A" }}
                  thumbColor={isBiometricsEnabled ? "#ffffff" : "#f4f3f4"}
                />
              </View>
            )}
            <View className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center">
              <View className="flex-1">
                <Label className="text-foreground text-lg">Mailbox Notifications</Label>
                <Text className="text-base mt-1 text-muted-foreground">
                  Allow Noah to monitor your Ark mailbox so it can wake this app to claim
                  Lightning payments in the background.
                </Text>
              </View>
              <Switch
                value={isMailboxAuthorizationEnabled}
                onValueChange={handleMailboxAuthorizationToggle}
                disabled={isMailboxTogglePending}
                trackColor={{ false: "#767577", true: "#F7931A" }}
                thumbColor={isMailboxAuthorizationEnabled ? "#ffffff" : "#f4f3f4"}
              />
            </View>
          </View>
        )}

        {debugData.length > 0 && (
          <View className="mb-6">
            <Text
              className="text-lg font-bold text-foreground mb-2"
              style={{ color: COLORS.BITCOIN_ORANGE }}
            >
              Debug
            </Text>
            {debugData.map(renderSettingItem)}
          </View>
        )}

        {isInitialized && (
          <View className="mb-6">
            <Text className="text-lg font-bold text-destructive mb-2">Danger Zone</Text>

            <View className="p-4 border-b border-border bg-card rounded-lg mb-4 flex-row justify-between items-center">
              <View className="flex-1">
                <Label className="text-foreground text-lg">Suspend Wallet</Label>
                <Text className="text-base mt-1 text-muted-foreground">
                  Disable all wallet operations. The wallet will be closed and won't load until
                  re-enabled.
                </Text>
              </View>
              <Switch
                value={isWalletSuspended}
                onValueChange={(value) => suspendWalletMutation.mutate(value)}
                disabled={suspendWalletMutation.isPending}
                trackColor={{ false: "#767577", true: "#dc2626" }}
                thumbColor={isWalletSuspended ? "#ffffff" : "#f4f3f4"}
              />
            </View>

            <ConfirmationDialog
              trigger={
                <Button variant="outline" disabled={isExporting} className="mb-4">
                  <Text>{isExporting ? "Exporting..." : "Export Database"}</Text>
                </Button>
              }
              title="Export Database"
              description="This will create an encrypted backup file containing your wallet's databases. Keep this file secure, as it can be used to restore your wallet."
              onConfirm={exportDatabase}
              confirmText="Export"
              confirmVariant="default"
            />

            <ConfirmationDialog
              trigger={
                <Button variant="destructive">
                  <Text>Delete Wallet</Text>
                </Button>
              }
              title="Delete Wallet"
              description={`This action is irreversible. To confirm, please type "delete" in the box below.`}
              onConfirm={() => {
                if (confirmText.toLowerCase() === "delete") {
                  deleteWalletMutation.mutate();
                }
              }}
              isConfirmDisabled={confirmText.toLowerCase() !== "delete"}
            >
              <Input
                value={confirmText}
                onChangeText={setConfirmText}
                placeholder='Type "delete" to confirm'
                className="h-12"
                autoCapitalize="none"
                autoCorrect={false}
              />
            </ConfirmationDialog>
          </View>
        )}

        <View className="items-center py-8 px-4">
          <Pressable onPress={handleVersionTap}>
            <Text className="text-muted-foreground text-sm mb-1">
              v{Constants.expoConfig?.version || "0.0.1"}
              {versionTapCount > 0 &&
                versionTapCount < 5 &&
                ` (${5 - versionTapCount} taps to unlock debug)`}
              {isDebugModeEnabled && " 🔧"}
            </Text>
          </Pressable>
          <Text className="text-muted-foreground text-sm">Made with ❤️ from Noah team</Text>
        </View>
      </ScrollView>

      <FeedbackModal visible={showFeedback} onClose={() => setShowFeedback(false)} />
    </NoahSafeAreaView>
  );
};

export default SettingsScreen;
