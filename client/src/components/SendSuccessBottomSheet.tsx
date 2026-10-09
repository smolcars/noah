import { T, useGT, useLocale } from "gt-react-native";
import Icon from "@react-native-vector-icons/ionicons";
import * as Haptics from "expo-haptics";
import React, { useEffect, useState } from "react";
import { Pressable, View } from "react-native";

import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useCopyToClipboard } from "~/lib/clipboardUtils";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import { COLORS } from "~/lib/styleConstants";
import { NativeNoahButton } from "./ui/NativeNoahButton";
import { Text } from "./ui/text";

type ParsedResult = {
  amount_sat: number;
  destination: string;
  txid?: string;
  preimage?: string;
  success: boolean;
  type: string;
};

type SendSuccessBottomSheetProps = {
  parsedResult: ParsedResult;
  handleDone: () => void;
  btcPrice?: number;
  fiatCurrency: FiatCurrencyCode;
};

const truncateValue = (value: string) => {
  if (value.length <= 32) {
    return value;
  }

  return `${value.slice(0, 14)}…${value.slice(-10)}`;
};

const formatCompletedAt = (date: Date) => {
  const day = date.toLocaleDateString(undefined, {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
  const time = date.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
  });

  return `${day} · ${time}`;
};

const DetailRow = ({ label, value }: { label: string; value: string }) => (
  <View className="flex-row items-start justify-between gap-5 border-b border-border/60 py-4">
    <Text className="text-base text-muted-foreground">{label}</Text>
    <Text className="min-w-0 flex-1 text-right text-base font-semibold text-foreground">
      {value}
    </Text>
  </View>
);

const CopyDetailRow = ({
  label,
  value,
  copyId,
  testID,
}: {
  label: string;
  value: string;
  copyId: string;
  testID: string;
}) => {
  const gt = useGT();
  const { copyWithState, isCopied } = useCopyToClipboard(3000);
  const copied = isCopied(copyId);
  const displayedValue = truncateValue(value);

  return (
    <Pressable
      accessibilityHint={gt("Copies the full payment detail to the clipboard")}
      accessibilityLabel={
        copied
          ? gt("{value1} copied", { value1: label })
          : gt("Copy {value1}: {value2}", { value1: label, value2: displayedValue })
      }
      accessibilityRole="button"
      className="flex-row items-start justify-between gap-5 border-b border-border/60 py-4"
      onPress={() => copyWithState(value, copyId)}
      testID={testID}
    >
      <Text className="text-base text-muted-foreground">{label}</Text>
      <View className="min-w-0 flex-1 items-end">
        <Text className="text-right text-base font-semibold text-foreground">{displayedValue}</Text>
        <Text
          className="mt-1 text-xs font-semibold uppercase tracking-[1.4px]"
          style={{ color: copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
        >
          {copied ? gt("Copied") : gt("Copy")}
        </Text>
      </View>
    </Pressable>
  );
};

export const SendSuccessBottomSheet: React.FC<SendSuccessBottomSheetProps> = ({
  parsedResult,
  handleDone,
  btcPrice,
  fiatCurrency,
}) => {
  const locale = useLocale();
  const gt = useGT();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const [completedAt] = useState(() => new Date());
  const fiatAmount = btcPrice ? satsToFiat(parsedResult.amount_sat, btcPrice, fiatCurrency) : null;
  const proofLabel = parsedResult.txid ? gt("Transaction ID") : gt("Payment preimage");
  const proofValue = parsedResult.txid ?? parsedResult.preimage;

  useEffect(() => {
    void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
  }, []);

  return (
    <View className="pb-2" testID="send-success-sheet">
      <View className="items-center px-5 pt-8">
        <View className="size-20 items-center justify-center rounded-full bg-success/15">
          <Icon name="checkmark" size={48} color={COLORS.SUCCESS} />
        </View>
        <T>
          <Text className="mt-7 text-center text-4xl font-bold text-foreground">Payment sent</Text>
        </T>
        <Text className="mt-3 text-center text-3xl font-semibold text-foreground">
          {formatBitcoinAmount(parsedResult.amount_sat)}
        </Text>
        {fiatAmount ? (
          <Text className="mt-2 text-base text-muted-foreground">
            ≈ {formatFiatAmount(fiatAmount, fiatCurrency, locale)}
          </Text>
        ) : null}

        <View className="mt-8 w-full border-t border-border/60">
          <DetailRow label={gt("Paid via")} value={parsedResult.type} />
          <CopyDetailRow
            label={gt("To")}
            value={parsedResult.destination}
            copyId="success-destination"
            testID="send-success-copy-destination"
          />
          {proofValue ? (
            <CopyDetailRow
              label={proofLabel}
              value={proofValue}
              copyId="success-proof"
              testID="send-success-copy-proof"
            />
          ) : null}
          <DetailRow label={gt("Completed")} value={formatCompletedAt(completedAt)} />
        </View>
      </View>

      <View className="mt-8 px-1">
        <NativeNoahButton
          label={gt("Done")}
          onPress={handleDone}
          size="lg"
          fullWidth
          testID="send-success-done"
        />
      </View>
    </View>
  );
};
