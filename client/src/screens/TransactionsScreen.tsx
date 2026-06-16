import { View, Pressable, ActivityIndicator } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import Share from "react-native-share";
import { useState } from "react";
import { FlashList } from "@shopify/flash-list";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { type Transaction, type PaymentTypes } from "../types/transaction";
import { NavigationProp, useNavigation } from "@react-navigation/native";
import { TabParamList, TransactionsStackParamList } from "~/Navigators";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { Result, ResultAsync } from "neverthrow";
import { CACHES_DIRECTORY_PATH } from "~/constants";
import RNFSTurbo from "react-native-fs-turbo";
import logger from "~/lib/log";
import { useTransactions } from "~/hooks/useTransactions";
import { HistoryRefreshButton } from "~/components/HistoryRefreshButton";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { TransactionDetailContent } from "~/screens/TransactionDetailScreen";
import { useProfileStore } from "~/store/profileStore";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

const log = logger("TransactionsScreen");

const TransactionsScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<TransactionsStackParamList>>();
  const parentNavigation = navigation.getParent<NavigationProp<TabParamList>>();
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const { data: transactions = [], isLoading, isError, isRefetching, refetch } = useTransactions();
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const [filter, setFilter] = useState<PaymentTypes | "all" | "Lightning">("all");
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

  const handleBack = () => {
    if (navigation.canGoBack()) {
      navigation.goBack();
      return;
    }

    if (parentNavigation?.canGoBack()) {
      parentNavigation.goBack();
      return;
    }

    parentNavigation?.navigate("Home");
  };

  const openTransaction = (transaction: Transaction) => {
    setSelectedTransaction(transaction);
    setIsTransactionSheetOpen(true);
  };

  const exportToCSV = async () => {
    const csvHeader = `Payment ID,Date,Type,Direction,Amount (₿),BTC Price (${fiatCurrency}),Transaction ID,Destination\n`;
    const csvRows = filteredTransactions
      .map((transaction) => {
        const date =
          transaction.dateLabel ?? new Date(transaction.date).toISOString().split("T")[0];
        const type =
          transaction.type === "Bolt11" || transaction.type === "Lnurl"
            ? "Lightning"
            : transaction.type;
        const direction = transaction.direction === "outgoing" ? "Outgoing" : "Incoming";
        const amount =
          transaction.direction === "outgoing" ? -transaction.amount : transaction.amount;
        const id = transaction.id;
        const btcPrice = transaction.btcPrice;
        const txid = transaction.txid || "";
        const destination = transaction.destination;

        return `${id},${date},${type},${direction},${amount},${btcPrice},${txid},${destination}`;
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

  const getIconForType = (type: Transaction["type"]) => {
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

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <NoahSafeAreaView className="flex-1 bg-background">
        <View className="p-4 flex-1">
          <View className="flex-row items-center justify-between mb-8">
            <View className="flex-row items-center">
              <Pressable onPress={handleBack} className="mr-4">
                <Icon name="arrow-back-outline" size={24} color={iconColor} />
              </Pressable>
              <Text className="text-2xl font-bold text-foreground">Transactions</Text>
            </View>
            <View className="flex-row items-center gap-2">
              <HistoryRefreshButton isRefreshing={isRefetching} onRefresh={handleRefresh} />
              <Pressable
                onPress={exportToCSV}
                accessibilityRole="button"
                accessibilityLabel="Export transactions"
                className="h-10 w-10 items-center justify-center rounded-full"
              >
                <Icon name="download-outline" size={24} color={iconColor} />
              </Pressable>
            </View>
          </View>
          <View className="flex-row justify-around mb-4">
            {(["all", "Lightning", "Arkoor", "Onchain"] as const).map((f) => (
              <Pressable
                key={f}
                onPress={() => setFilter(f)}
                className={`px-3 py-1 rounded-full ${filter === f ? "bg-primary" : "bg-card"}`}
              >
                <Text
                  className={`text-sm ${
                    filter === f ? "text-primary-foreground" : "text-foreground"
                  }`}
                >
                  {f === "Arkoor"
                    ? "Ark"
                    : f === "all"
                      ? "All"
                      : f === "Lightning"
                        ? "Lightning"
                        : f}
                </Text>
              </Pressable>
            ))}
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
                return (
                  <View style={{ marginBottom: 8 }}>
                    <Pressable
                      onPress={() => openTransaction(item)}
                      className="w-full flex-row items-center rounded-lg bg-card p-4"
                    >
                      <View pointerEvents="none" className="mr-4">
                        <Icon
                          name={getIconForType(item.type)}
                          size={24}
                          color={item.direction === "outgoing" ? "red" : "green"}
                        />
                      </View>
                      <View pointerEvents="none" className="flex-1">
                        <View className="flex-row justify-between gap-4">
                          <View className="flex-1">
                            <Text className="text-foreground text-base font-medium">
                              {item.type === "Bolt11" || item.type === "Lnurl"
                                ? "Lightning"
                                : item.type}
                            </Text>
                          </View>
                          <View className="items-end">
                            <Text
                              className={`text-base font-bold ${
                                item.direction === "outgoing" ? "text-red-500" : "text-green-500"
                              }`}
                            >
                              {`${item.direction === "outgoing" ? "-" : "+"}${formatBitcoinAmount(item.amount)}`}
                            </Text>
                          </View>
                        </View>
                        <Text className="text-muted-foreground text-sm mt-1">
                          {item.dateLabel ?? new Date(item.date).toLocaleString()}
                        </Text>
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
