import { View, ScrollView, RefreshControl, Pressable } from "react-native";
import { NavigationProp, useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { HomeStackParamList, TabParamList } from "../Navigators";
import { Text } from "../components/ui/text";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "../components/ui/collapsible";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { AlertCircle, ChevronDown } from "lucide-react-native";
import { useCallback, useEffect, useState } from "react";
import { COLORS } from "../lib/styleConstants";
import { useIconColor } from "../hooks/useTheme";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import { useBalance, useLoadWallet, useWalletSync } from "../hooks/useWallet";
import Icon from "@react-native-vector-icons/ionicons";
import { APP_VARIANT } from "~/config";
import { BITCOIN_FACTS, PLATFORM } from "~/constants";
import { useAppVersionCheck } from "~/hooks/useAppVersionCheck";
import { UpdateWarningBanner } from "~/components/UpdateWarningBanner";
import { EmailVerificationBanner } from "~/components/EmailVerificationBanner";
import { BackupStatusBanner } from "~/components/BackupStatusBanner";
import { AutoBoardingStatusBanner } from "~/components/AutoBoardingStatusBanner";
import { useBackgroundJobCoordination } from "~/hooks/useBackgroundJobCoordination";
import { useServerStore } from "~/store/serverStore";

import Animated, {
  FadeInDown,
  FadeOutDown,
  useAnimatedStyle,
  withTiming,
} from "react-native-reanimated";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { useBtcToUsdRate } from "~/hooks/useMarketData";
import { useWalletStore } from "~/store/walletStore";
import { PauseCircle } from "lucide-react-native";
import { updateWidget, useWidget } from "~/hooks/useWidget";
import { formatBip177 } from "~/lib/utils";
import { calculateBalances } from "~/lib/balanceUtils";
import { onchainSync, sync } from "~/lib/walletApi";
import { useTransactions } from "~/hooks/useTransactions";
import { Transaction } from "~/types/transaction";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { TransactionDetailContent } from "~/screens/TransactionDetailScreen";

const getTransactionIcon = (type: Transaction["type"]) => {
  switch (type) {
    case "Bolt11":
    case "Lnurl":
      return "flash-outline";
    case "Arkoor":
      return "boat-outline";
    case "Onchain":
      return "cube-outline";
    default:
      return "cash-outline";
  }
};

const getTransactionLabel = (transaction: Transaction) => {
  if (transaction.type === "Bolt11" || transaction.type === "Lnurl") {
    return "Lightning";
  }

  if (transaction.type === "Arkoor") {
    return "Ark";
  }

  return transaction.type;
};

const HomeScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<HomeStackParamList>>();
  const iconColor = useIconColor();
  const parentNavigation = navigation.getParent<NavigationProp<TabParamList>>();
  const { walletError, isWalletSuspended } = useWalletStore();
  const { safelyExecuteWhenReady, isBackgroundJobRunning } = useBackgroundJobCoordination();
  const { data: balance, refetch, error, isLoading: isBalanceLoading } = useBalance();
  const { isPending: isSyncPending } = useWalletSync();
  const { mutateAsync: loadWallet } = useLoadWallet();
  const { data: btcToUsdRate, isLoading: isRateLoading } = useBtcToUsdRate();
  const [isOpen, setIsOpen] = useState(false);
  const [fact, setFact] = useState("");
  const [selectedTransaction, setSelectedTransaction] = useState<Transaction | null>(null);
  const [isTransactionSheetOpen, setIsTransactionSheetOpen] = useState(false);
  const bottomTabBarHeight = useBottomTabBarHeight();
  const { isUpdateRequired, minimumVersion, currentVersion } = useAppVersionCheck();
  const { isEmailVerified, isEmailPromptDismissed, setEmailPromptDismissed } = useServerStore();
  const { data: transactions = [], isLoading: isTransactionsLoading } = useTransactions();
  const recentTransactions = transactions.slice(0, 3);

  const handleEmailVerificationPress = useCallback(() => {
    navigation.navigate("EmailVerification", { fromSettings: true });
  }, [navigation]);

  const getRandomFact = useCallback(() => {
    const randomIndex = Math.floor(Math.random() * BITCOIN_FACTS.length);
    setFact(BITCOIN_FACTS[randomIndex]);
  }, []);

  useEffect(() => {
    safelyExecuteWhenReady(() => loadWallet());
    getRandomFact();
  }, [getRandomFact, safelyExecuteWhenReady, loadWallet]);

  const onRefresh = useCallback(async () => {
    await safelyExecuteWhenReady(() => loadWallet());

    await sync();
    await onchainSync();
    await refetch();
    await updateWidget();
    getRandomFact();
  }, [refetch, getRandomFact, safelyExecuteWhenReady, loadWallet]);

  const openHistory = () => {
    parentNavigation?.navigate("History", { screen: "TransactionsList" });
  };

  const openTransaction = (transaction: Transaction) => {
    setSelectedTransaction(transaction);
    setIsTransactionSheetOpen(true);
  };

  const isLoading = isBalanceLoading || isSyncPending || isRateLoading;
  const balances = balance ? calculateBalances(balance) : null;
  const totalBalance = balances?.totalBalance ?? 0;
  const onchainBalance = balances?.onchainBalance ?? 0;
  const offchainBalance = balances?.offchainBalance ?? 0;
  const totalPendingBalance = balances?.pendingBalance ?? 0;
  const totalBalanceInUsd = btcToUsdRate ? (totalBalance / 100_000_000) * btcToUsdRate : 0;
  const errorMessage = error instanceof Error ? error.message : String(error);

  useWidget(balances);

  const animatedRotation = useAnimatedStyle(() => {
    return {
      transform: [{ rotate: withTiming(isOpen ? "180deg" : "0deg") }],
    };
  }, [isOpen]);

  if (isWalletSuspended) {
    return (
      <NoahSafeAreaView
        className="flex-1 bg-background"
        style={{
          paddingBottom: PLATFORM === "ios" ? bottomTabBarHeight : 0,
        }}
      >
        <View className="flex-1 items-center justify-center p-8">
          <View className="bg-card rounded-2xl p-8 items-center max-w-[320px] border border-border">
            <View className="w-20 h-20 rounded-full bg-destructive/10 items-center justify-center mb-6">
              <PauseCircle size={48} color="#dc2626" />
            </View>
            <Text className="text-2xl font-bold text-foreground mb-3 text-center">
              Wallet Suspended
            </Text>
            <Text className="text-base text-muted-foreground text-center leading-6">
              Your wallet is currently suspended. All wallet operations are disabled.
            </Text>
            <Text className="text-sm text-muted-foreground text-center mt-4">
              Go to Settings → Danger Zone to resume your wallet.
            </Text>
            <Pressable
              onPress={() => navigation.navigate("Settings")}
              accessibilityRole="button"
              accessibilityLabel="Open settings"
              className="mt-6 rounded-2xl bg-primary px-5 py-3"
            >
              <Text className="font-semibold text-primary-foreground">Open Settings</Text>
            </Pressable>
          </View>
        </View>
      </NoahSafeAreaView>
    );
  }

  return (
    <NoahSafeAreaView
      className="flex-1 bg-background"
      style={{
        paddingBottom: PLATFORM === "ios" ? bottomTabBarHeight : 0,
      }}
    >
      <View className="flex-row items-center justify-between p-4">
        <Pressable onPress={() => navigation.navigate("BoardArk")}>
          <Icon name="boat" size={28} color={iconColor} />
        </Pressable>
        <View className="flex-1 items-center">
          {APP_VARIANT !== "mainnet" && (
            <View className="rounded-md bg-yellow-400 px-2 py-1">
              <Text className="text-xs font-bold uppercase text-black">{APP_VARIANT}</Text>
            </View>
          )}
        </View>
        <View className="flex-row items-center gap-4">
          <Pressable
            onPress={() => navigation.navigate("QRHub")}
            accessibilityRole="button"
            accessibilityLabel="Open QR code"
          >
            <Icon name="qr-code-outline" size={28} color={iconColor} />
          </Pressable>
          <Pressable
            onPress={() => navigation.navigate("Settings")}
            accessibilityRole="button"
            accessibilityLabel="Open settings"
          >
            <Icon name="settings-outline" size={28} color={iconColor} />
          </Pressable>
        </View>
      </View>
      <ScrollView
        contentContainerStyle={{
          flexGrow: 1,
        }}
        refreshControl={
          <RefreshControl
            refreshing={isSyncPending}
            onRefresh={onRefresh}
            tintColor={COLORS.BITCOIN_ORANGE}
            colors={[COLORS.BITCOIN_ORANGE]}
            title="Refreshing..."
            titleColor={COLORS.BITCOIN_ORANGE}
            progressViewOffset={-10}
          />
        }
      >
        {isUpdateRequired && (
          <UpdateWarningBanner
            currentVersion={currentVersion}
            minimumVersion={minimumVersion || "0.0.1"}
          />
        )}
        {!isEmailVerified && !isEmailPromptDismissed && (
          <EmailVerificationBanner
            onPress={handleEmailVerificationPress}
            onDismiss={() => setEmailPromptDismissed(true)}
          />
        )}
        <BackupStatusBanner />
        <AutoBoardingStatusBanner />
        {isBackgroundJobRunning && (
          <View className="px-4 py-2 bg-blue-500/20 border-b border-blue-500/40">
            <View className="flex-row items-center justify-center space-x-2">
              <NoahActivityIndicator size="small" />
              <Text className="text-blue-400 text-sm">Background task in progress...</Text>
            </View>
          </View>
        )}
        <View className="px-5 pt-16 pb-10">
          {isLoading && !balance ? (
            <View className="items-center justify-center py-28">
              <NoahActivityIndicator size="large" />
            </View>
          ) : (error || walletError) && !balance ? (
            <Alert variant="destructive" icon={AlertCircle}>
              <AlertTitle>Error</AlertTitle>
              <AlertDescription>
                {walletError
                  ? "Failed to connect to wallet. Pull down to try again."
                  : errorMessage}
              </AlertDescription>
            </Alert>
          ) : (
            <>
              <Collapsible open={isOpen} onOpenChange={setIsOpen} className="items-center pb-10">
                <CollapsibleTrigger asChild>
                  <Pressable>
                    <View className="items-center">
                      {btcToUsdRate ? (
                        <Text className="text-2xl text-muted-foreground mb-2">
                          $
                          {totalBalanceInUsd.toLocaleString(undefined, {
                            minimumFractionDigits: 2,
                            maximumFractionDigits: 2,
                          })}
                        </Text>
                      ) : (
                        <View className="h-[32px] mb-2 justify-center">
                          <NoahActivityIndicator />
                        </View>
                      )}
                      <View className="flex-row items-center space-x-2">
                        <Text className="text-4xl font-bold">{formatBip177(totalBalance)}</Text>
                        <Animated.View style={animatedRotation}>
                          <ChevronDown color={iconColor} size={28} />
                        </Animated.View>
                      </View>
                      {totalPendingBalance > 0 && (
                        <View className="mt-2 px-3 py-1 rounded-full bg-yellow-500/20 border border-yellow-500/40">
                          <Text className="text-yellow-500 text-sm">
                            Pending balance: {formatBip177(totalPendingBalance)}
                          </Text>
                        </View>
                      )}
                    </View>
                  </Pressable>
                </CollapsibleTrigger>
                <CollapsibleContent>
                  <Animated.View entering={FadeInDown} exiting={FadeOutDown}>
                    <View className="p-4 rounded-lg bg-card mt-4 min-w-[300px]">
                      <Text className="text-lg font-bold mb-4 text-center">Balance Details</Text>

                      <View className="mb-4">
                        <View className="flex-row justify-between items-center mb-2">
                          <Text className="text-md font-bold">Onchain</Text>
                          <Text className="text-md font-bold">{formatBip177(onchainBalance)}</Text>
                        </View>
                        <View className="pl-4 space-y-1">
                          <View className="flex-row justify-between">
                            <Text className="text-muted-foreground">Confirmed</Text>
                            <Text>{formatBip177(balance?.onchain.confirmed ?? 0)}</Text>
                          </View>
                          <View className="flex-row justify-between">
                            <Text className="text-muted-foreground">Trusted Pending</Text>
                            <Text>{formatBip177(balance?.onchain.trusted_pending ?? 0)}</Text>
                          </View>
                          <View className="flex-row justify-between">
                            <Text className="text-muted-foreground">Untrusted Pending</Text>
                            <Text>{formatBip177(balance?.onchain.untrusted_pending ?? 0)}</Text>
                          </View>
                          <View className="flex-row justify-between">
                            <Text className="text-muted-foreground">Immature</Text>
                            <Text>{formatBip177(balance?.onchain.immature ?? 0)}</Text>
                          </View>
                        </View>
                      </View>

                      <View>
                        <View className="flex-row justify-between items-center mb-2">
                          <Text className="text-md font-bold">Offchain</Text>
                          <Text className="text-md font-bold">{formatBip177(offchainBalance)}</Text>
                        </View>
                        <View className="pl-4 space-y-1">
                          <View className="flex-row justify-between">
                            <Text className="text-muted-foreground">Spendable</Text>
                            <Text>{formatBip177(balance?.offchain.spendable ?? 0)}</Text>
                          </View>
                          <View className="flex-row justify-between">
                            <Text className="text-muted-foreground">Pending Send</Text>
                            <Text>
                              {formatBip177(balance?.offchain.pending_lightning_send ?? 0)}
                            </Text>
                          </View>
                          <View className="flex-row justify-between mb-2">
                            <Text className="text-muted-foreground">Pending In Round</Text>
                            <Text>{formatBip177(balance?.offchain.pending_in_round ?? 0)}</Text>
                          </View>
                          <View className="flex-row justify-between">
                            <Text className="text-muted-foreground">Pending Exit</Text>
                            <Text>{formatBip177(balance?.offchain.pending_exit ?? 0)}</Text>
                          </View>
                          <View className="flex-row justify-between">
                            <Text className="text-muted-foreground">Pending Board</Text>
                            <Text>{formatBip177(balance?.offchain.pending_board ?? 0)}</Text>
                          </View>
                        </View>
                      </View>
                    </View>
                  </Animated.View>
                </CollapsibleContent>
              </Collapsible>

              <View className="gap-4">
                <View className="rounded-[18px] border border-border/60 bg-card/70 px-4 py-4">
                  <View className="mb-3 flex-row items-center justify-between">
                    <Text className="text-base font-bold text-foreground">Recent Activity</Text>
                    <Pressable onPress={openHistory}>
                      <Text className="text-sm font-semibold text-primary">View all</Text>
                    </Pressable>
                  </View>

                  {isTransactionsLoading ? (
                    <View className="items-center py-6">
                      <NoahActivityIndicator size="small" />
                    </View>
                  ) : recentTransactions.length === 0 ? (
                    <View className="py-5">
                      <Text className="text-sm font-semibold text-foreground">No activity yet</Text>
                      <Text className="mt-1 text-sm text-muted-foreground">
                        Receive or send bitcoin to start your history.
                      </Text>
                    </View>
                  ) : (
                    recentTransactions.map((transaction, index) => (
                      <Pressable
                        key={transaction.id}
                        onPress={() => openTransaction(transaction)}
                        className={`flex-row items-center py-3 ${
                          index < recentTransactions.length - 1 ? "border-b border-border/60" : ""
                        }`}
                      >
                        <View className="mr-3 h-10 w-10 items-center justify-center rounded-full bg-background">
                          <Icon
                            name={getTransactionIcon(transaction.type)}
                            size={20}
                            color={transaction.direction === "outgoing" ? "#ef4444" : "#22c55e"}
                          />
                        </View>
                        <View className="min-w-0 flex-1">
                          <Text className="text-sm font-semibold text-foreground">
                            {getTransactionLabel(transaction)}
                          </Text>
                          <Text className="mt-1 text-xs text-muted-foreground">
                            {transaction.dateLabel ??
                              new Date(transaction.date).toLocaleDateString(undefined, {
                                month: "short",
                                day: "numeric",
                              })}
                          </Text>
                        </View>
                        <Text
                          className={`text-sm font-bold ${
                            transaction.direction === "outgoing" ? "text-red-500" : "text-green-500"
                          }`}
                        >
                          {`${transaction.direction === "outgoing" ? "-" : "+"}${formatBip177(transaction.amount)}`}
                        </Text>
                      </Pressable>
                    ))
                  )}
                </View>
              </View>
            </>
          )}
        </View>
        <View className="p-4 items-center justify-center mb-16" style={{ marginTop: "auto" }}>
          <Text className="text-center text-xs text-muted-foreground">{fact}</Text>
        </View>
      </ScrollView>
      {selectedTransaction ? (
        <AppBottomSheet
          isOpen={isTransactionSheetOpen}
          onClose={() => setIsTransactionSheetOpen(false)}
          onDismiss={() => setSelectedTransaction(null)}
        >
          <TransactionDetailContent
            transaction={selectedTransaction}
            onClose={() => setIsTransactionSheetOpen(false)}
            closeIconName="close-outline"
          />
        </AppBottomSheet>
      ) : null}
    </NoahSafeAreaView>
  );
};

export default HomeScreen;
