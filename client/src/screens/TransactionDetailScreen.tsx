import { View, Pressable, ScrollView, Linking } from "react-native";
import { useRoute, useNavigation } from "@react-navigation/native";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { copyToClipboard } from "../lib/clipboardUtils";
import { type Transaction } from "../types/transaction";
import { type ComponentProps, useState } from "react";
import { COLORS } from "~/lib/styleConstants";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import { formatMovementKindLabel, formatMovementStatusLabel } from "~/types/movement";
import { getMempoolTxUrl } from "~/constants";
import { useProfileStore } from "~/store/profileStore";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { getTransactionDisplayLabel } from "~/lib/transactionHistory";

const TransactionDetailRow = ({
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

const MovementDestinationList = ({
  title,
  destinations,
}: {
  title: string;
  destinations: NonNullable<Transaction["sentTo"]>;
}) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();

  return (
    <View className="bg-card p-4 rounded-lg mb-4">
      <Text className="text-sm font-semibold text-foreground mb-3">{title}</Text>
      {destinations.map((dest, index) => (
        <View
          key={`${dest.destination}-${index}`}
          className="py-2 border-b border-border/10 last:border-b-0"
        >
          <Text className="text-foreground text-sm mb-1" numberOfLines={2}>
            {dest.destination}
          </Text>
          <Text className="text-muted-foreground text-xs">
            {formatBitcoinAmount(dest.amount_sat)}
          </Text>
        </View>
      ))}
    </View>
  );
};

export const TransactionDetailContent = ({
  transaction,
  fiatCurrency,
  onClose,
  closeIconName = "arrow-back-outline",
}: {
  transaction: Transaction;
  fiatCurrency: FiatCurrencyCode;
  onClose?: () => void;
  closeIconName?: ComponentProps<typeof Icon>["name"];
}) => {
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();

  const fiatAmount = transaction.btcPrice
    ? satsToFiat(transaction.amount, transaction.btcPrice, fiatCurrency)
    : "N/A";
  const bitcoinPrice = transaction.btcPrice
    ? formatFiatAmount(transaction.btcPrice, fiatCurrency)
    : "N/A";
  const formattedFiatAmount =
    fiatAmount === "N/A" ? fiatAmount : formatFiatAmount(fiatAmount, fiatCurrency);
  const transactionDateLabel = transaction.dateLabel ?? new Date(transaction.date).toLocaleString();
  const movementStatusLabel = formatMovementStatusLabel(transaction.movementStatus);
  const movementKindLabel = formatMovementKindLabel(transaction.movementKind);
  const hasMovementDetails = Boolean(
    movementStatusLabel ||
    movementKindLabel ||
    transaction.subsystemName ||
    transaction.subsystemKind ||
    typeof transaction.intendedBalanceSat === "number" ||
    typeof transaction.effectiveBalanceSat === "number" ||
    typeof transaction.offchainFeeSat === "number" ||
    typeof transaction.movementId === "number",
  );
  const hasOnchainWalletDetails =
    transaction.source === "onchain-wallet" || typeof transaction.balanceChangeSat === "number";
  const onchainExplorerUrl =
    hasOnchainWalletDetails && transaction.txid ? getMempoolTxUrl(transaction.txid) : null;
  const arkSendOnchainExplorerUrl =
    !hasOnchainWalletDetails && transaction.type === "Onchain" && transaction.txid
      ? getMempoolTxUrl(transaction.txid)
      : null;

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
          {getTransactionDisplayLabel(transaction)}
        </Text>
      </View>

      <View className="items-center my-8">
        <Text className="text-4xl font-bold text-foreground">
          {formatBitcoinAmount(transaction.amount)}
        </Text>
        <Text className="text-xl text-muted-foreground">{formattedFiatAmount}</Text>
      </View>

      <View className="bg-card p-4 rounded-lg mb-4">
        <TransactionDetailRow label={`Bitcoin Price (${fiatCurrency})`} value={bitcoinPrice} />
        <TransactionDetailRow
          label="Amount"
          value={`${formatBitcoinAmount(transaction.amount)} (${formattedFiatAmount})`}
        />
      </View>

      {hasOnchainWalletDetails ? (
        <View className="bg-card p-4 rounded-lg mb-4">
          <Text className="text-lg font-semibold text-foreground mb-3">
            Onchain Wallet Transaction
          </Text>
          {transaction.txid ? (
            <TransactionDetailRow
              label="Transaction ID"
              value={transaction.txid}
              copyable
              explorerUrl={onchainExplorerUrl}
            />
          ) : null}
          <TransactionDetailRow
            label="Status"
            value={transaction.hasConfirmation ? "Confirmed" : "Unconfirmed"}
          />
          {typeof transaction.balanceChangeSat === "number" ? (
            <TransactionDetailRow
              label="Balance Δ"
              value={formatBitcoinAmount(transaction.balanceChangeSat)}
            />
          ) : null}
          {transaction.hasOnchainFee && typeof transaction.onchainFeeSat === "number" ? (
            <TransactionDetailRow
              label="Onchain Fee"
              value={formatBitcoinAmount(transaction.onchainFeeSat)}
            />
          ) : null}
          {typeof transaction.confirmationHeight === "number" ? (
            <TransactionDetailRow
              label="Confirmation Height"
              value={transaction.confirmationHeight.toString()}
            />
          ) : null}
          {transaction.confirmationHash ? (
            <TransactionDetailRow
              label="Block Hash"
              value={transaction.confirmationHash}
              copyable
            />
          ) : null}
          {transaction.txHex ? (
            <TransactionDetailRow label="Raw Transaction" value={transaction.txHex} copyable />
          ) : null}
        </View>
      ) : null}

      {hasMovementDetails ? (
        <View className="bg-card p-4 rounded-lg mb-4">
          <Text className="text-lg font-semibold text-foreground mb-3">Ark Movement</Text>
          {movementKindLabel ? (
            <TransactionDetailRow label="Type" value={movementKindLabel} />
          ) : null}
          {movementStatusLabel ? (
            <TransactionDetailRow label="Status" value={movementStatusLabel} />
          ) : null}
          {transaction.movementId !== undefined ? (
            <TransactionDetailRow
              label="Movement ID"
              value={transaction.movementId.toString()}
              copyable
            />
          ) : null}
          {transaction.subsystemName ? (
            <TransactionDetailRow
              label="Subsystem"
              value={
                transaction.subsystemKind
                  ? `${transaction.subsystemName} (${transaction.subsystemKind})`
                  : transaction.subsystemName
              }
            />
          ) : null}
          {transaction.chainAnchor && transaction.chainAnchor !== transaction.txid ? (
            <TransactionDetailRow label="Chain Anchor" value={transaction.chainAnchor} copyable />
          ) : null}
          {typeof transaction.intendedBalanceSat === "number" ? (
            <TransactionDetailRow
              label="Intended Δ"
              value={formatBitcoinAmount(transaction.intendedBalanceSat)}
            />
          ) : null}
          {typeof transaction.effectiveBalanceSat === "number" ? (
            <TransactionDetailRow
              label="Effective Δ"
              value={formatBitcoinAmount(transaction.effectiveBalanceSat)}
            />
          ) : null}
          {typeof transaction.offchainFeeSat === "number" ? (
            <TransactionDetailRow
              label="Offchain Fee"
              value={formatBitcoinAmount(transaction.offchainFeeSat)}
            />
          ) : null}
        </View>
      ) : null}

      <View className="bg-card p-4 rounded-lg">
        {transaction.description ? (
          <TransactionDetailRow label="Note" value={transaction.description} />
        ) : null}
        <TransactionDetailRow
          label={transaction.dateLabel ? "Confirmation" : "Date & time"}
          value={transactionDateLabel}
        />
        <TransactionDetailRow label="Payment ID" value={transaction.id} copyable />
        {transaction.txid && !hasOnchainWalletDetails ? (
          <TransactionDetailRow
            label="Transaction ID"
            value={transaction.txid}
            copyable
            explorerUrl={arkSendOnchainExplorerUrl}
          />
        ) : null}
        {transaction.preimage ? (
          <TransactionDetailRow label="Preimage" value={transaction.preimage} copyable />
        ) : null}
        {transaction.destination ? (
          <TransactionDetailRow label="Destination" value={transaction.destination} copyable />
        ) : null}
        {transaction.receivedOn &&
        transaction.receivedOn.length === 1 &&
        transaction.receivedOn[0]?.destination ? (
          <TransactionDetailRow
            label="Invoice"
            value={transaction.receivedOn[0].destination}
            copyable
          />
        ) : null}
      </View>

      {transaction.sentTo && transaction.sentTo.length > 0 ? (
        <MovementDestinationList title="Sent To" destinations={transaction.sentTo} />
      ) : null}

      {transaction.receivedOn && transaction.receivedOn.length > 0 ? (
        <MovementDestinationList title="Received On" destinations={transaction.receivedOn} />
      ) : null}
    </ScrollView>
  );
};

const TransactionDetailScreen = () => {
  const route = useRoute();
  const navigation = useNavigation();
  const { transaction } = route.params as { transaction: Transaction };
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <TransactionDetailContent
        transaction={transaction}
        fiatCurrency={fiatCurrency}
        onClose={() => navigation.goBack()}
      />
    </NoahSafeAreaView>
  );
};

export default TransactionDetailScreen;
