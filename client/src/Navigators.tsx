import React, { useCallback, useEffect, useState, useRef } from "react";
import {
  NavigationContainer,
  DarkTheme,
  DefaultTheme,
  NavigatorScreenParams,
} from "@react-navigation/native";
import { createNativeBottomTabNavigator } from "@bottom-tabs/react-navigation";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { Platform, View, Text, AppState, ImageSourcePropType } from "react-native";
import { StatusBar } from "expo-status-bar";
import { useUniwind } from "uniwind";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";

import HomeScreen from "~/screens/HomeScreen";
import OnboardingScreen from "~/screens/OnboardingScreen";
import ReceiveScreen from "~/screens/ReceiveScreen";
import ReceiveSuccessScreen from "~/screens/ReceiveSuccessScreen";
import SendScreen from "~/screens/SendScreen";
import SettingsScreen from "~/screens/SettingsScreen";
import BoardArkScreen from "~/screens/BoardArkScreen";
import MnemonicScreen from "~/screens/MnemonicScreen";
import LogScreen from "~/screens/LogScreen";
import TransactionsScreen from "~/screens/TransactionsScreen";
import TransactionDetailScreen from "~/screens/TransactionDetailScreen";
import BoardingTransactionsScreen from "~/screens/BoardingTransactionsScreen";
import BoardingTransactionDetailScreen from "~/screens/BoardingTransactionDetailScreen";
import LightningAddressScreen from "~/screens/LightningAddressScreen";
import EmailVerificationScreen from "~/screens/EmailVerificationScreen";
import { BackupSettingsScreen } from "~/screens/BackupSettingsScreen";
import RestoreWalletScreen from "~/screens/RestoreWalletScreen";
import ArkServerAccessTokenScreen from "~/screens/ArkServerAccessTokenScreen";
import NoahStoryScreen from "~/screens/NoahStoryScreen";
import DebugScreen from "~/screens/DebugScreen";
import WalletLoader from "~/components/WalletLoader";
import { useWalletStore } from "~/store/walletStore";
import { useServerStore } from "~/store/serverStore";
import { useTransactionStore } from "~/store/transactionStore";
import { COLORS } from "~/lib/styleConstants";
import { useThemeColors } from "~/hooks/useTheme";
import { PortalHost } from "@rn-primitives/portal";
import AppServices from "~/AppServices";
import { Transaction } from "~/types/transaction";
import { OnboardingRequest, OffboardingRequest } from "~/lib/transactionsDb";
import { getMnemonic } from "~/lib/crypto";
import { walletDataExists, clearStaleKeychain } from "~/lib/walletApi";
import VTXOsScreen, { type VTXOWithStatus } from "~/screens/VTXOsScreen";
import VTXODetailScreen from "~/screens/VTXODetailScreen";
import PushNotificationsRequiredScreen from "~/screens/PushNotificationsRequiredScreen";
import UnifiedPushScreen from "~/screens/UnifiedPushScreen";
import {
  getPushPermissionStatus,
  registerForPushNotificationsAsync,
} from "~/lib/pushNotifications";
import { PermissionStatus } from "expo-notifications";
import logger from "~/lib/log";

// Param list types
type BoardingTransaction = (OnboardingRequest | OffboardingRequest) & {
  type: "onboarding" | "offboarding";
};
export type TabParamList = {
  Home: NavigatorScreenParams<HomeStackParamList> | undefined;
  Receive: undefined;
  Send: { destination?: string };
  Settings: undefined;
};

export type SettingsStackParamList = {
  SettingsList: undefined;
  Mnemonic: { fromOnboarding: boolean };
  Logs: undefined;
  LightningAddress: { fromOnboarding?: boolean };
  BackupSettings: undefined;
  VTXOs: undefined;
  VTXODetail: { vtxo: VTXOWithStatus };
  NoahStory: undefined;
  UnifiedPush: { fromOnboarding?: boolean } | undefined;
  Debug: undefined;
};

export type OnboardingStackParamList = {
  Onboarding: undefined;
  Configuration: undefined;
  ArkServerAccessToken: { mode: "create" } | { mode: "restore"; mnemonic: string };
  Mnemonic: { fromOnboarding: boolean };
  RestoreWallet: undefined;
  EmailVerification: undefined;
  LightningAddress: { fromOnboarding: boolean };
  UnifiedPush: { fromOnboarding?: boolean } | undefined;
};

export type HomeStackParamList = {
  HomeStack: undefined;
  BoardArk: undefined;
  Send: { destination: string };
  Transactions: undefined;
  TransactionDetail: { transaction: Transaction };
  BoardingTransactions: undefined;
  BoardingTransactionDetail: { transaction: BoardingTransaction };
  ReceiveSuccess: { amountSat: number };
  EmailVerification: { fromSettings?: boolean } | undefined;
};

const Tab = createNativeBottomTabNavigator<TabParamList>();
const Stack = createNativeStackNavigator<SettingsStackParamList>();
const OnboardingStack = createNativeStackNavigator<OnboardingStackParamList>();
const HomeStack = createNativeStackNavigator<HomeStackParamList>();

const SettingsStackNav = () => (
  <Stack.Navigator screenOptions={{ headerShown: false }}>
    <Stack.Screen
      name="SettingsList"
      component={SettingsScreen}
      options={{ animation: "default" }}
    />
    <Stack.Screen name="Mnemonic" component={MnemonicScreen} options={{ animation: "default" }} />
    <Stack.Screen name="Logs" component={LogScreen} options={{ animation: "default" }} />
    <Stack.Screen
      name="LightningAddress"
      component={LightningAddressScreen}
      options={{ animation: "default" }}
    />
    <Stack.Screen
      name="BackupSettings"
      component={BackupSettingsScreen}
      options={{ animation: "default" }}
    />
    <Stack.Screen name="VTXOs" component={VTXOsScreen} options={{ animation: "default" }} />
    <Stack.Screen
      name="VTXODetail"
      component={VTXODetailScreen}
      options={{ animation: "default" }}
    />
    <Stack.Screen name="NoahStory" component={NoahStoryScreen} options={{ animation: "default" }} />
    <Stack.Screen
      name="UnifiedPush"
      component={UnifiedPushScreen}
      options={{ animation: "default" }}
    />
    <Stack.Screen name="Debug" component={DebugScreen} options={{ animation: "default" }} />
  </Stack.Navigator>
);

const HomeStackScreen = () => (
  <HomeStack.Navigator>
    <HomeStack.Screen
      name="HomeStack"
      component={HomeScreen}
      options={{ headerShown: false, animation: "default" }}
    />
    <HomeStack.Screen
      name="BoardArk"
      component={BoardArkScreen}
      options={{ headerShown: false, animation: "default" }}
    />
    <HomeStack.Screen
      name="Send"
      component={SendScreen}
      options={{ headerShown: false, animation: "default" }}
    />
    <HomeStack.Screen
      name="Transactions"
      component={TransactionsScreen}
      options={{ headerShown: false, animation: "default" }}
    />
    <HomeStack.Screen
      name="TransactionDetail"
      component={TransactionDetailScreen}
      options={{ headerShown: false, animation: "default" }}
    />
    <HomeStack.Screen
      name="BoardingTransactions"
      component={BoardingTransactionsScreen}
      options={{ headerShown: false, animation: "default" }}
    />
    <HomeStack.Screen
      name="BoardingTransactionDetail"
      component={BoardingTransactionDetailScreen}
      options={{ headerShown: false, animation: "default" }}
    />
    <HomeStack.Screen
      name="ReceiveSuccess"
      component={ReceiveSuccessScreen}
      options={{ headerShown: false, animation: "default" }}
    />
    <HomeStack.Screen
      name="EmailVerification"
      component={EmailVerificationScreen}
      options={{ headerShown: false, animation: "default" }}
    />
  </HomeStack.Navigator>
);

const OnboardingStackScreen = () => (
  <OnboardingStack.Navigator screenOptions={{ headerShown: false }}>
    <OnboardingStack.Screen
      name="Onboarding"
      component={OnboardingScreen}
      options={{ animation: "default" }}
    />
    <OnboardingStack.Screen
      name="Configuration"
      component={SettingsScreen}
      options={{ animation: "default" }}
    />
    <OnboardingStack.Screen
      name="Mnemonic"
      component={MnemonicScreen}
      options={{ animation: "default" }}
    />
    <OnboardingStack.Screen
      name="RestoreWallet"
      component={RestoreWalletScreen}
      options={{ animation: "default" }}
    />
    <OnboardingStack.Screen
      name="ArkServerAccessToken"
      component={ArkServerAccessTokenScreen}
      options={{ animation: "default" }}
    />
    <OnboardingStack.Screen
      name="EmailVerification"
      component={EmailVerificationScreen}
      options={{ animation: "default" }}
    />
    <OnboardingStack.Screen
      name="LightningAddress"
      component={LightningAddressScreen}
      options={{ animation: "default" }}
    />
    <OnboardingStack.Screen
      name="UnifiedPush"
      component={UnifiedPushScreen}
      options={{ animation: "default" }}
    />
  </OnboardingStack.Navigator>
);

type PreloadedIcons = {
  home: ImageSourcePropType;
  homeOutline: ImageSourcePropType;
  arrowDown: ImageSourcePropType;
  arrowDownOutline: ImageSourcePropType;
  arrowUp: ImageSourcePropType;
  arrowUpOutline: ImageSourcePropType;
  settings: ImageSourcePropType;
  settingsOutline: ImageSourcePropType;
};

const preloadAndroidIcons = async (): Promise<PreloadedIcons> => {
  const [
    home,
    homeOutline,
    arrowDown,
    arrowDownOutline,
    arrowUp,
    arrowUpOutline,
    settings,
    settingsOutline,
  ] = await Promise.all([
    Icon.getImageSource("home", 24),
    Icon.getImageSource("home-outline", 24),
    Icon.getImageSource("arrow-down", 24),
    Icon.getImageSource("arrow-down-outline", 24),
    Icon.getImageSource("arrow-up", 24),
    Icon.getImageSource("arrow-up-outline", 24),
    Icon.getImageSource("settings", 24),
    Icon.getImageSource("settings-outline", 24),
  ]);
  return {
    home: home!,
    homeOutline: homeOutline!,
    arrowDown: arrowDown!,
    arrowDownOutline: arrowDownOutline!,
    arrowUp: arrowUp!,
    arrowUpOutline: arrowUpOutline!,
    settings: settings!,
    settingsOutline: settingsOutline!,
  };
};

const AppTabs = ({ preloadedIcons }: { preloadedIcons: PreloadedIcons }) => {
  const isIos = Platform.OS === "ios";
  const themedColors = useThemeColors();

  return (
    <Tab.Navigator
      screenOptions={{
        tabBarActiveTintColor: COLORS.BITCOIN_ORANGE,
      }}
      tabBarStyle={{
        backgroundColor: themedColors.tabBarBackground,
      }}
      tabBarInactiveTintColor={themedColors.tabBarInactive}
      hapticFeedbackEnabled
      disablePageAnimations={true}
    >
      <Tab.Screen
        name="Home"
        component={HomeStackScreen}
        options={{
          tabBarIcon: ({ focused }) => {
            if (isIos) {
              return { sfSymbol: "house.fill" };
            }
            return focused ? preloadedIcons!.home : preloadedIcons!.homeOutline;
          },
        }}
        listeners={({ navigation }) => ({
          tabPress: (e) => {
            // Only apply this fix for iOS
            if (Platform.OS === "ios") {
              const state = navigation.getState();
              const route = state.routes[state.index];

              // If we're on the Home tab and in a nested screen, prevent default and reset
              if (route.name === "Home" && route.state?.index && route.state.index > 0) {
                // Prevent default action only when we need to reset
                e.preventDefault();

                // Navigate to Home twice to reset the stack
                navigation.navigate("Home");
                // Request animation frame to ensure smooth reset
                requestAnimationFrame(() => {
                  navigation.navigate("Home");
                });
              }
            }
            // Otherwise (Android or not nested), let the default behavior handle the navigation
          },
        })}
      />
      <Tab.Screen
        name="Receive"
        component={ReceiveScreen}
        options={{
          lazy: true,
          tabBarIcon: ({ focused }) => {
            if (isIos) {
              return { sfSymbol: "arrow.down.left" };
            }
            return focused ? preloadedIcons!.arrowDown : preloadedIcons!.arrowDownOutline;
          },
        }}
      />
      <Tab.Screen
        name="Send"
        component={SendScreen}
        options={{
          lazy: true,
          tabBarIcon: ({ focused }) => {
            if (isIos) {
              return { sfSymbol: "arrow.up.right" };
            }
            return focused ? preloadedIcons!.arrowUp : preloadedIcons!.arrowUpOutline;
          },
        }}
      />
      <Tab.Screen
        name="Settings"
        component={SettingsStackNav}
        options={{
          lazy: true,
          tabBarIcon: ({ focused }) => {
            if (isIos) {
              return { sfSymbol: "gear" };
            }
            return focused ? preloadedIcons!.settings : preloadedIcons!.settingsOutline;
          },
        }}
        listeners={({ navigation }) => ({
          tabPress: (e) => {
            // Only apply this fix for iOS
            if (Platform.OS === "ios") {
              const state = navigation.getState();
              const route = state.routes[state.index];

              // If we're on the Settings tab and in a nested screen, prevent default and reset
              if (route.name === "Settings" && route.state?.index && route.state.index > 0) {
                // Prevent default action only when we need to reset
                e.preventDefault();

                // Navigate to Settings twice to reset the stack
                navigation.navigate("Settings");
                // Request animation frame to ensure smooth reset
                requestAnimationFrame(() => {
                  navigation.navigate("Settings");
                });
              }
            }
            // Otherwise (Android or not nested), let the default behavior handle the navigation
          },
        })}
      />
    </Tab.Navigator>
  );
};

const AppNavigation = () => {
  const { isInitialized } = useWalletStore();
  const [isCheckingWallet, setIsCheckingWallet] = useState(true);
  const [pushPermissionStatus, setPushPermissionStatus] = useState<PermissionStatus | "checking">(
    "checking",
  );
  const [isPhysicalDevice, setIsPhysicalDevice] = useState(true);
  const [isRequestingPermission, setIsRequestingPermission] = useState(false);
  const [preloadedIcons, setPreloadedIcons] = useState<PreloadedIcons | null>(null);
  const iconsPreloadedRef = useRef(false);
  const log = logger("AppNavigation");
  const themedColors = useThemeColors();
  const { theme } = useUniwind();
  const isDark = theme === "dark";
  const navigationTheme = isDark ? DarkTheme : DefaultTheme;
  const statusBarStyle = isDark ? "light" : "dark";

  useEffect(() => {
    if (Platform.OS !== "ios" && !iconsPreloadedRef.current) {
      iconsPreloadedRef.current = true;
      preloadAndroidIcons().then(setPreloadedIcons);
    }
  }, []);

  // Check for existing wallet on app start
  useEffect(() => {
    const checkExistingWallet = async () => {
      if (isInitialized) {
        setIsCheckingWallet(false);
        return; // Already initialized, no need to check
      }

      let shouldCheckWallet = true;
      const mnemonicResult = await getMnemonic();

      if (mnemonicResult.isOk() && mnemonicResult.value) {
        // Check if wallet data exists on disk
        // iOS Keychain persists after uninstall, but app data is deleted
        // This detects that inconsistent state and clears the stale keychain
        if (!walletDataExists()) {
          log.w("Mnemonic found in keychain but wallet data missing - clearing stale keychain");
          await clearStaleKeychain();
          // Reset all stores to ensure clean state
          useWalletStore.getState().reset();
          useServerStore.getState().resetRegistration();
          useTransactionStore.getState().reset();
          // Don't call finishOnboarding, let user go through onboarding
        } else {
          useWalletStore.getState().finishOnboarding();
          shouldCheckWallet = false;
        }
      }

      if (shouldCheckWallet) {
        setIsCheckingWallet(false);
      }
    };

    checkExistingWallet();
  }, [isInitialized]);

  const refreshPushPermissionStatus = useCallback(async () => {
    const permissionResult = await getPushPermissionStatus();
    if (permissionResult.isErr()) {
      log.w("Failed to fetch push permission status", [permissionResult.error]);
      return null;
    }

    setPushPermissionStatus(permissionResult.value.status);
    setIsPhysicalDevice(permissionResult.value.isPhysicalDevice);
    return permissionResult.value.status;
  }, [log]);

  useEffect(() => {
    if (!isInitialized) {
      return;
    }
    refreshPushPermissionStatus();
  }, [isInitialized, refreshPushPermissionStatus]);

  useEffect(() => {
    if (!isInitialized) {
      return;
    }

    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") {
        refreshPushPermissionStatus();
      }
    });

    return () => {
      subscription.remove();
    };
  }, [isInitialized, refreshPushPermissionStatus]);

  const handleRequestPermission = useCallback(async () => {
    setIsRequestingPermission(true);
    const permissionResult = await registerForPushNotificationsAsync();
    if (permissionResult.isOk()) {
      const payload = permissionResult.value;
      switch (payload.kind) {
        case "success":
          setPushPermissionStatus(PermissionStatus.GRANTED);
          setIsPhysicalDevice(true);
          break;
        case "permission_denied":
          setPushPermissionStatus(payload.permissionStatus);
          setIsPhysicalDevice(true);
          break;
        case "device_not_supported":
          setIsPhysicalDevice(false);
          setPushPermissionStatus("checking");
          break;
        default: {
          const _exhaustive: never = payload;
          log.w("Unknown permission payload", [_exhaustive]);
        }
      }
    } else {
      log.w("Failed to request push permission", [permissionResult.error]);
    }
    setIsRequestingPermission(false);
  }, [log]);

  const handleRetryPermissionStatus = useCallback(async () => {
    setIsRequestingPermission(true);
    await refreshPushPermissionStatus().finally(() => {
      setIsRequestingPermission(false);
    });
  }, [refreshPushPermissionStatus]);

  const hasResolvedPushPermission = pushPermissionStatus !== "checking";

  const shouldShowPushPermissionScreen =
    isInitialized &&
    isPhysicalDevice &&
    hasResolvedPushPermission &&
    pushPermissionStatus === PermissionStatus.DENIED;

  const isLoadingIcons = Platform.OS !== "ios" && preloadedIcons === null;

  if (isCheckingWallet || isLoadingIcons) {
    return (
      <NavigationContainer theme={navigationTheme}>
        <StatusBar style={statusBarStyle} />
        <View className="flex-1 items-center justify-center bg-background">
          <NoahActivityIndicator size="large" />
          <Text style={{ marginTop: 10, color: themedColors.foreground }}>Loading...</Text>
        </View>
        <PortalHost />
      </NavigationContainer>
    );
  }

  if (shouldShowPushPermissionScreen) {
    return (
      <NavigationContainer theme={navigationTheme}>
        <StatusBar style={statusBarStyle} />
        <PushNotificationsRequiredScreen
          status={pushPermissionStatus}
          isRequesting={isRequestingPermission}
          onRequestPermission={handleRequestPermission}
          onRetryStatus={handleRetryPermissionStatus}
        />
        <PortalHost />
      </NavigationContainer>
    );
  }

  return (
    <NavigationContainer theme={navigationTheme}>
      <StatusBar style={statusBarStyle} />
      {isInitialized ? (
        <WalletLoader>
          <AppServices />
          <AppTabs preloadedIcons={preloadedIcons!} />
        </WalletLoader>
      ) : (
        <OnboardingStackScreen />
      )}
      <PortalHost />
    </NavigationContainer>
  );
};

export default AppNavigation;
