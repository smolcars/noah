import { View, Pressable, ScrollView, Linking } from "react-native";
import { useRoute, useNavigation } from "@react-navigation/native";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { copyToClipboard } from "../lib/clipboardUtils";
import { type ComponentProps, useState } from "react";
import { COLORS } from "~/lib/styleConstants";
import type { BoardingTransaction } from "~/types/boardingTransaction";
import { formatMovementStatusLabel } from "~/types/movement";
import { getMempoolTxUrl } from "~/constants";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

const BoardingTransactionDetailRow = ({
  label,
  value,
  copyable = false,
  explorerUrl,
}: {
  label: string;
  value: string;
  copyable?: boolean;
  explorerUrl?: string | null;
}) => {
  const [copied, setCopied] = useState(false);
  const iconColor = useIconColor();

  const onCopy = async () => {
    await copyToClipboard(value, {
      onCopy: () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1000);
      },
    });
  };

  return (
    <View className="flex-row justify-between items-center py-3 border-b border-border/10 last:border-b-0">
      <Text className="text-muted-foreground text-sm">{label}</Text>
      {copyable || explorerUrl ? (
        <View className="flex-row items-center gap-x-3 flex-shrink-0">
          {copyable ? (
            <Pressable onPress={onCopy} className="flex-row items-center gap-x-2 flex-shrink-0">
              <Text
                className="text-foreground text-sm text-right"
                ellipsizeMode="middle"
                numberOfLines={1}
                style={{ maxWidth: 150 }}
              >
                {value}
              </Text>
              {copied ? (
                <Icon name="checkmark-circle-outline" size={16} color={COLORS.SUCCESS} />
              ) : (
                <Icon name="copy-outline" size={16} color={iconColor} />
              )}
            </Pressable>
          ) : null}
          {explorerUrl ? (
            <Pressable
              onPress={() => Linking.openURL(explorerUrl)}
              hitSlop={10}
              className="h-8 w-8 items-center justify-center rounded-full bg-background"
            >
              <Icon name="open-outline" size={17} color={COLORS.BITCOIN_ORANGE} />
            </Pressable>
          ) : null}
        </View>
      ) : (
        <Text
          className="text-foreground text-sm text-right"
          ellipsizeMode="tail"
          numberOfLines={2}
          style={{ maxWidth: 200 }}
        >
          {value}
        </Text>
      )}
    </View>
  );
};

const formatBoardingType = (type: BoardingTransaction["type"]) =>
  type === "onboarding" ? "Boarding" : "Offboarding";

const formatBoardingStatus = (transaction: BoardingTransaction) => {
  return formatMovementStatusLabel(transaction.status) ?? transaction.status;
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

const getStatusIcon = (status: string) => {
  switch (status) {
    case "successful":
      return "checkmark-circle-outline";
    case "pending":
      return "time-outline";
    case "failed":
      return "close-circle-outline";
    default:
      return "help-circle-outline";
  }
};

const getTypeIcon = (type: string) => {
  return type === "onboarding" ? "log-in-outline" : "log-out-outline";
};

const getTypeColor = (type: string) => {
  return type === "onboarding" ? "#22c55e" : "#f97316";
};

export const BoardingTransactionDetailContent = ({
  transaction,
  onClose,
  closeIconName = "arrow-back-outline",
}: {
  transaction: BoardingTransaction;
  onClose?: () => void;
  closeIconName?: ComponentProps<typeof Icon>["name"];
}) => {
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const explorerUrl = transaction.txid ? getMempoolTxUrl(transaction.txid) : null;
  const statusLabel = formatBoardingStatus(transaction);

  return (
    <ScrollView
      className="flex-1"
      contentContainerStyle={{ padding: 16, paddingBottom: 48 }}
      showsVerticalScrollIndicator={false}
    >
      <View className="flex-row items-center mb-8">
        {onClose ? (
          <Pressable onPress={onClose} className="mr-4">
            <Icon name={closeIconName} size={24} color={iconColor} />
          </Pressable>
        ) : null}
        <Text className="text-2xl font-bold text-foreground">
          {formatBoardingType(transaction.type)} Details
        </Text>
      </View>

      <View className="items-center my-8">
        <View className="mb-4">
          <Icon
            name={getTypeIcon(transaction.type)}
            size={64}
            color={getTypeColor(transaction.type)}
          />
        </View>
        <Text className="text-3xl font-bold text-foreground mb-2">
          {formatBoardingType(transaction.type)}
        </Text>
        {statusLabel ? (
          <View className="flex-row items-center">
            <Icon
              name={getStatusIcon(transaction.status)}
              size={20}
              color={
                getStatusColor(transaction.status).includes("yellow")
                  ? "#eab308"
                  : getStatusColor(transaction.status).includes("red")
                    ? "#ef4444"
                    : getStatusColor(transaction.status).includes("green")
                      ? "#22c55e"
                      : "#6b7280"
              }
            />
            <Text className={`text-xl font-medium ml-2 ${getStatusColor(transaction.status)}`}>
              {statusLabel}
            </Text>
          </View>
        ) : null}
      </View>

      <View className="bg-card p-4 rounded-lg mb-4">
        <BoardingTransactionDetailRow
          label="Movement ID"
          value={transaction.movementId.toString()}
          copyable
        />
        <BoardingTransactionDetailRow
          label="Date & time"
          value={new Date(transaction.date).toLocaleString()}
        />
        <BoardingTransactionDetailRow label="Type" value={formatBoardingType(transaction.type)} />
        <BoardingTransactionDetailRow label="Status" value={statusLabel} />
        <BoardingTransactionDetailRow
          label="Amount"
          value={formatBitcoinAmount(transaction.amountSat)}
        />
      </View>

      {(transaction.txid || transaction.destination) && (
        <View className="bg-card p-4 rounded-lg mb-4">
          <Text className="text-foreground text-lg font-semibold mb-3">Onchain Transaction</Text>
          {transaction.txid ? (
            <BoardingTransactionDetailRow
              label="Transaction ID"
              value={transaction.txid}
              copyable
              explorerUrl={explorerUrl}
            />
          ) : null}
          {transaction.destination ? (
            <BoardingTransactionDetailRow
              label="Destination"
              value={transaction.destination}
              copyable
            />
          ) : null}
        </View>
      )}

      <View className="bg-card p-4 rounded-lg mb-4">
        <Text className="text-foreground text-lg font-semibold mb-3">Movement</Text>
        {typeof transaction.intendedBalanceSat === "number" ? (
          <BoardingTransactionDetailRow
            label="Intended Delta"
            value={formatBitcoinAmount(transaction.intendedBalanceSat)}
          />
        ) : null}
        {typeof transaction.effectiveBalanceSat === "number" ? (
          <BoardingTransactionDetailRow
            label="Effective Delta"
            value={formatBitcoinAmount(transaction.effectiveBalanceSat)}
          />
        ) : null}
        {typeof transaction.offchainFeeSat === "number" ? (
          <BoardingTransactionDetailRow
            label="Offchain Fee"
            value={formatBitcoinAmount(transaction.offchainFeeSat)}
          />
        ) : null}
        {typeof transaction.onchainFeeSat === "number" ? (
          <BoardingTransactionDetailRow
            label="Onchain Fee"
            value={formatBitcoinAmount(transaction.onchainFeeSat)}
          />
        ) : null}
      </View>

      <View className="bg-card p-4 rounded-lg">
        <Text className="text-foreground text-lg font-semibold mb-3">Description</Text>
        <Text className="text-muted-foreground text-sm">
          {transaction.type === "onboarding"
            ? "Boarding transaction to enter the Ark network. Your Bitcoin was moved from the onchain wallet to the offchain Ark balance."
            : "Offboarding transaction to exit the Ark network. Your Ark balance was converted back to onchain Bitcoin."}
        </Text>
        {transaction.status === "pending" && (
          <Text className="text-yellow-500 text-sm mt-2">
            {transaction.type === "onboarding"
              ? "This boarding request is being processed. It will be completed when the next Ark round starts."
              : "This offboarding request is being processed. It will be completed when the next Ark round starts."}
          </Text>
        )}
      </View>
    </ScrollView>
  );
};

const BoardingTransactionDetailScreen = () => {
  const route = useRoute();
  const navigation = useNavigation();
  const { transaction } = route.params as { transaction: BoardingTransaction };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <BoardingTransactionDetailContent
        transaction={transaction}
        onClose={() => navigation.goBack()}
      />
    </NoahSafeAreaView>
  );
};

export default BoardingTransactionDetailScreen;
