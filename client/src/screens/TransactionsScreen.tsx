import { View, Pressable, ActivityIndicator } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import Share from "react-native-share";
import { useState } from "react";
import { FlashList } from "@shopify/flash-list";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import Icon from "@react-native-vector-icons/ionicons";
import { type Transaction, type PaymentTypes } from "../types/transaction";
import { Result, ResultAsync } from "neverthrow";
import { CACHES_DIRECTORY_PATH } from "~/constants";
import RNFSTurbo from "react-native-fs-turbo";
import logger from "~/lib/log";
import { useTransactions } from "~/hooks/useTransactions";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { TransactionDetailContent } from "~/screens/TransactionDetailScreen";
import { useProfileStore } from "~/store/profileStore";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { NativeNoahIconButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSegmentedControl } from "~/components/ui/NativeNoahSegmentedControl";
import { getTransactionDisplayLabel, isInternalBoardingTransfer } from "~/lib/transactionHistory";
import { formatMovementStatusLabel } from "~/types/movement";

const log = logger("TransactionsScreen");

type TransactionFilter = PaymentTypes | "all" | "Lightning";

const TRANSACTION_FILTER_OPTIONS = [
  { label: "All", value: "all" },
  { label: "Lightning", value: "Lightning" },
  { label: "Ark", value: "Arkoor" },
  { label: "Onchain", value: "Onchain" },
] as const;

const TransactionsScreen = () => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const { data: transactions = [], isLoading, isError, isRefetching, refetch } = useTransactions();
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
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

  const exportToCSV = async () => {
    const csvHeader = `Payment ID,Date,Type,Status,Direction,Amount (₿),BTC Price (${fiatCurrency}),Transaction ID,Destination\n`;
    const csvRows = filteredTransactions
      .map((transaction) => {
        const date =
          transaction.dateLabel ?? new Date(transaction.date).toISOString().split("T")[0];
        const type = getTransactionDisplayLabel(transaction);
        const status = formatMovementStatusLabel(transaction.movementStatus) ?? "";
        const isTransfer = isInternalBoardingTransfer(transaction);
        const direction = isTransfer
          ? "Transfer"
          : transaction.direction === "outgoing"
            ? "Outgoing"
            : "Incoming";
        const amount = isTransfer
          ? transaction.amount
          : transaction.direction === "outgoing"
            ? -transaction.amount
            : transaction.amount;
        const id = transaction.id;
        const btcPrice = transaction.btcPrice;
        const txid = transaction.txid || "";
        const destination = transaction.destination;

        return `${id},${date},${type},${status},${direction},${amount},${btcPrice},${txid},${destination}`;
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
        title: "Export Transactions",
        url: `file://${filePath}`,
        type: "text/csv",
        filename: filename,
        subject: "Noah Wallet Transaction Export",
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
        <View className="p-4 flex-1">
          <View className="flex-row items-center justify-between mb-8">
            <Text className="text-2xl font-bold text-foreground">Transactions</Text>
            <View className="flex-row items-center gap-4">
              <NativeNoahIconButton
                icon="refresh"
                accessibilityLabel="Refresh transaction history"
                onPress={() => {
                  void handleRefresh();
                }}
                isLoading={isRefetching}
                testID="transactions-refresh-button"
              />
              <NativeNoahIconButton
                icon="share"
                accessibilityLabel="Export transactions"
                onPress={exportToCSV}
                testID="transactions-share-button"
              />
            </View>
          </View>
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
              <Text className="text-muted-foreground mb-4">Failed to load transactions</Text>
              <Pressable onPress={() => refetch()} className="px-4 py-2 bg-primary rounded-lg">
                <Text className="text-primary-foreground">Retry</Text>
              </Pressable>
            </View>
          ) : filteredTransactions.length === 0 ? (
            <View className="flex-1 items-center justify-center">
              <Text className="text-muted-foreground">No transactions yet</Text>
            </View>
          ) : (
            <FlashList
              data={filteredTransactions}
              renderItem={({ item }: { item: Transaction }) => {
                const isTransfer = isInternalBoardingTransfer(item);
                const movementStatus = formatMovementStatusLabel(item.movementStatus);

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
                            isTransfer ? "#f97316" : item.direction === "outgoing" ? "red" : "green"
                          }
                        />
                      </View>
                      <View
                        pointerEvents="none"
                        className="min-w-0 flex-1 flex-row justify-between gap-4"
                      >
                        <View className="min-w-0 flex-1">
                          <Text className="text-foreground text-base font-medium">
                            {getTransactionDisplayLabel(item)}
                          </Text>
                          <Text className="text-muted-foreground text-sm mt-1">
                            {item.dateLabel ?? new Date(item.date).toLocaleString()}
                          </Text>
                        </View>
                        <View className="shrink-0 items-end">
                          <Text
                            className={`text-base font-bold ${
                              isTransfer
                                ? "text-orange-500"
                                : item.direction === "outgoing"
                                  ? "text-red-500"
                                  : "text-green-500"
                            }`}
                          >
                            {`${isTransfer ? "" : item.direction === "outgoing" ? "-" : "+"}${formatBitcoinAmount(item.amount)}`}
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
        {selectedTransaction ? (
          <AppBottomSheet
            isOpen={isTransactionSheetOpen}
            onClose={() => setIsTransactionSheetOpen(false)}
            onDismiss={() => setSelectedTransaction(null)}
          >
            <TransactionDetailContent
              transaction={selectedTransaction}
              fiatCurrency={fiatCurrency}
              onClose={() => setIsTransactionSheetOpen(false)}
              closeIconName="close-outline"
            />
          </AppBottomSheet>
        ) : null}
      </NoahSafeAreaView>
    </GestureHandlerRootView>
  );
};

export default TransactionsScreen;
