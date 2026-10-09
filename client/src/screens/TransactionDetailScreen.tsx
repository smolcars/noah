import { useLocale, T, useGT } from "gt-react-native";
import { View, Pressable, ScrollView, Linking } from "react-native";
import { useRoute, useNavigation } from "@react-navigation/native";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { copyToClipboard } from "../lib/clipboardUtils";
import { type Transaction } from "../types/transaction";
import { type ComponentProps, type ReactNode, useState } from "react";
import { COLORS } from "~/lib/styleConstants";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import { formatMovementKindLabel, formatMovementStatusLabel } from "~/types/movement";
import { getMempoolTxUrl } from "~/constants";
import { useProfileStore } from "~/store/profileStore";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import {
  getTransactionAccountingValues,
  getTransactionDisplayLabel,
  getTransactionConfirmationLabel,
} from "~/lib/transactionHistory";
import { canRepeatPayment } from "~/lib/repeatPayment";
import type { RepeatPaymentDetails } from "~/types/repeatPayment";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";

const getTransactionIcon = (transaction: Transaction): ComponentProps<typeof Icon>["name"] => {
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

const DetailSection = ({ title, children }: { title: string; children: ReactNode }) => (
  <View className="mt-8">
    <Text className="mb-1 text-xs font-semibold uppercase tracking-[2px] text-muted-foreground">
      {title}
    </Text>
    <View className="border-t border-border/40">{children}</View>
  </View>
);

const PaymentDestination = ({ value }: { value: string }) => {
  const gt = useGT();
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
    <Pressable
      accessibilityLabel={gt("Copy destination {value1}", { value1: value })}
      accessibilityRole="button"
      className="mt-2 max-w-[320px] flex-row items-center justify-center gap-2 px-4"
      onPress={onCopy}
    >
      <Text
        className="min-w-0 text-center text-base font-medium leading-6 text-foreground"
        ellipsizeMode="middle"
        numberOfLines={2}
      >
        {value}
      </Text>
      <Icon
        name={copied ? "checkmark-circle-outline" : "copy-outline"}
        size={17}
        color={copied ? COLORS.SUCCESS : iconColor}
      />
    </Pressable>
  );
};

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
  const gt = useGT();
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
    <View className="flex-row items-start justify-between gap-4 border-b border-border/20 py-3.5 last:border-b-0">
      <Text className="w-[112px] text-sm text-muted-foreground">{label}</Text>
      {copyable || explorerUrl ? (
        <View className="min-w-0 flex-1 flex-row items-center justify-end gap-x-2">
          {copyable ? (
            <Pressable
              accessibilityLabel={gt("Copy {value1}", { value1: label })}
              accessibilityRole="button"
              onPress={onCopy}
              className="min-w-0 flex-1 flex-row items-center justify-end gap-x-2"
            >
              <Text
                className="min-w-0 flex-shrink text-right text-sm text-foreground"
                ellipsizeMode="middle"
                numberOfLines={1}
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
          className="min-w-0 flex-1 text-right text-sm leading-5 text-foreground"
          ellipsizeMode="tail"
          numberOfLines={3}
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
    <View className="mt-5 border-t border-border/30 pt-4">
      <Text className="text-xs font-semibold uppercase tracking-[1.6px] text-muted-foreground">
        {title}
      </Text>
      {destinations.map((dest, index) => (
        <View
          key={`${dest.destination}-${index}`}
          className="border-b border-border/20 py-3 last:border-b-0"
        >
          <Text className="mb-1 text-sm leading-5 text-foreground" numberOfLines={2}>
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
  onRepeatPayment,
  closeIconName = "arrow-back-outline",
}: {
  transaction: Transaction;
  fiatCurrency: FiatCurrencyCode;
  onClose?: () => void;
  onRepeatPayment?: (details: RepeatPaymentDetails) => void;
  closeIconName?: ComponentProps<typeof Icon>["name"];
}) => {
  const gt = useGT();
  const locale = useLocale();
  const [showTechnicalDetails, setShowTechnicalDetails] = useState(false);
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();

  const fiatAmount = transaction.btcPrice
    ? satsToFiat(transaction.amount, transaction.btcPrice, fiatCurrency)
    : "N/A";
  const formattedFiatAmount =
    fiatAmount === "N/A" ? fiatAmount : formatFiatAmount(fiatAmount, fiatCurrency, locale);
  const transactionDateLabel =
    getTransactionConfirmationLabel(transaction, gt) ??
    new Date(transaction.date).toLocaleString(locale);
  const movementStatusLabel = formatMovementStatusLabel(transaction.movementStatus, gt);
  const movementKindLabel = formatMovementKindLabel(transaction.movementKind, gt);
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
  const repeatPaymentDetails = transaction.repeatPayment;
  const isRepeatable = Boolean(
    onRepeatPayment && canRepeatPayment(transaction) && repeatPaymentDetails,
  );
  const isCompleted =
    transaction.movementStatus === "successful" ||
    (hasOnchainWalletDetails && transaction.hasConfirmation === true);
  const isFailed =
    transaction.movementStatus === "failed" || transaction.movementStatus === "canceled";
  const statusLabel = isCompleted
    ? gt("Completed")
    : movementStatusLabel ||
      (hasOnchainWalletDetails
        ? transaction.hasConfirmation
          ? gt("Confirmed")
          : gt("Unconfirmed")
        : gt("Recorded"));
  const statusColor = isCompleted ? COLORS.SUCCESS : isFailed ? "#ef4444" : COLORS.BITCOIN_ORANGE;
  const accountingDirection = getTransactionAccountingValues(transaction).direction;
  const receiptActionLabel =
    accountingDirection === "Transfer"
      ? gt("Transferred")
      : accountingDirection === "None"
        ? gt("Canceled")
        : accountingDirection === "Outgoing"
          ? gt("Sent")
          : gt("Received");
  const paymentRoute =
    transaction.type === "Lnurl"
      ? gt("Lightning address")
      : getTransactionDisplayLabel(transaction, gt);
  const enteredAmount =
    repeatPaymentDetails?.amountMode === "FIAT" && repeatPaymentDetails.fiatCurrency
      ? formatFiatAmount(
          repeatPaymentDetails.amountInput,
          repeatPaymentDetails.fiatCurrency,
          locale,
        )
      : null;
  const offchainFeeSat =
    typeof transaction.offchainFeeSat === "number" ? transaction.offchainFeeSat : undefined;
  const onchainFeeSat =
    transaction.hasOnchainFee && typeof transaction.onchainFeeSat === "number"
      ? transaction.onchainFeeSat
      : undefined;

  return (
    <ScrollView
      className="flex-1"
      contentContainerStyle={{ padding: 16, paddingBottom: 40 }}
      showsVerticalScrollIndicator={false}
    >
      <View className="relative flex-row items-center justify-between">
        {onClose ? (
          <Pressable
            accessibilityLabel={gt("Close payment details")}
            accessibilityRole="button"
            onPress={onClose}
            className="z-10 h-10 w-10 items-center justify-center rounded-full bg-muted/60"
          >
            <Icon name={closeIconName} size={20} color={iconColor} />
          </Pressable>
        ) : (
          <View className="h-10 w-10" />
        )}
        <T>
          <Text className="absolute left-0 right-0 text-center text-base font-semibold text-foreground">
            Payment details
          </Text>
        </T>
        <View
          className="z-10 rounded-full px-3 py-1.5"
          style={{ backgroundColor: `${statusColor}18` }}
        >
          <Text className="text-xs font-semibold" style={{ color: statusColor }}>
            {statusLabel}
          </Text>
        </View>
      </View>

      <View className="items-center pb-2 pt-8">
        <View
          className="h-14 w-14 items-center justify-center rounded-full"
          style={{ backgroundColor: `${COLORS.BITCOIN_ORANGE}18` }}
        >
          <Icon name={getTransactionIcon(transaction)} size={26} color={COLORS.BITCOIN_ORANGE} />
        </View>
        <Text className="mt-4 text-xs font-semibold uppercase tracking-[2px] text-muted-foreground">
          {receiptActionLabel}
        </Text>
        <Text className="mt-1 text-4xl font-bold tracking-tight text-foreground">
          {formatBitcoinAmount(transaction.amount)}
        </Text>
        {formattedFiatAmount !== "N/A" ? (
          <Text className="mt-1 text-base text-muted-foreground">≈ {formattedFiatAmount}</Text>
        ) : null}
        {transaction.destination ? (
          <>
            <Text className="mt-6 text-xs font-medium uppercase tracking-[1.8px] text-muted-foreground">
              {transaction.direction === "outgoing" ? gt("Paid to") : gt("Received on")}
            </Text>
            <PaymentDestination value={transaction.destination} />
            {isRepeatable && repeatPaymentDetails ? (
              <NativeNoahButton
                className="mt-4"
                label={gt("Pay again")}
                onPress={() => onRepeatPayment?.(repeatPaymentDetails)}
                size="sm"
                testID="repeat-payment-button"
                width={148}
              />
            ) : null}
          </>
        ) : null}
      </View>

      <DetailSection title={gt("Overview")}>
        <TransactionDetailRow
          label={transaction.dateLabel ? gt("Confirmation") : gt("Date & time")}
          value={transactionDateLabel}
        />
        <TransactionDetailRow label={gt("Route")} value={paymentRoute} />
        {enteredAmount ? (
          <TransactionDetailRow label={gt("Entered as")} value={enteredAmount} />
        ) : null}
        {offchainFeeSat !== undefined ? (
          <TransactionDetailRow
            label={onchainFeeSat !== undefined ? gt("Offchain fee") : gt("Fee")}
            value={offchainFeeSat === 0 ? gt("No fee") : formatBitcoinAmount(offchainFeeSat)}
          />
        ) : null}
        {onchainFeeSat !== undefined ? (
          <TransactionDetailRow
            label={offchainFeeSat !== undefined ? gt("Onchain fee") : gt("Fee")}
            value={onchainFeeSat === 0 ? gt("No fee") : formatBitcoinAmount(onchainFeeSat)}
          />
        ) : null}
        {transaction.description ? (
          <TransactionDetailRow label={gt("Note")} value={transaction.description} />
        ) : null}
        {repeatPaymentDetails?.comment ? (
          <TransactionDetailRow label={gt("Message")} value={repeatPaymentDetails.comment} />
        ) : null}
      </DetailSection>

      <Pressable
        accessibilityRole="button"
        accessibilityState={{ expanded: showTechnicalDetails }}
        className="mt-7 flex-row items-center justify-between border-y border-border/40 py-4"
        onPress={() => setShowTechnicalDetails((isVisible) => !isVisible)}
        testID="technical-details-toggle"
      >
        <View>
          <T>
            <Text className="text-sm font-semibold text-foreground">More details</Text>
          </T>
          <T>
            <Text className="mt-0.5 text-xs text-muted-foreground">
              IDs, routing, and movement data
            </Text>
          </T>
        </View>
        <Icon
          name={showTechnicalDetails ? "chevron-up-outline" : "chevron-down-outline"}
          size={18}
          color={iconColor}
        />
      </Pressable>

      {showTechnicalDetails ? (
        <View className="border-b border-border/40 pb-4">
          <TransactionDetailRow label={gt("Payment ID")} value={transaction.id} copyable />
          {transaction.txid ? (
            <TransactionDetailRow
              label={gt("Transaction ID")}
              value={transaction.txid}
              copyable
              explorerUrl={hasOnchainWalletDetails ? onchainExplorerUrl : arkSendOnchainExplorerUrl}
            />
          ) : null}
          {transaction.preimage ? (
            <TransactionDetailRow label={gt("Preimage")} value={transaction.preimage} copyable />
          ) : null}
          {transaction.receivedOn?.length === 1 && transaction.receivedOn[0]?.destination ? (
            <TransactionDetailRow
              label={gt("Invoice")}
              value={transaction.receivedOn[0].destination}
              copyable
            />
          ) : null}

          {hasOnchainWalletDetails ? (
            <>
              <TransactionDetailRow
                label={gt("Chain status")}
                value={transaction.hasConfirmation ? gt("Confirmed") : gt("Unconfirmed")}
              />
              {typeof transaction.balanceChangeSat === "number" ? (
                <TransactionDetailRow
                  label={gt("Balance change")}
                  value={formatBitcoinAmount(transaction.balanceChangeSat)}
                />
              ) : null}
              {typeof transaction.confirmationHeight === "number" ? (
                <TransactionDetailRow
                  label={gt("Block height")}
                  value={transaction.confirmationHeight.toString()}
                />
              ) : null}
              {transaction.confirmationHash ? (
                <TransactionDetailRow
                  label={gt("Block hash")}
                  value={transaction.confirmationHash}
                  copyable
                />
              ) : null}
              {transaction.txHex ? (
                <TransactionDetailRow
                  label={gt("Raw transaction")}
                  value={transaction.txHex}
                  copyable
                />
              ) : null}
            </>
          ) : null}

          {hasMovementDetails ? (
            <>
              {movementKindLabel ? (
                <TransactionDetailRow label={gt("Movement type")} value={movementKindLabel} />
              ) : null}
              {transaction.movementId !== undefined ? (
                <TransactionDetailRow
                  label={gt("Movement ID")}
                  value={transaction.movementId.toString()}
                  copyable
                />
              ) : null}
              {transaction.subsystemName ? (
                <TransactionDetailRow
                  label={gt("Subsystem")}
                  value={
                    transaction.subsystemKind
                      ? `${transaction.subsystemName} (${transaction.subsystemKind})`
                      : transaction.subsystemName
                  }
                />
              ) : null}
              {transaction.chainAnchor && transaction.chainAnchor !== transaction.txid ? (
                <TransactionDetailRow
                  label={gt("Chain anchor")}
                  value={transaction.chainAnchor}
                  copyable
                />
              ) : null}
              {typeof transaction.intendedBalanceSat === "number" ? (
                <TransactionDetailRow
                  label={gt("Intended change")}
                  value={formatBitcoinAmount(transaction.intendedBalanceSat)}
                />
              ) : null}
              {typeof transaction.effectiveBalanceSat === "number" ? (
                <TransactionDetailRow
                  label={gt("Effective change")}
                  value={formatBitcoinAmount(transaction.effectiveBalanceSat)}
                />
              ) : null}
            </>
          ) : null}

          {transaction.sentTo && transaction.sentTo.length > 0 ? (
            <MovementDestinationList title={gt("Sent to")} destinations={transaction.sentTo} />
          ) : null}
          {transaction.receivedOn && transaction.receivedOn.length > 0 ? (
            <MovementDestinationList
              title={gt("Received on")}
              destinations={transaction.receivedOn}
            />
          ) : null}
        </View>
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
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <TransactionDetailContent
        transaction={transaction}
        fiatCurrency={fiatCurrency}
        onClose={() => navigation.goBack()}
      />
    </NoahSafeAreaView>
  );
};

export default TransactionDetailScreen;
