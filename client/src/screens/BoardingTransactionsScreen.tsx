import { View, Pressable, ActivityIndicator } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import Share from "react-native-share";
import { useState } from "react";
import { FlashList } from "@shopify/flash-list";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { useNavigation } from "@react-navigation/native";
import { HomeStackParamList } from "~/Navigators";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { Result, ResultAsync } from "neverthrow";
import { CACHES_DIRECTORY_PATH } from "~/constants";
import RNFSTurbo from "react-native-fs-turbo";
import { useBoardingTransactions } from "~/hooks/useBoardingTransactions";
import type { BoardingTransaction } from "~/types/boardingTransaction";
import { formatMovementStatusLabel } from "~/types/movement";
import { formatBip177 } from "~/lib/utils";
import logger from "~/lib/log";
import { HistoryRefreshButton } from "~/components/HistoryRefreshButton";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { BoardingTransactionDetailContent } from "~/screens/BoardingTransactionDetailScreen";

const log = logger("BoardingTransactionsScreen");

type BoardingTransactionFilter = "all" | BoardingTransaction["type"];

const formatBoardingType = (type: BoardingTransaction["type"]) =>
  type === "onboarding" ? "Boarding" : "Offboarding";

const formatBoardingFilter = (filter: BoardingTransactionFilter) =>
  filter === "all" ? "All" : formatBoardingType(filter);

const formatBoardingStatus = (status: BoardingTransaction["status"]) => {
  return formatMovementStatusLabel(status) ?? status;
};

const BoardingTransactionsScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<HomeStackParamList>>();
  const iconColor = useIconColor();
  const {
    data: transactions = [],
    isLoading,
    isError,
    isRefetching,
    refetch,
  } = useBoardingTransactions();
  const [filter, setFilter] = useState<BoardingTransactionFilter>("all");
  const [selectedTransaction, setSelectedTransaction] = useState<BoardingTransaction | null>(null);
  const [isTransactionSheetOpen, setIsTransactionSheetOpen] = useState(false);

  const exportToCSV = async () => {
    const csvHeader = "Movement ID,Date,Type,Status,Amount (sats),Transaction ID,Destination\n";
    const csvRows = filteredTransactions
      .map((transaction) => {
        const date = new Date(transaction.date).toISOString().split("T")[0];
        const type = formatBoardingType(transaction.type);
        const status = formatBoardingStatus(transaction.status);
        const txid = transaction.txid || "";
        const destination = transaction.destination || "";

        return `${transaction.movementId},${date},${type},${status},${transaction.amountSat},${txid},${destination}`;
      })
      .join("\n");

    const csvContent = csvHeader + csvRows;
    const filename = `noah_boarding_transactions_${new Date().toISOString().split("T")[0]}.csv`;
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
        title: "Export Boarding Transactions",
        url: `file://${filePath}`,
        type: "text/csv",
        filename: filename,
        subject: "Noah Wallet Boarding Transaction Export",
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

  const filteredTransactions =
    filter === "all" ? transactions : transactions.filter((t) => t.type === filter);

  const handleRefresh = async () => {
    await refetch();
  };

  const openTransaction = (transaction: BoardingTransaction) => {
    setSelectedTransaction(transaction);
    setIsTransactionSheetOpen(true);
  };

  const getIconForType = (type: BoardingTransaction["type"]) => {
    switch (type) {
      case "onboarding":
        return "log-in-outline";
      case "offboarding":
        return "log-out-outline";
      default:
        return "boat-outline";
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case "successful":
        return "text-green-500";
      case "pending":
        return "text-yellow-500";
      case "failed":
        return "text-red-500";
      case "canceled":
        return "text-muted-foreground";
      default:
        return "text-muted-foreground";
    }
  };

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <NoahSafeAreaView className="flex-1 bg-background">
        <View className="p-4 flex-1">
          <View className="flex-row items-center justify-between mb-8">
            <View className="flex-row items-center">
              <Pressable onPress={() => navigation.goBack()} className="mr-4">
                <Icon name="arrow-back-outline" size={24} color={iconColor} />
              </Pressable>
              <Text className="text-2xl font-bold text-foreground">Boarding History</Text>
            </View>
            <View className="flex-row items-center gap-2">
              <HistoryRefreshButton isRefreshing={isRefetching} onRefresh={handleRefresh} />
              <Pressable
                onPress={exportToCSV}
                accessibilityRole="button"
                accessibilityLabel="Export boarding history"
                className="h-10 w-10 items-center justify-center rounded-full"
              >
                <Icon name="download-outline" size={24} color={iconColor} />
              </Pressable>
            </View>
          </View>
          <View className="flex-row justify-around mb-4">
            {(["all", "onboarding", "offboarding"] as const).map((f) => (
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
                  {formatBoardingFilter(f)}
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
              <Text className="text-muted-foreground mb-4">
                Failed to load boarding transactions
              </Text>
              <Pressable onPress={() => refetch()} className="px-4 py-2 bg-primary rounded-lg">
                <Text className="text-primary-foreground">Retry</Text>
              </Pressable>
            </View>
          ) : filteredTransactions.length === 0 ? (
            <View className="flex-1 items-center justify-center">
              <Icon name="boat-outline" size={48} color="#666" />
              <Text className="text-muted-foreground mt-4 text-center">
                No boarding transactions found
              </Text>
            </View>
          ) : (
            <FlashList
              data={filteredTransactions}
              renderItem={({ item }: { item: BoardingTransaction }) => {
                const statusLabel = formatBoardingStatus(item.status);

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
                          color={item.type === "onboarding" ? "green" : "orange"}
                        />
                      </View>
                      <View pointerEvents="none" className="flex-1">
                        <View className="flex-row justify-between items-center">
                          <Text className="text-foreground text-base font-medium">
                            {formatBoardingType(item.type)}
                          </Text>
                          <Text className={`text-sm font-medium ${getStatusColor(item.status)}`}>
                            {statusLabel}
                          </Text>
                        </View>
                        <Text className="text-foreground text-sm mt-1">
                          {formatBip177(item.amountSat)}
                        </Text>
                        <Text className="text-muted-foreground text-sm mt-1">
                          {new Date(item.date).toLocaleString()}
                        </Text>
                        {item.txid && (
                          <Text className="text-muted-foreground text-xs mt-1" numberOfLines={1}>
                            TXID: {item.txid}
                          </Text>
                        )}
                      </View>
                    </Pressable>
                  </View>
                );
              }}
              keyExtractor={(item: BoardingTransaction) => item.id}
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
            <BoardingTransactionDetailContent
              transaction={selectedTransaction}
              onClose={() => setIsTransactionSheetOpen(false)}
              closeIconName="close-outline"
            />
          </AppBottomSheet>
        ) : null}
      </NoahSafeAreaView>
    </GestureHandlerRootView>
  );
};

export default BoardingTransactionsScreen;
