import React from "react";
import { Pressable, View } from "react-native";
import { Text } from "./ui/text";
import { NoahButton } from "./ui/NoahButton";
import SuccessAnimation from "./SuccessAnimation";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import { useCopyToClipboard } from "~/lib/clipboardUtils";
import { COLORS } from "~/lib/styleConstants";
import { useThemeColors } from "~/hooks/useTheme";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

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

  return `${value.slice(0, 14)}...${value.slice(-10)}`;
};

const CopyRow = ({ label, value, copyId }: { label: string; value: string; copyId: string }) => {
  const { copyWithState, isCopied } = useCopyToClipboard(1200);

  return (
    <Pressable
      onPress={() => copyWithState(value, copyId)}
      className="flex-row items-start justify-between py-4"
    >
      <View className="flex-1 pr-4">
        <Text className="text-sm font-medium uppercase tracking-[2px] text-muted-foreground">
          {label}
        </Text>
        <Text className="mt-2 text-sm leading-6 text-foreground">{truncateValue(value)}</Text>
      </View>
      <Text
        className="pt-1 text-xs font-semibold uppercase tracking-[2px]"
        style={{ color: isCopied(copyId) ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
      >
        {isCopied(copyId) ? "Copied" : "Copy"}
      </Text>
    </Pressable>
  );
};

export const SendSuccessBottomSheet: React.FC<SendSuccessBottomSheetProps> = ({
  parsedResult,
  handleDone,
  btcPrice,
  fiatCurrency,
}) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const fiatAmount = btcPrice ? satsToFiat(parsedResult.amount_sat, btcPrice, fiatCurrency) : null;
  const colors = useThemeColors();

  return (
    <View>
      <View className="items-center">
        <SuccessAnimation />
        <Text className="mt-4 text-center text-3xl font-bold text-foreground">Payment sent</Text>
      </View>

      <View className="mt-6 items-center">
        <Text className="text-center text-4xl font-bold text-foreground">
          {formatBitcoinAmount(parsedResult.amount_sat)}
        </Text>
        {btcPrice && (
          <Text className="mt-3 text-lg font-medium text-muted-foreground">
            ≈ {fiatAmount ? formatFiatAmount(fiatAmount, fiatCurrency) : null}
          </Text>
        )}
      </View>

      <View
        className="mt-8 rounded-[24px] border px-5 py-5"
        style={{
          borderColor: `${colors.mutedForeground}22`,
          backgroundColor: `${colors.card}CC`,
        }}
      >
        <View className="flex-row items-center justify-between">
          <Text className="text-sm font-medium uppercase tracking-[2px] text-muted-foreground">
            Payment route
          </Text>
          <Text className="text-sm font-semibold" style={{ color: COLORS.BITCOIN_ORANGE }}>
            {parsedResult.type}
          </Text>
        </View>

        <View className="mt-4 h-px bg-border" />

        <CopyRow label="Destination" value={parsedResult.destination} copyId="destination" />

        {parsedResult.txid ? (
          <>
            <View className="h-px bg-border" />
            <CopyRow label="Transaction ID" value={parsedResult.txid} copyId="txid" />
          </>
        ) : null}

        {parsedResult.preimage ? (
          <>
            <View className="h-px bg-border" />
            <CopyRow label="Preimage" value={parsedResult.preimage} copyId="preimage" />
          </>
        ) : null}
      </View>

      <View className="mt-8">
        <NoahButton onPress={handleDone} className="w-full rounded-2xl py-4">
          Done
        </NoahButton>
      </View>
    </View>
  );
};
