import {
  Image,
  Keyboard,
  Linking,
  Pressable,
  ScrollView,
  View,
} from "react-native";
import Constants from "expo-constants";
import * as Haptics from "expo-haptics";
import { useWalletStore } from "../store/walletStore";
import { useBiometrics } from "../hooks/useBiometrics";
import { PLATFORM, shouldUseUnifiedPush } from "../constants";
import { useServerStore } from "../store/serverStore";
import { useTransactionStore } from "../store/transactionStore";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Text } from "../components/ui/text";
import React, { useState, useEffect, useRef } from "react";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { OnboardingStackParamList, SettingsStackParamList } from "../Navigators";
import Icon from "@react-native-vector-icons/ionicons";
import { useAutoBoardThreshold, useDeleteWallet, useSuspendWallet } from "../hooks/useWallet";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { ConfirmationDialog, DangerZoneRow } from "../components/ConfirmationDialog";
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { AlertTriangle, CheckCircle } from "lucide-react-native";
import logoImageDark from "../../assets/1024_no_background.png";
import logoImageLight from "../../assets/All_Files/light_dark_tinted/icon_clear_tinted_ios.png";
import { COLORS } from "~/lib/styleConstants";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { useIconColor, useTheme } from "~/hooks/useTheme";
import { resetAndReRegisterWithServer } from "../lib/server";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { revokeMailboxAuthorization } from "~/lib/api";
import { AUTO_BOARD_ONCHAIN_BUFFER_AMOUNT, formatAutoBoardThreshold } from "~/lib/autoBoarding";
import { useProfileStore } from "~/store/profileStore";
import { getFiatCurrencyInfo } from "~/lib/fiatCurrency";
import { getBitcoinAmountUnitInfo } from "~/lib/bitcoinAmount";
import { NativeSwitch } from "~/components/ui/native-switch";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import {
  GitHubBrandIcon,
  GITHUB_URL,
  TelegramBrandIcon,
  TELEGRAM_SUPPORT_URL,
} from "~/components/BrandIcons";
import { APP_VARIANT } from "~/config";
import { getDefaultEsploraEndpoint } from "~/lib/esplora";
import { useEsploraStore } from "~/store/esploraStore";

type Setting = {
  id:
    | "profile"
    | "currency"
    | "bitcoinUnit"
    | "esplora"
    | "showMnemonic"
    | "showLogs"
    | "resetRegistration"
    | "backup"
    | "arkInfo"
    | "vtxos"
    | "emergencyExit"
    | "feedback"
    | "unifiedPush"
    | "exportDatabase"
    | "debug";
  title: string;
  value?: string;
  description?: string;
  isPressable: boolean;
};

const SettingsScreen = () => {
  const iconColor = useIconColor();
  const { isDark } = useTheme();
  const logoImage = isDark ? logoImageDark : logoImageLight;
  const [confirmText, setConfirmText] = useState("");
  const [isDeleteWalletDialogOpen, setIsDeleteWalletDialogOpen] = useState(false);
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
    isMailboxAuthorizationEnabled,
    setMailboxAuthorizationExpiry,
    setMailboxAuthorizationEnabled,
  } = useServerStore();
  const { isAutoBoardingEnabled, setAutoBoardingEnabled } = useTransactionStore();
  const preferredCurrency = useProfileStore((state) => state.preferredCurrency);
  const preferredCurrencyInfo = getFiatCurrencyInfo(preferredCurrency);
  const bitcoinAmountUnit = useProfileStore((state) => state.bitcoinAmountUnit);
  const bitcoinAmountUnitInfo = getBitcoinAmountUnitInfo(bitcoinAmountUnit);
  const endpointOverride = useEsploraStore((state) => state.endpointOverride);
  const effectiveEsploraEndpoint = endpointOverride ?? getDefaultEsploraEndpoint();
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
  const tabBarHeight = useBottomTabBarHeight();
  const { bottom: safeBottomInset } = useSafeAreaInsets();

  const navigation =
    useNavigation<NativeStackNavigationProp<SettingsStackParamList & OnboardingStackParamList>>();

  const autoBoardDescription = isAutoBoardThresholdError
    ? "Auto-board threshold unavailable"
    : isAutoBoardThresholdLoading || autoBoardThreshold === undefined
      ? "Loading auto-board threshold..."
      : `Ask to board to Ark when onchain balance can cover ${formatAutoBoardThreshold(autoBoardThreshold)}, estimated fees, and a ${formatAutoBoardThreshold(AUTO_BOARD_ONCHAIN_BUFFER_AMOUNT)} reserve.`;

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

  const handleTelegramPress = () => {
    Linking.openURL(TELEGRAM_SUPPORT_URL);
  };

  const handleGithubPress = () => {
    Linking.openURL(GITHUB_URL);
  };

  const closeDeleteWalletSheet = () => {
    Keyboard.dismiss();
    setIsDeleteWalletDialogOpen(false);
    setConfirmText("");
  };

  const handleCancelDeleteWallet = async () => {
    await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    closeDeleteWalletSheet();
  };

  const handleDeleteWallet = async () => {
    if (confirmText.trim().toLowerCase() !== "delete" || deleteWalletMutation.isPending) {
      return;
    }

    await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    deleteWalletMutation.mutate();
    closeDeleteWalletSheet();
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

    if (item.id === "profile") {
      navigation.navigate("Profile");
    } else if (item.id === "currency") {
      navigation.navigate("Currency");
    } else if (item.id === "bitcoinUnit") {
      navigation.navigate("BitcoinUnit");
    } else if (item.id === "esplora") {
      navigation.navigate("Esplora");
    } else if (item.id === "showMnemonic") {
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
    } else if (item.id === "emergencyExit") {
      navigation.navigate("UnilateralExit");
    } else if (item.id === "feedback") {
      navigation.navigate("Feedback");
    } else if (item.id === "unifiedPush") {
      navigation.navigate("UnifiedPush", { fromOnboarding: false });
    } else if (item.id === "exportDatabase") {
      navigation.navigate("ExportDatabase");
    } else if (item.id === "debug") {
      navigation.navigate("Debug");
    }
  };

  const profileData: Setting[] = [];
  const infoData: Setting[] = [];
  const walletData: Setting[] = [];
  const debugData: Setting[] = [];

  if (isInitialized) {
    profileData.push({
      id: "profile",
      title: "Profile",
      description: "Manage your name, Lightning address, emergency email, and public key.",
      isPressable: true,
    });
    profileData.push({
      id: "currency",
      title: "Currency",
      value: `${preferredCurrencyInfo.code} · ${preferredCurrencyInfo.name}`,
      description: "Choose the fiat currency used for balances and payment amounts.",
      isPressable: true,
    });
    profileData.push({
      id: "bitcoinUnit",
      title: "Bitcoin Unit",
      value: `${bitcoinAmountUnitInfo.title} · ${bitcoinAmountUnitInfo.value}`,
      description: "Choose how bitcoin amounts are displayed.",
      isPressable: true,
    });

    infoData.push({
      id: "arkInfo",
      title: "Ark Info",
      description: "View Ark server, wallet, and explorer configuration.",
      isPressable: true,
    });
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
      id: "emergencyExit",
      title: "Emergency Exit",
      description: "Recover funds if the Ark server is unavailable.",
      isPressable: true,
    });
    walletData.push({
      id: "backup",
      title: "Backup & Restore",
      description: "Automatically or manually backup your wallet after encrypting it.",
      isPressable: true,
    });

    if (APP_VARIANT !== "regtest") {
      walletData.push({
        id: "esplora",
        title: "Esplora API",
        value: endpointOverride ? "Custom" : "Noah default",
        description: effectiveEsploraEndpoint ?? "Not configured",
        isPressable: true,
      });
    }

    if (shouldUseUnifiedPush()) {
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
            setResetError(null);
            setShowResetSuccess(false);
            const result = await resetAndReRegisterWithServer();
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
          <NativeNoahBackButton
            onPress={() => navigation.goBack()}
            className="mr-3"
            testID="settings-back-button"
          />
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

        {profileData.length > 0 && (
          <View className="mb-6">
            <Text
              className="text-lg font-bold text-foreground mb-2"
              style={{ color: COLORS.BITCOIN_ORANGE }}
            >
              Account
            </Text>
            {profileData.map(renderSettingItem)}
          </View>
        )}

        {infoData.length > 0 && (
          <View className="mb-6">
            <Text
              className="text-lg font-bold text-foreground mb-2"
              style={{ color: COLORS.BITCOIN_ORANGE }}
            >
              Info
            </Text>
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
                <Text className="text-base mt-1 text-muted-foreground">{autoBoardDescription}</Text>
              </View>
              <NativeSwitch value={isAutoBoardingEnabled} onValueChange={setAutoBoardingEnabled} />
            </View>
            {isBiometricsAvailable && (
              <View className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center">
                <View className="flex-1">
                  <Label className="text-foreground text-lg">Biometric Authentication</Label>
                  <Text className="text-base mt-1 text-muted-foreground">
                    Require biometric authentication to unlock your wallet
                  </Text>
                </View>
                <NativeSwitch value={isBiometricsEnabled} onValueChange={handleBiometricsToggle} />
              </View>
            )}
            <View className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center">
              <View className="flex-1">
                <Label className="text-foreground text-lg">Mailbox Notifications</Label>
                <Text className="text-base mt-1 text-muted-foreground">
                  Allow Noah to monitor your Ark mailbox so it can wake this app to claim Lightning
                  payments in the background.
                </Text>
              </View>
              <NativeSwitch
                value={isMailboxAuthorizationEnabled}
                onValueChange={handleMailboxAuthorizationToggle}
                disabled={isMailboxTogglePending}
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
              <NativeSwitch
                value={isWalletSuspended}
                onValueChange={(value) => suspendWalletMutation.mutate(value)}
                disabled={suspendWalletMutation.isPending}
                tone="destructive"
              />
            </View>

            <DangerZoneRow
              title="Export Database"
              description="Create an encrypted backup file containing your wallet database."
              isPressable
              onPress={() => navigation.navigate("ExportDatabase")}
            />

            <NativeNoahButton
              label="Delete Wallet"
              variant="destructive"
              onPress={() => setIsDeleteWalletDialogOpen(true)}
              fullWidth
            />
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
          <View className="mt-3 flex-row items-center justify-center gap-4">
            <Pressable
              onPress={handleTelegramPress}
              className="h-10 w-10 items-center justify-center rounded-full bg-card"
              accessibilityRole="link"
              accessibilityLabel="Open Telegram support chat"
            >
              <TelegramBrandIcon size={28} />
            </Pressable>
            <Pressable
              onPress={handleGithubPress}
              className="h-10 w-10 items-center justify-center rounded-full bg-card"
              accessibilityRole="link"
              accessibilityLabel="Open Noah GitHub repository"
            >
              <GitHubBrandIcon size={28} color={iconColor} />
            </Pressable>
          </View>
        </View>
      </ScrollView>
      <AppBottomSheet
        isOpen={isDeleteWalletDialogOpen}
        onClose={closeDeleteWalletSheet}
        detents={[0, "content"]}
        avoidKeyboard
      >
        <View
          className="gap-4 pt-2"
          style={{ paddingBottom: Math.max(safeBottomInset, 16) + 12 }}
        >
          <View className="gap-2">
            <Text className="text-xl font-bold text-foreground">Delete Wallet</Text>
            <Text className="text-base text-muted-foreground">
              This action is irreversible. To confirm, please type "delete" in the box below.
            </Text>
          </View>

          <Input
            value={confirmText}
            onChangeText={setConfirmText}
            placeholder='Type "delete" to confirm'
            className="h-12"
            autoCapitalize="none"
            autoCorrect={false}
            returnKeyType="done"
            onSubmitEditing={() => {
              void handleDeleteWallet();
            }}
          />

          <View className="flex-row gap-3">
            <NativeNoahSecondaryButton
              label="Cancel"
              onPress={() => {
                void handleCancelDeleteWallet();
              }}
              disabled={deleteWalletMutation.isPending}
              className="flex-1"
              fullWidth
            />
            <NativeNoahButton
              label="Delete Wallet"
              variant="destructive"
              testID="confirm-delete-wallet"
              onPress={() => {
                void handleDeleteWallet();
              }}
              disabled={
                confirmText.trim().toLowerCase() !== "delete" || deleteWalletMutation.isPending
              }
              isLoading={deleteWalletMutation.isPending}
              loadingLabel="Deleting..."
              className="flex-1"
              fullWidth
            />
          </View>
        </View>
      </AppBottomSheet>
    </NoahSafeAreaView>
  );
};

export default SettingsScreen;
