import { useErrorTranslation } from "~/hooks/useErrorTranslation";
import { Image, Keyboard, Linking, Pressable, ScrollView, View } from "react-native";
import Constants from "expo-constants";
import { useGT, T, Var, useLocaleSelector } from "gt-react-native";
import * as Haptics from "expo-haptics";
import { useWalletStore } from "../store/walletStore";
import { useBiometrics } from "../hooks/useBiometrics";
import { isRecurringPaymentsSupported, PLATFORM, shouldUseUnifiedPush } from "../constants";
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
import { ScreenHeader } from "~/components/ScreenHeader";
import { useIconColor, useTheme } from "~/hooks/useTheme";
import { resetAndReRegisterWithServer } from "../lib/server";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { revokeMailboxAuthorization } from "~/lib/api";
import { AUTO_BOARD_ONCHAIN_BUFFER_AMOUNT, formatAutoBoardThreshold } from "~/lib/autoBoarding";
import { useProfileStore } from "~/store/profileStore";
import { useCurrencyNames } from "~/hooks/useCurrencyNames";
import { formatBitcoinAmount, getBitcoinAmountUnitInfo } from "~/lib/bitcoinAmount";
import { NativeSwitch } from "~/components/ui/native-switch";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { AdaptiveColumns } from "~/components/AdaptiveColumns";
import {
  GitHubBrandIcon,
  GITHUB_URL,
  TelegramBrandIcon,
  TELEGRAM_SUPPORT_URL,
} from "~/components/BrandIcons";

type Setting = {
  id:
    | "profile"
    | "language"
    | "currency"
    | "bitcoinUnit"
    | "showMnemonic"
    | "showLogs"
    | "resetRegistration"
    | "backup"
    | "boardArk"
    | "recurringPayments"
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
  testID?: string;
};

const SettingsScreen = () => {
  const translateError = useErrorTranslation();
  const gt = useGT();
  const { locale, getLocaleProperties } = useLocaleSelector();
  const languageName = getLocaleProperties(locale).nativeName;
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
  const currencyNames = useCurrencyNames();
  const bitcoinAmountUnit = useProfileStore((state) => state.bitcoinAmountUnit);
  const bitcoinAmountUnitInfo = getBitcoinAmountUnitInfo(bitcoinAmountUnit);
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
    ? gt("Auto-board threshold unavailable")
    : isAutoBoardThresholdLoading || autoBoardThreshold === undefined
      ? gt("Loading auto-board threshold...")
      : gt(
          "Ask to board to Ark when onchain balance can cover {threshold}, estimated fees, and a {reserve} reserve.",
          {
            threshold: formatAutoBoardThreshold(autoBoardThreshold, locale),
            reserve: formatAutoBoardThreshold(AUTO_BOARD_ONCHAIN_BUFFER_AMOUNT, locale),
            $context: "Boarding transfers Bitcoin funds from the Bitcoin blockchain to Ark.",
          },
        );

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
      ? gt("Authenticate to enable biometrics")
      : gt("Authenticate to disable biometrics");
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
        setMailboxError(result.error.message || gt("Failed to revoke mailbox authorization"));
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
    } else if (item.id === "language") {
      navigation.navigate("Language");
    } else if (item.id === "currency") {
      navigation.navigate("Currency");
    } else if (item.id === "bitcoinUnit") {
      navigation.navigate("BitcoinUnit");
    } else if (item.id === "showMnemonic") {
      navigation.navigate("Mnemonic", { fromOnboarding: false });
    } else if (item.id === "showLogs") {
      navigation.navigate("Logs");
    } else if (item.id === "resetRegistration") {
      // This is handled by the AlertDialog now
    } else if (item.id === "backup") {
      navigation.navigate("BackupSettings");
    } else if (item.id === "boardArk") {
      navigation.navigate("BoardArk");
    } else if (item.id === "recurringPayments") {
      navigation.navigate("RecurringPayments");
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
      title: gt("Profile"),
      description: gt("Manage your name, Lightning address, emergency email, and public key."),
      isPressable: true,
    });
    profileData.push({
      id: "language",
      title: gt("Language"),
      value: languageName.charAt(0).toLocaleUpperCase(locale) + languageName.slice(1),
      description: gt("Choose the language used in Noah."),
      isPressable: true,
    });
    profileData.push({
      id: "currency",
      title: gt("Currency"),
      value: `${preferredCurrency} · ${currencyNames[preferredCurrency]}`,
      description: gt("Choose the fiat currency used for balances and payment amounts."),
      isPressable: true,
    });
    profileData.push({
      id: "bitcoinUnit",
      title: gt("Bitcoin Unit"),
      value: `${bitcoinAmountUnitInfo.title} · ${formatBitcoinAmount(1234, bitcoinAmountUnit, locale)}`,
      description: gt("Choose how bitcoin amounts are displayed."),
      isPressable: true,
    });

    infoData.push({
      id: "arkInfo",
      title: gt("Ark Info"),
      description: gt("View Ark server, wallet, and explorer configuration."),
      isPressable: true,
    });
  }

  if (isInitialized) {
    walletData.push({
      id: "showMnemonic",
      title: gt("Show Seed Phrase"),
      description: gt(
        "Never share your seed phrase with anyone. It is important to keep it safe and secure.",
      ),
      isPressable: true,
    });
    walletData.push({
      id: "vtxos",
      title: gt("Show VTXOs"),
      description: gt("VTXOs are to Ark like UTXOs are to Bitcoin"),
      isPressable: true,
    });
    walletData.push({
      id: "emergencyExit",
      title: gt("Emergency Exit"),
      description: gt("Recover funds if the Ark server is unavailable."),
      isPressable: true,
    });
    // Hidden for UnifiedPush users: their push handler can't wake Noah for
    // recurring payments yet.
    if (isRecurringPaymentsSupported()) {
      walletData.push({
        id: "recurringPayments",
        title: gt("Recurring Payments"),
        description: gt("Schedule automatic weekly or monthly payments, and pause or cancel them."),
        isPressable: true,
        testID: "settings-recurring-payments",
      });
    }
    walletData.push({
      id: "backup",
      title: gt("Backup & Restore"),
      description: gt("Automatically or manually backup your wallet after encrypting it."),
      isPressable: true,
    });

    if (shouldUseUnifiedPush()) {
      walletData.push({
        id: "unifiedPush",
        title: gt("UnifiedPush Setup"),
        description: gt("Configure push notifications using UnifiedPush"),
        isPressable: true,
      });
    }

    walletData.push({
      id: "boardArk",
      title: gt("Board to Ark"),
      description: gt("Manually move onchain bitcoin into Ark."),
      isPressable: true,
      testID: "settings-board-ark",
    });

    debugData.push({
      id: "showLogs",
      title: gt("Show Logs"),
      description: gt("View application logs for debugging purposes"),
      isPressable: true,
    });
    debugData.push({
      id: "resetRegistration",
      title: gt("Reset Server Registration"),
      description: gt("Clear your registration with the server. You will need to register again."),
      isPressable: true,
    });
    debugData.push({
      id: "feedback",
      title: gt("Send Feedback"),
      description: gt("Report bugs or share feedback with the Noah team"),
      isPressable: true,
    });
    if (isDebugModeEnabled) {
      debugData.push({
        id: "debug",
        title: gt("Debug Screen"),
        description: gt("Advanced debug actions for developers"),
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
              description={gt(
                "Attempt to reset the connection with our server if you're experiencing issues.",
              )}
              isPressable={item.isPressable}
              onPress={() => {}}
            />
          }
          title={gt("Reset Server Registration")}
          description={gt(
            "Are you sure you want to reset your server registration? This will not delete your wallet, but you will need to register with the server again.",
          )}
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
              setResetError(result.error.message || gt("Failed to reset registration"));
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
        testID={item.testID ?? `settings-${item.id}`}
        className="flex-row justify-between items-center p-4 border-b border-border bg-card rounded-lg mb-2"
      >
        <View className="flex-1">
          <Text className="text-foreground text-lg font-medium">{item.title}</Text>
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
      <View className="px-4">
        <ScreenHeader
          title={gt("Settings")}
          onBack={() => navigation.goBack()}
          backButtonTestID="settings-back-button"
          className="mb-4"
        />

        {showResetSuccess && (
          <Alert icon={CheckCircle} className="mb-4">
            <T>
              <AlertTitle>Success!</AlertTitle>
            </T>
            <T>
              <AlertDescription>Server registration has been reset.</AlertDescription>
            </T>
          </Alert>
        )}
        {resetError && (
          <Alert icon={AlertTriangle} variant="destructive" className="mb-4">
            <T>
              <AlertTitle>Reset Failed!</AlertTitle>
            </T>
            <AlertDescription>{translateError(resetError ?? "")}</AlertDescription>
          </Alert>
        )}
        {showMailboxSuccess && (
          <Alert icon={CheckCircle} className="mb-4">
            <T>
              <AlertTitle>Mailbox Access Updated!</AlertTitle>
            </T>
            <AlertDescription>
              {isMailboxAuthorizationEnabled
                ? gt("Mailbox authorization will be granted again shortly.")
                : gt("Mailbox authorization has been revoked.")}
            </AlertDescription>
          </Alert>
        )}
        {mailboxError && (
          <Alert icon={AlertTriangle} variant="destructive" className="mb-4">
            <T>
              <AlertTitle>Mailbox Update Failed!</AlertTitle>
            </T>
            <AlertDescription>{translateError(mailboxError ?? "")}</AlertDescription>
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
        <AdaptiveColumns>
          <View>
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
                <T>
                  <Text
                    className="text-lg font-bold text-foreground mb-2"
                    style={{ color: COLORS.BITCOIN_ORANGE }}
                  >
                    Account
                  </Text>
                </T>
                {profileData.map(renderSettingItem)}
              </View>
            )}

            {infoData.length > 0 && (
              <View className="mb-6">
                <T>
                  <Text
                    className="text-lg font-bold text-foreground mb-2"
                    style={{ color: COLORS.BITCOIN_ORANGE }}
                  >
                    Info
                  </Text>
                </T>
                {infoData.map(renderSettingItem)}
              </View>
            )}
          </View>
          <View>
            {walletData.length > 0 && (
              <View className="mb-6">
                <T>
                  <Text
                    className="text-lg font-bold text-foreground mb-2"
                    style={{ color: COLORS.BITCOIN_ORANGE }}
                  >
                    Wallet
                  </Text>
                </T>
                {walletData.map(renderSettingItem)}
                <View className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center">
                  <View className="flex-1">
                    <T>
                      <Label className="text-foreground text-lg">Auto-Board to Ark</Label>
                    </T>
                    <Text className="text-base mt-1 text-muted-foreground">
                      {autoBoardDescription}
                    </Text>
                  </View>
                  <NativeSwitch
                    value={isAutoBoardingEnabled}
                    onValueChange={setAutoBoardingEnabled}
                  />
                </View>
                {isBiometricsAvailable && (
                  <View className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center">
                    <View className="flex-1">
                      <T>
                        <Label className="text-foreground text-lg">Biometric Authentication</Label>
                      </T>
                      <T>
                        <Text className="text-base mt-1 text-muted-foreground">
                          Require biometric authentication to unlock your wallet
                        </Text>
                      </T>
                    </View>
                    <NativeSwitch
                      value={isBiometricsEnabled}
                      onValueChange={handleBiometricsToggle}
                    />
                  </View>
                )}
                <View className="p-4 border-b border-border bg-card rounded-lg mb-2 flex-row justify-between items-center">
                  <View className="flex-1">
                    <T>
                      <Label className="text-foreground text-lg">Mailbox Notifications</Label>
                    </T>
                    <T>
                      <Text className="text-base mt-1 text-muted-foreground">
                        Allow Noah to monitor your Ark mailbox so it can wake this app to claim
                        Lightning payments in the background.
                      </Text>
                    </T>
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
                <T>
                  <Text
                    className="text-lg font-bold text-foreground mb-2"
                    style={{ color: COLORS.BITCOIN_ORANGE }}
                  >
                    Debug
                  </Text>
                </T>
                {debugData.map(renderSettingItem)}
              </View>
            )}

            {isInitialized && (
              <View className="mb-6">
                <T>
                  <Text className="text-lg font-bold text-destructive mb-2">Danger Zone</Text>
                </T>

                <View className="p-4 border-b border-border bg-card rounded-lg mb-4 flex-row justify-between items-center">
                  <View className="flex-1">
                    <T>
                      <Label className="text-foreground text-lg">Suspend Wallet</Label>
                    </T>
                    <T>
                      <Text className="text-base mt-1 text-muted-foreground">
                        Disable all wallet operations. The wallet will be closed and won't load
                        until re-enabled.
                      </Text>
                    </T>
                  </View>
                  <NativeSwitch
                    value={isWalletSuspended}
                    onValueChange={(value) => suspendWalletMutation.mutate(value)}
                    disabled={suspendWalletMutation.isPending}
                    tone="destructive"
                  />
                </View>

                <DangerZoneRow
                  title={gt("Export Database")}
                  description={gt(
                    "Create an encrypted backup file containing your wallet database.",
                  )}
                  isPressable
                  onPress={() => navigation.navigate("ExportDatabase")}
                />

                <NativeNoahButton
                  label={gt("Delete Wallet")}
                  variant="destructive"
                  onPress={() => setIsDeleteWalletDialogOpen(true)}
                  fullWidth
                />
              </View>
            )}
          </View>
        </AdaptiveColumns>
        <View className="items-center py-8 px-4">
          <Pressable onPress={handleVersionTap}>
            <T>
              <Text className="text-muted-foreground text-sm mb-1">
                v<Var>{Constants.expoConfig?.version || "0.0.1"}</Var>
                <Var>
                  {versionTapCount > 0 &&
                    versionTapCount < 5 &&
                    gt(" ({count} taps to unlock debug)", { count: 5 - versionTapCount })}
                </Var>
                <Var>{isDebugModeEnabled && " 🔧"}</Var>
              </Text>
            </T>
          </Pressable>
          <T>
            <Text className="text-muted-foreground text-sm">Made with ❤️ from Noah team</Text>
          </T>
          <View className="mt-3 flex-row items-center justify-center gap-4">
            <Pressable
              onPress={handleTelegramPress}
              className="h-10 w-10 items-center justify-center rounded-full bg-card"
              accessibilityRole="link"
              accessibilityLabel={gt("Open Telegram support chat")}
            >
              <TelegramBrandIcon size={28} />
            </Pressable>
            <Pressable
              onPress={handleGithubPress}
              className="h-10 w-10 items-center justify-center rounded-full bg-card"
              accessibilityRole="link"
              accessibilityLabel={gt("Open Noah GitHub repository")}
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
        <View className="gap-4 pt-2" style={{ paddingBottom: Math.max(safeBottomInset, 16) + 12 }}>
          <View className="gap-2">
            <T>
              <Text className="text-xl font-bold text-foreground">Delete Wallet</Text>
            </T>
            <T>
              <Text className="text-base text-muted-foreground">
                This action is irreversible. To confirm, please type "<Var>delete</Var>" in the box
                below.
              </Text>
            </T>
          </View>

          <Input
            value={confirmText}
            onChangeText={setConfirmText}
            placeholder={gt('Type "{word}" to confirm', { word: "delete" })}
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
              label={gt("Cancel")}
              onPress={() => {
                void handleCancelDeleteWallet();
              }}
              disabled={deleteWalletMutation.isPending}
              className="flex-1"
              fullWidth
            />
            <NativeNoahButton
              label={gt("Delete Wallet")}
              variant="destructive"
              testID="confirm-delete-wallet"
              onPress={() => {
                void handleDeleteWallet();
              }}
              disabled={
                confirmText.trim().toLowerCase() !== "delete" || deleteWalletMutation.isPending
              }
              isLoading={deleteWalletMutation.isPending}
              loadingLabel={gt("Deleting...")}
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
