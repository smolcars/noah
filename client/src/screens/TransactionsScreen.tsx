import { useLocale, T, useGT } from "gt-react-native";
import { View, Pressable, ActivityIndicator } from "react-native";
import { type NavigationProp, useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import Share from "react-native-share";
import { useState } from "react";
import { FlashList } from "@shopify/flash-list";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { ScreenHeader } from "~/components/ScreenHeader";
import Icon from "@react-native-vector-icons/ionicons";
import { type Transaction, type PaymentTypes } from "../types/transaction";
import { Result, ResultAsync } from "neverthrow";
import { CACHES_DIRECTORY_PATH } from "~/constants";
import RNFSTurbo from "react-native-fs-turbo";
import logger from "~/lib/log";
import { useAdaptiveLayout } from "~/hooks/useAdaptiveLayout";
import { PANE_GAP } from "~/lib/adaptiveLayout";
import { useTransactions } from "~/hooks/useTransactions";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { TransactionDetailContent } from "~/screens/TransactionDetailScreen";
import { useProfileStore } from "~/store/profileStore";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { NativeNoahIconButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSegmentedControl } from "~/components/ui/NativeNoahSegmentedControl";
import {
  getTransactionAccountingValues,
  getTransactionDisplayLabel,
  getTransactionConfirmationLabel,
  isCanceledTransaction,
  isInternalBoardingTransfer,
} from "~/lib/transactionHistory";
import { formatMovementStatusLabel } from "~/types/movement";
import { useThemeColors } from "~/hooks/useTheme";
import type { TabParamList, TransactionsStackParamList } from "~/Navigators";
import type { RepeatPaymentDetails } from "~/types/repeatPayment";

const log = logger("TransactionsScreen");

type TransactionFilter = PaymentTypes | "all" | "Lightning";

const TransactionsScreen = () => {
  const gt = useGT();
  const TRANSACTION_FILTER_OPTIONS = [
    { label: gt("All"), value: "all" },
    { label: "Lightning", value: "Lightning" },
    { label: "Ark", value: "Arkoor" },
    { label: gt("Onchain"), value: "Onchain" },
  ] as const;
  const locale = useLocale();
  const navigation = useNavigation<NativeStackNavigationProp<TransactionsStackParamList>>();
  const tabNavigation = navigation.getParent<NavigationProp<TabParamList>>();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const { mutedForeground } = useThemeColors();
  const { data: transactions = [], isLoading, isError, isRefetching, refetch } = useTransactions();
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const { isExpanded, onLayout } = useAdaptiveLayout();
  const [filter, setFilter] = useState<TransactionFilter>("all");
  const [selectedTransaction, setSelectedTransaction] = useState<Transaction | null>(null);
  const [isTransactionSheetOpen, setIsTransactionSheetOpen] = useState(false);

  const filteredTransactions =
    filter === "all"
      ? transactions
      : filter === "Lightning"
        ? transactions.filter((t) => t.type === "Bolt11" || t.type === "Lnurl")
        : transactions.filter((t) => t.type === filter);

  const handleRefresh = async () => {
    await refetch();
  };

  const openTransaction = (transaction: Transaction) => {
    setSelectedTransaction(transaction);
    setIsTransactionSheetOpen(true);
  };

  const repeatPayment = (details: RepeatPaymentDetails) => {
    setIsTransactionSheetOpen(false);
    tabNavigation?.navigate("Send", { repeatPayment: details, requestId: Date.now() });
  };

  const exportToCSV = async () => {
    const csvHeader =
      [
        gt("Payment ID"),
        gt("Date"),
        gt("Type"),
        gt("Status"),
        gt("Direction"),
        gt("Amount (₿)"),
        gt("BTC Price ({currency})", { currency: fiatCurrency }),
        gt("Transaction ID"),
        gt("Destination"),
      ].join(",") + "\n";
    const directionLabels = {
      Incoming: gt("Incoming"),
      Outgoing: gt("Outgoing"),
      Transfer: gt("Transfer"),
      None: gt("None"),
    };
    const csvRows = filteredTransactions
      .map((transaction) => {
        const date =
          getTransactionConfirmationLabel(transaction, gt) ??
          new Date(transaction.date).toISOString().split("T")[0];
        const type = getTransactionDisplayLabel(transaction, gt);
        const status = formatMovementStatusLabel(transaction.movementStatus, gt) ?? "";
        const { direction, amount } = getTransactionAccountingValues(transaction);
        const id = transaction.id;
        const btcPrice = transaction.btcPrice;
        const txid = transaction.txid || "";
        const destination = transaction.destination;

        return `${id},${date},${type},${status},${directionLabels[direction]},${amount},${btcPrice},${txid},${destination}`;
      })
      .join("\n");

    const csvContent = csvHeader + csvRows;
    const filename = `noah_transactions_${new Date().toISOString().split("T")[0]}.csv`;
    const filePath = `${CACHES_DIRECTORY_PATH}/${filename}`;

    const writeFileResult = Result.fromThrowable(
      () => {
        return RNFSTurbo.writeFile(filePath, csvContent, "utf8");
      },
      (e) => e as Error,
    )();

    if (writeFileResult.isErr()) {
      log.e("Error writing CSV file:", [writeFileResult.error]);
      return;
    }

    const shareResult = await ResultAsync.fromPromise(
      Share.open({
        title: gt("Export Transactions"),
        url: `file://${filePath}`,
        type: "text/csv",
        filename: filename,
        subject: gt("Noah Wallet Transaction Export"),
      }),
      (e) => e as Error,
    );

    if (shareResult.isErr()) {
      if (!shareResult.error.message.includes("User did not share")) {
        log.e("Error sharing CSV:", [shareResult.error]);
      }
    }

    Result.fromThrowable(
      () => {
        return RNFSTurbo.unlink(filePath);
      },
      (e) => e as Error,
    )();
  };

  const getIconForTransaction = (transaction: Transaction) => {
    if (transaction.movementKind === "onboard") {
      return "log-in-outline";
    }

    if (transaction.movementKind === "offboard") {
      return "log-out-outline";
    }

    switch (transaction.type) {
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

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <NoahSafeAreaView className="flex-1 bg-background">
        <View className="pb-4 flex-1" onLayout={onLayout}>
          <ScreenHeader
            title={gt("Transactions")}
            className="mb-8"
            actions={
              <>
                <NativeNoahIconButton
                  icon="refresh"
                  accessibilityLabel={gt("Refresh transaction history")}
                  onPress={() => {
                    void handleRefresh();
                  }}
                  isLoading={isRefetching}
                  testID="transactions-refresh-button"
                />
                <NativeNoahIconButton
                  icon="share"
                  accessibilityLabel={gt("Export transactions")}
                  onPress={exportToCSV}
                  testID="transactions-share-button"
                />
              </>
            }
          />
          <View
            className="flex-1"
            style={{ flexDirection: isExpanded ? "row" : "column", gap: PANE_GAP }}
          >
            <View className="min-w-0 flex-1">
              <View className="mb-4">
                <NativeNoahSegmentedControl
                  value={filter}
                  options={TRANSACTION_FILTER_OPTIONS}
                  onValueChange={setFilter}
                  testID="transaction-filter"
                />
              </View>
              {isLoading ? (
                <View className="flex-1 items-center justify-center">
                  <ActivityIndicator size="large" />
                </View>
              ) : isError ? (
                <View className="flex-1 items-center justify-center">
                  <T>
                    <Text className="text-muted-foreground mb-4">Failed to load transactions</Text>
                  </T>
                  <Pressable onPress={() => refetch()} className="px-4 py-2 bg-primary rounded-lg">
                    <T>
                      <Text className="text-primary-foreground">Retry</Text>
                    </T>
                  </Pressable>
                </View>
              ) : filteredTransactions.length === 0 ? (
                <View className="flex-1 items-center justify-center">
                  <T>
                    <Text className="text-muted-foreground">No transactions yet</Text>
                  </T>
                </View>
              ) : (
                <FlashList
                  data={filteredTransactions}
                  renderItem={({ item }: { item: Transaction }) => {
                    const isTransfer = isInternalBoardingTransfer(item);
                    const isCanceled = isCanceledTransaction(item);
                    const movementStatus = formatMovementStatusLabel(item.movementStatus, gt);

                    return (
                      <View style={{ marginBottom: 8 }}>
                        <Pressable
                          onPress={() => openTransaction(item)}
                          className="w-full flex-row items-center rounded-lg bg-card p-4"
                        >
                          <View
                            pointerEvents="none"
                            className="mr-4 h-8 w-8 shrink-0 items-center justify-center"
                          >
                            <Icon
                              name={getIconForTransaction(item)}
                              size={24}
                              color={
                                isCanceled
                                  ? mutedForeground
                                  : isTransfer
                                    ? "#f97316"
                                    : item.direction === "outgoing"
                                      ? "red"
                                      : "green"
                              }
                            />
                          </View>
                          <View
                            pointerEvents="none"
                            className="min-w-0 flex-1 flex-row flex-wrap justify-between gap-2"
                          >
                            <View className="min-w-0 flex-1">
                              <Text className="text-foreground text-base font-medium">
                                {getTransactionDisplayLabel(item, gt)}
                              </Text>
                              <Text className="text-muted-foreground text-sm mt-1">
                                {getTransactionConfirmationLabel(item, gt) ??
                                  new Date(item.date).toLocaleString(locale)}
                              </Text>
                            </View>
                            <View className="shrink-0 items-end">
                              <Text
                                className={`text-base font-bold ${
                                  isCanceled
                                    ? "text-muted-foreground"
                                    : isTransfer
                                      ? "text-orange-500"
                                      : item.direction === "outgoing"
                                        ? "text-red-500"
                                        : "text-green-500"
                                }`}
                              >
                                {`${isCanceled || isTransfer ? "" : item.direction === "outgoing" ? "-" : "+"}${formatBitcoinAmount(item.amount)}`}
                              </Text>
                              {movementStatus ? (
                                <Text className="mt-1 text-xs text-muted-foreground">
                                  {movementStatus}
                                </Text>
                              ) : null}
                            </View>
                          </View>
                        </Pressable>
                      </View>
                    );
                  }}
                  keyExtractor={(item: Transaction) => item.id}
                  showsVerticalScrollIndicator={false}
                  contentContainerStyle={{ paddingBottom: 50 }}
                />
              )}
            </View>
            {isExpanded ? (
              <View
                className="min-w-0 flex-1 rounded-2xl border border-border bg-card"
                testID="history-detail-pane"
              >
                {selectedTransaction && isTransactionSheetOpen ? (
                  <TransactionDetailContent
                    transaction={selectedTransaction}
                    fiatCurrency={fiatCurrency}
                    onClose={() => setIsTransactionSheetOpen(false)}
                    onRepeatPayment={repeatPayment}
                    closeIconName="close-outline"
                  />
                ) : (
                  <View className="flex-1 items-center justify-center p-6">
                    <T>
                      <Text className="text-center text-muted-foreground">
                        Select a transaction to see its details
                      </Text>
                    </T>
                  </View>
                )}
              </View>
            ) : null}
          </View>
        </View>
        {selectedTransaction ? (
          <AppBottomSheet
            isOpen={isTransactionSheetOpen && !isExpanded}
            onClose={() => {
              if (!isExpanded) setIsTransactionSheetOpen(false);
            }}
            onDismiss={() => {
              if (!isExpanded && !isTransactionSheetOpen) setSelectedTransaction(null);
            }}
          >
            <TransactionDetailContent
              transaction={selectedTransaction}
              fiatCurrency={fiatCurrency}
              onClose={() => setIsTransactionSheetOpen(false)}
              onRepeatPayment={repeatPayment}
              closeIconName="close-outline"
            />
          </AppBottomSheet>
        ) : null}
      </NoahSafeAreaView>
    </GestureHandlerRootView>
  );
};

export default TransactionsScreen;
