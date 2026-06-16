import React from "react";
import { Pressable, View } from "react-native";
import { Text } from "./ui/text";
import { NoahButton } from "./ui/NoahButton";
import { Button } from "./ui/button";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import { DestinationTypes, ParsedBip321 } from "~/lib/sendUtils";
import { useThemeColors } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";
import { Bip321Picker } from "./Bip321Picker";
import { FeeEstimateSummary } from "./FeeEstimateSummary";
import type { BarkFeeEstimate, OnchainSendSource } from "~/lib/paymentsApi";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

interface SendConfirmationProps {
  destination: string;
  amount: number;
  destinationType: DestinationTypes;
  comment?: string;
  btcPrice?: number;
  fiatCurrency: FiatCurrencyCode;
  bip321Data?: ParsedBip321 | null;
  selectedPaymentMethod?: "ark" | "lightning" | "onchain" | "offer";
  onSelectPaymentMethod?: (type: "ark" | "lightning" | "onchain" | "offer") => void;
  onchainSourceOptions?: OnchainSendSource[];
  selectedOnchainSource?: OnchainSendSource | null;
  onSelectOnchainSource?: (source: OnchainSendSource) => void;
  onchainWalletBalance?: number;
  offchainWalletBalance?: number;
  onConfirm: () => void;
  onCancel: () => void;
  isConfirmDisabled?: boolean;
  isLoading?: boolean;
  feeEstimate?: BarkFeeEstimate;
  isEstimatingFee?: boolean;
  feeEstimateError?: Error | null;
  feeEstimateUnavailableText?: string | null;
  feeEstimateNote?: string | null;
  feeEstimateWarning?: string | null;
  sendError?: string | null;
}

const truncateValue = (value: string) => {
  if (value.length <= 32) {
    return value;
  }

  return `${value.slice(0, 14)}...${value.slice(-10)}`;
};

export const SendConfirmation: React.FC<SendConfirmationProps> = ({
  destination,
  amount,
  destinationType,
  comment,
  btcPrice,
  fiatCurrency,
  bip321Data,
  selectedPaymentMethod,
  onSelectPaymentMethod,
  onchainSourceOptions = [],
  selectedOnchainSource = null,
  onSelectOnchainSource,
  onchainWalletBalance = 0,
  offchainWalletBalance = 0,
  onConfirm,
  onCancel,
  isConfirmDisabled = false,
  isLoading = false,
  feeEstimate,
  isEstimatingFee = false,
  feeEstimateError = null,
  feeEstimateUnavailableText = null,
  feeEstimateNote = null,
  feeEstimateWarning = null,
  sendError = null,
}) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const colors = useThemeColors();
  const isOnchainDestination =
    destinationType === "onchain" ||
    (destinationType === "bip321" && selectedPaymentMethod === "onchain");

  const getOnchainSourceLabel = (source: OnchainSendSource) =>
    source === "offchain" ? "Ark balance" : "Onchain wallet";

  const getOnchainSourceBalance = (source: OnchainSendSource) =>
    source === "offchain" ? offchainWalletBalance : onchainWalletBalance;

  const getPaymentMethodLabel = () => {
    if (destinationType === "bip321") {
      switch (selectedPaymentMethod) {
        case "ark":
          return "Ark";
        case "lightning":
          return "Lightning";
        case "offer":
          return "Offer";
        case "onchain":
        default:
          return "On-chain";
      }
    }

    switch (destinationType) {
      case "ark":
        return "Ark";
      case "lightning":
        return "Lightning";
      case "lnurl":
        return "Lightning Address";
      case "onchain":
        return "On-chain";
      case "offer":
        return "Offer";
      default:
        return "Bitcoin";
    }
  };

  const getDestinationDisplay = () => {
    if (destinationType === "bip321" && bip321Data) {
      if (selectedPaymentMethod === "ark" && bip321Data.arkAddress) {
        return bip321Data.arkAddress;
      }

      if (selectedPaymentMethod === "lightning" && bip321Data.lightningInvoice) {
        return bip321Data.lightningInvoice;
      }

      if (selectedPaymentMethod === "offer" && bip321Data.offer) {
        return bip321Data.offer;
      }

      if (selectedPaymentMethod === "onchain" && bip321Data.onchainAddress) {
        return bip321Data.onchainAddress;
      }
    }

    return destination;
  };

  const fiatAmount = btcPrice ? satsToFiat(amount, btcPrice, fiatCurrency) : null;
  const resolvedDestination = getDestinationDisplay();
  const title = isLoading ? "Sending payment" : sendError ? "Send failed" : "Confirm send";
  const description = isLoading
    ? "Keep Noah open while this completes."
    : sendError
      ? "Review the error, then retry or cancel."
      : "Review the route and fee before sending.";

  return (
    <View>
      <View className="items-center">
        <Text className="text-center text-2xl font-bold text-foreground">{title}</Text>
        <Text className="mt-1 max-w-[280px] text-center text-sm text-muted-foreground">
          {description}
        </Text>
      </View>

      <View className="mt-4 items-center">
        <Text className="text-center text-3xl font-bold text-foreground">
          {formatBitcoinAmount(amount)}
        </Text>
        {btcPrice ? (
          <Text className="mt-1 text-sm font-medium text-muted-foreground">
            ≈ {fiatAmount ? formatFiatAmount(fiatAmount, fiatCurrency) : null}
          </Text>
        ) : null}
      </View>

      <View
        className="mt-5 rounded-[20px] border px-4 py-4"
        style={{
          borderColor: `${colors.mutedForeground}22`,
          backgroundColor: `${colors.card}CC`,
        }}
      >
        <View className="flex-row items-center justify-between">
          <Text className="text-xs font-medium uppercase tracking-[2px] text-muted-foreground">
            Route
          </Text>
          {destinationType !== "bip321" ? (
            <Text className="text-sm font-semibold" style={{ color: COLORS.BITCOIN_ORANGE }}>
              {getPaymentMethodLabel()}
            </Text>
          ) : null}
        </View>

        {destinationType === "bip321" &&
        bip321Data &&
        selectedPaymentMethod &&
        onSelectPaymentMethod ? (
          <Bip321Picker
            bip321Data={bip321Data}
            selectedPaymentMethod={selectedPaymentMethod}
            onSelect={isLoading ? () => undefined : onSelectPaymentMethod}
            showSectionHeader={false}
            showSelectedDestination={false}
          />
        ) : (
          <View className="mt-3 h-px bg-border" />
        )}

        {isOnchainDestination && onchainSourceOptions.length > 0 ? (
          <>
            <View className="h-px bg-border" />
            <View className="py-3">
              <Text className="text-xs font-medium uppercase tracking-[2px] text-muted-foreground">
                Send from
              </Text>
              {onchainSourceOptions.length > 1 && onSelectOnchainSource ? (
                <View className="mt-3 flex-row gap-2">
                  {onchainSourceOptions.map((source) => {
                    const isSelected = selectedOnchainSource === source;
                    return (
                      <Pressable
                        key={source}
                        onPress={() => onSelectOnchainSource(source)}
                        disabled={isLoading}
                        className="flex-1 rounded-2xl border px-3 py-3"
                        style={{
                          borderColor: isSelected
                            ? COLORS.BITCOIN_ORANGE
                            : `${colors.mutedForeground}26`,
                          backgroundColor: isSelected
                            ? "rgba(201, 138, 60, 0.14)"
                            : `${colors.card}99`,
                          opacity: isLoading ? 0.65 : 1,
                        }}
                      >
                        <Text
                          className={`text-sm font-semibold ${
                            isSelected ? "text-foreground" : "text-muted-foreground"
                          }`}
                        >
                          {getOnchainSourceLabel(source)}
                        </Text>
                        <Text className="mt-1 text-xs text-muted-foreground">
                          {formatBitcoinAmount(getOnchainSourceBalance(source))}
                        </Text>
                      </Pressable>
                    );
                  })}
                </View>
              ) : selectedOnchainSource ? (
                <Text className="mt-1 text-sm font-semibold text-foreground">
                  {getOnchainSourceLabel(selectedOnchainSource)}
                </Text>
              ) : null}
            </View>
          </>
        ) : null}

        <View className="py-3">
          <Text className="text-xs font-medium uppercase tracking-[2px] text-muted-foreground">
            Destination
          </Text>
          <Text className="mt-1 text-sm leading-5 text-foreground">
            {truncateValue(resolvedDestination)}
          </Text>
        </View>

        {comment ? (
          <>
            <View className="h-px bg-border" />
            <View className="pt-3">
              <Text className="text-xs font-medium uppercase tracking-[2px] text-muted-foreground">
                Note
              </Text>
              <Text className="mt-1 text-sm leading-5 text-foreground" numberOfLines={2}>
                {comment}
              </Text>
            </View>
          </>
        ) : null}
      </View>

      <FeeEstimateSummary
        estimate={feeEstimate}
        isLoading={isEstimatingFee}
        error={
          feeEstimateUnavailableText ? new Error(feeEstimateUnavailableText) : feeEstimateError
        }
        unavailableText={feeEstimateUnavailableText ?? undefined}
        note={feeEstimateNote}
        compact
      />

      {feeEstimateWarning ? (
        <View className="mt-3 rounded-2xl border border-amber-500/40 bg-amber-500/10 px-4 py-3">
          <Text className="text-sm leading-5 text-amber-700 dark:text-amber-200">
            {feeEstimateWarning}
          </Text>
        </View>
      ) : null}

      {sendError ? (
        <View className="mt-3 rounded-2xl border border-destructive/35 bg-destructive/10 px-4 py-3">
          <Text className="text-sm font-semibold text-destructive">Payment did not send</Text>
          <Text className="mt-1 text-sm leading-5 text-destructive/90">{sendError}</Text>
        </View>
      ) : null}

      <View className="mt-5 flex-row gap-3">
        <Button
          onPress={onCancel}
          variant="outline"
          disabled={isLoading}
          className="flex-1 rounded-2xl py-3"
        >
          <Text className="font-semibold">Cancel</Text>
        </Button>
        <NoahButton
          onPress={onConfirm}
          isLoading={isLoading}
          disabled={isLoading || isConfirmDisabled}
          className="flex-1 rounded-2xl py-3"
        >
          Confirm & Send
        </NoahButton>
      </View>
    </View>
  );
};
