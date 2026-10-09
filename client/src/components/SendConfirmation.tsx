import { T, useGT, useLocale } from "gt-react-native";
import { View } from "react-native";

import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { formatFiatAmount, satsToFiat, type FiatCurrencyCode } from "~/lib/fiatCurrency";
import type { BarkFeeEstimate, OnchainSendSource } from "~/lib/paymentsApi";
import { getOnchainSourceLabel, getSendRailLabel, type SendRail } from "~/lib/sendFlow";
import type { DestinationTypes, ParsedBip321 } from "~/lib/sendUtils";

type SendConfirmationProps = {
  destination: string;
  amount: number;
  amountNote?: string | null;
  isMaxAmount?: boolean;
  destinationType: DestinationTypes;
  comment?: string;
  btcPrice?: number;
  fiatCurrency: FiatCurrencyCode;
  bip321Data?: ParsedBip321 | null;
  selectedPaymentMethod?: "ark" | "lightning" | "onchain" | "offer";
  selectedRail: SendRail;
  selectedOnchainSource?: OnchainSendSource | null;
  onConfirm: () => void;
  onCancel: () => void;
  /** Shown for reusable recipients; opens the recurring payment editor prefilled. */
  onMakeRecurring?: () => void;
  isConfirmDisabled?: boolean;
  isLoading?: boolean;
  feeEstimate?: BarkFeeEstimate;
  isEstimatingFee?: boolean;
  feeEstimateError?: Error | null;
  feeEstimateUnavailableText?: string | null;
  feeEstimateNote?: string | null;
  feeEstimateWarning?: string | null;
  sendError?: string | null;
};

const truncateValue = (value: string) => {
  if (value.length <= 32) {
    return value;
  }

  return `${value.slice(0, 14)}…${value.slice(-10)}`;
};

const ReviewRow = ({ label, value }: { label: string; value: string }) => (
  <View className="flex-row items-start justify-between gap-5 py-3">
    <Text className="text-base text-muted-foreground">{label}</Text>
    <Text className="min-w-0 flex-1 text-right text-base font-semibold text-foreground">
      {value}
    </Text>
  </View>
);

export function SendConfirmation({
  destination,
  amount,
  amountNote = null,
  isMaxAmount = false,
  destinationType,
  comment,
  btcPrice,
  fiatCurrency,
  bip321Data,
  selectedPaymentMethod,
  selectedRail,
  selectedOnchainSource = null,
  onConfirm,
  onCancel,
  onMakeRecurring,
  isConfirmDisabled = false,
  isLoading = false,
  feeEstimate,
  isEstimatingFee = false,
  feeEstimateError = null,
  feeEstimateUnavailableText = null,
  feeEstimateNote = null,
  feeEstimateWarning = null,
  sendError = null,
}: SendConfirmationProps) {
  const locale = useLocale();
  const gt = useGT();
  const formatBitcoinAmount = useBitcoinAmountFormatter();

  const resolvedDestination = (() => {
    if (destinationType !== "bip321" || !bip321Data) {
      return destination;
    }
    if (selectedPaymentMethod === "ark") {
      return bip321Data.arkAddress ?? destination;
    }
    if (selectedPaymentMethod === "lightning") {
      return bip321Data.lightningInvoice ?? destination;
    }
    if (selectedPaymentMethod === "offer") {
      return bip321Data.offer ?? destination;
    }
    return bip321Data.onchainAddress ?? destination;
  })();
  const railLabel = getSendRailLabel(selectedRail, gt);
  const sourceLabel = selectedOnchainSource
    ? getOnchainSourceLabel(selectedOnchainSource, gt)
    : getOnchainSourceLabel("offchain", gt);
  const fiatAmount = btcPrice ? satsToFiat(amount, btcPrice, fiatCurrency) : null;
  const unavailableFeeText =
    feeEstimateUnavailableText ??
    (feeEstimateError
      ? gt("Fee estimate unavailable. The final fee will be calculated when sending.")
      : null);
  const amountPrefix = isMaxAmount ? (feeEstimate ? gt("Pay ≈ ") : gt("Pay up to ")) : gt("Pay ");
  const fiatPrefix = isMaxAmount ? (feeEstimate ? gt("Estimated ≈ ") : gt("Up to ≈ ")) : gt("≈ ");
  const feeValue = feeEstimate
    ? formatBitcoinAmount(feeEstimate.fee_sat)
    : isEstimatingFee
      ? gt("Estimating…")
      : isMaxAmount
        ? gt("Deducted from amount")
        : gt("Calculated when sent");
  const totalValue = feeEstimate
    ? formatBitcoinAmount(feeEstimate.gross_amount_sat)
    : isMaxAmount
      ? formatBitcoinAmount(amount)
      : gt("{value1} + fee", { value1: formatBitcoinAmount(amount) });

  return (
    <View className="pb-2" testID="send-review-sheet">
      <View className="items-center">
        <Text className="text-center text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
          {isLoading
            ? gt("Sending payment")
            : sendError
              ? gt("Payment failed")
              : gt("Review payment")}
        </Text>
        <Text className="mt-3 text-center text-4xl font-bold text-foreground">
          {amountPrefix}
          {formatBitcoinAmount(amount)}
        </Text>
        {fiatAmount ? (
          <Text className="mt-2 text-base font-medium text-muted-foreground">
            {fiatPrefix}
            {formatFiatAmount(fiatAmount, fiatCurrency, locale)}
          </Text>
        ) : null}
        {amountNote ? (
          <Text className="mt-2 max-w-[310px] text-center text-sm leading-5 text-muted-foreground">
            {amountNote}
          </Text>
        ) : null}
      </View>

      <View className="mt-6 border-y border-border/70 py-1">
        <ReviewRow label={gt("To")} value={truncateValue(resolvedDestination)} />
        <View className="h-px bg-border/60" />
        <ReviewRow label={gt("Pay via")} value={railLabel} />
        <View className="h-px bg-border/60" />
        <ReviewRow label={gt("Pay from")} value={sourceLabel} />
        <View className="h-px bg-border/60" />
        <ReviewRow
          label={gt("Settlement")}
          value={
            selectedRail === "onchain" ? gt("Requires network confirmation") : gt("Usually instant")
          }
        />
        {comment ? (
          <>
            <View className="h-px bg-border/60" />
            <ReviewRow label={gt("Note")} value={comment} />
          </>
        ) : null}
      </View>

      <View className="mt-4">
        <ReviewRow label={feeEstimate ? gt("Estimated fee") : gt("Fee")} value={feeValue} />
        <ReviewRow label={gt("Total deducted")} value={totalValue} />
        {unavailableFeeText ? (
          <Text className="text-sm leading-5 text-muted-foreground">{unavailableFeeText}</Text>
        ) : null}
        {feeEstimateNote ? (
          <Text className="mt-2 text-xs leading-5 text-muted-foreground">{feeEstimateNote}</Text>
        ) : null}
      </View>

      {feeEstimateWarning ? (
        <View className="mt-4 rounded-2xl border border-amber-500/40 bg-amber-500/10 px-4 py-3">
          <Text className="text-sm leading-5 text-amber-700 dark:text-amber-200">
            {feeEstimateWarning}
          </Text>
        </View>
      ) : null}

      {sendError ? (
        <View className="mt-4 rounded-2xl border border-destructive/35 bg-destructive/10 px-4 py-3">
          <T>
            <Text className="text-sm font-semibold text-destructive">Payment did not send</Text>
          </T>
          <Text className="mt-1 text-sm leading-5 text-destructive/90">{sendError}</Text>
        </View>
      ) : null}

      <View className="mt-6 gap-3">
        <NativeNoahSecondaryButton
          label={gt("Back")}
          onPress={onCancel}
          disabled={isLoading}
          size="lg"
          fullWidth
          testID="send-review-back"
        />
        <NativeNoahButton
          label={sendError ? gt("Retry payment") : gt("Confirm payment")}
          loadingLabel={gt("Sending…")}
          onPress={onConfirm}
          disabled={isConfirmDisabled}
          isLoading={isLoading}
          size="lg"
          fullWidth
          testID="send-review-confirm"
        />
        {onMakeRecurring ? (
          <NativeNoahSecondaryButton
            label={gt("Make this a recurring payment")}
            onPress={onMakeRecurring}
            disabled={isLoading}
            size="lg"
            fullWidth
            testID="send-review-make-recurring"
          />
        ) : null}
      </View>
    </View>
  );
}
