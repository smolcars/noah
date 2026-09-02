import Icon from "@react-native-vector-icons/ionicons";
import * as Haptics from "expo-haptics";
import React, { useEffect, useState } from "react";
import { Image, Pressable, View } from "react-native";
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withSequence,
  withTiming,
} from "react-native-reanimated";

import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useTheme } from "~/hooks/useTheme";
import { useCopyToClipboard } from "~/lib/clipboardUtils";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import { COLORS } from "~/lib/styleConstants";
import { NativeNoahButton } from "./ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "./ui/NativeNoahSecondaryButton";
import { Text } from "./ui/text";
import noahIcon from "../../assets/icon.png";

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

const PAPER_TRAVEL = 440;
const PAPER_COLOR = "#fffaf0";
const PAPER_INK = "#24211d";
const PAPER_MUTED_INK = "#746e64";
const PAPER_TEETH = Array.from({ length: 18 }, (_, index) => index);
const DARK_PRINTER_COLORS = {
  shell: "#292a2d",
  shellBorder: "#46474b",
  header: "rgba(255, 255, 255, 0.7)",
  headerMuted: "rgba(255, 255, 255, 0.6)",
  screen: "#151618",
  screenBorder: "#343539",
  screenForeground: "#ffffff",
  screenMuted: "rgba(255, 255, 255, 0.45)",
  screenStatus: "rgba(255, 255, 255, 0.7)",
  divider: "rgba(255, 255, 255, 0.1)",
  slot: "#0d0e10",
  slotBorder: "#45464b",
} as const;
const LIGHT_PRINTER_COLORS = {
  shell: "#e1e7ee",
  shellBorder: "#bec9d4",
  header: "#465263",
  headerMuted: "#657284",
  screen: "#f8fafc",
  screenBorder: "#c4ced9",
  screenForeground: "#182332",
  screenMuted: "#657284",
  screenStatus: "#465263",
  divider: "#d7dfe8",
  slot: "#272e37",
  slotBorder: "#66717d",
} as const;

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

const ReceiptDivider = () => (
  <View className="my-5 border-t" style={{ borderColor: "#c9c1b4", borderStyle: "dashed" }} />
);

const ReceiptCopyRow = ({
  label,
  value,
  copyId,
}: {
  label: string;
  value: string;
  copyId: string;
}) => {
  const { copyWithState, isCopied } = useCopyToClipboard(1200);
  const copied = isCopied(copyId);
  const displayedValue = truncateValue(value);

  return (
    <Pressable
      accessibilityHint="Copies the full receipt detail to the clipboard"
      accessibilityLabel={`Copy ${label}: ${displayedValue}`}
      accessibilityRole="button"
      onPress={() => copyWithState(value, copyId)}
      className="flex-row items-start justify-between gap-3 py-2"
    >
      <View className="min-w-0 flex-1">
        <Text
          className="font-mono text-[10px] uppercase tracking-[1.8px]"
          style={{ color: PAPER_MUTED_INK }}
        >
          {label}
        </Text>
        <Text className="mt-1 font-mono text-xs leading-5" style={{ color: PAPER_INK }}>
          {displayedValue}
        </Text>
      </View>
      <Text
        className="pt-1 font-mono text-[10px] font-bold uppercase tracking-[1.4px]"
        style={{ color: copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
      >
        {copied ? "Copied" : "Copy"}
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
  const { isDark } = useTheme();
  const shouldReduceMotion = useReducedMotion();
  const [completedAt] = useState(() => new Date());
  const [showReceipt, setShowReceipt] = useState(false);
  const paperOffset = useSharedValue(shouldReduceMotion ? 0 : -PAPER_TRAVEL);
  const paperOpacity = useSharedValue(shouldReduceMotion ? 1 : 0);
  const fiatAmount = btcPrice ? satsToFiat(parsedResult.amount_sat, btcPrice, fiatCurrency) : null;
  const proofLabel = parsedResult.txid ? "Transaction ID" : "Payment preimage";
  const proofValue = parsedResult.txid ?? parsedResult.preimage;
  const referenceSource = proofValue ?? parsedResult.destination;
  const reference = referenceSource.slice(-12).toUpperCase();
  const printerColors = isDark ? DARK_PRINTER_COLORS : LIGHT_PRINTER_COLORS;

  useEffect(() => {
    const hapticTimer = setTimeout(
      () => {
        void Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      },
      shouldReduceMotion ? 0 : 220,
    );

    if (shouldReduceMotion) {
      paperOffset.value = 0;
      paperOpacity.value = 1;
    } else {
      paperOpacity.value = withDelay(180, withTiming(1, { duration: 100 }));
      paperOffset.value = withDelay(
        180,
        withSequence(
          withTiming(-360, { duration: 120, easing: Easing.linear }),
          withDelay(45, withTiming(-285, { duration: 150, easing: Easing.linear })),
          withDelay(45, withTiming(-210, { duration: 150, easing: Easing.linear })),
          withDelay(45, withTiming(-135, { duration: 150, easing: Easing.linear })),
          withDelay(45, withTiming(-65, { duration: 150, easing: Easing.linear })),
          withDelay(45, withTiming(0, { duration: 180, easing: Easing.out(Easing.cubic) })),
        ),
      );
    }

    return () => clearTimeout(hapticTimer);
  }, [paperOffset, paperOpacity, shouldReduceMotion]);

  const paperAnimatedStyle = useAnimatedStyle(() => ({
    opacity: paperOpacity.value,
    transform: [{ translateY: paperOffset.value }],
  }));

  if (!showReceipt) {
    return (
      <View className="pb-2" testID="send-success-sheet">
        <View className="items-center px-5 pt-8">
          <View className="size-20 items-center justify-center rounded-full bg-success/15">
            <Icon name="checkmark" size={48} color={COLORS.SUCCESS} />
          </View>
          <Text className="mt-7 text-center text-4xl font-bold text-foreground">Payment sent</Text>
          <Text className="mt-3 text-center text-3xl font-semibold text-foreground">
            {formatBitcoinAmount(parsedResult.amount_sat)}
          </Text>
          {fiatAmount ? (
            <Text className="mt-2 text-base text-muted-foreground">
              ≈ {formatFiatAmount(fiatAmount, fiatCurrency)}
            </Text>
          ) : null}

          <View className="mt-8 w-full border-y border-border/70 py-4">
            <View className="flex-row items-center justify-between gap-5">
              <Text className="text-base text-muted-foreground">Paid via</Text>
              <Text className="text-base font-semibold text-foreground">{parsedResult.type}</Text>
            </View>
            <View className="mt-4 flex-row items-start justify-between gap-5">
              <Text className="text-base text-muted-foreground">To</Text>
              <Text className="min-w-0 flex-1 text-right text-base font-semibold text-foreground">
                {truncateValue(parsedResult.destination)}
              </Text>
            </View>
          </View>
        </View>

        <View className="mt-10 gap-3 px-1">
          <NativeNoahSecondaryButton
            label="View receipt"
            onPress={() => setShowReceipt(true)}
            size="lg"
            fullWidth
            testID="send-success-view-receipt"
          />
          <NativeNoahButton
            label="Done"
            onPress={handleDone}
            size="lg"
            fullWidth
            testID="send-success-done"
          />
        </View>
      </View>
    );
  }

  return (
    <View className="pb-2" testID="send-success-receipt">
      <View className="mb-4 px-1">
        <NativeNoahSecondaryButton
          label="Back to summary"
          onPress={() => setShowReceipt(false)}
          fullWidth
          testID="send-success-back-to-summary"
        />
      </View>
      <View className="items-center px-1">
        <View
          className="z-20 w-full rounded-[30px] border px-4 pt-4 pb-12"
          style={{
            backgroundColor: printerColors.shell,
            borderColor: printerColors.shellBorder,
            shadowColor: "#000000",
            shadowOffset: { width: 0, height: 16 },
            shadowOpacity: isDark ? 0.28 : 0.16,
            shadowRadius: 22,
            elevation: 12,
          }}
        >
          <View className="mb-4 flex-row items-center justify-between px-1">
            <View className="flex-row items-center gap-2">
              <Image
                accessibilityIgnoresInvertColors
                accessible={false}
                className="size-7 rounded-lg"
                resizeMode="cover"
                source={noahIcon}
              />
              <Text
                className="text-xs font-semibold uppercase tracking-[2.5px]"
                style={{ color: printerColors.header }}
              >
                Noah receipt
              </Text>
            </View>
            <View className="flex-row items-center gap-1.5">
              <View className="size-2 rounded-full" style={{ backgroundColor: COLORS.SUCCESS }} />
              <Text
                className="text-[10px] font-semibold uppercase tracking-[1.8px]"
                style={{ color: printerColors.headerMuted }}
              >
                Complete
              </Text>
            </View>
          </View>

          <View
            className="rounded-[21px] border px-4 py-4"
            style={{
              backgroundColor: printerColors.screen,
              borderColor: printerColors.screenBorder,
            }}
          >
            <View className="flex-row items-start justify-between gap-4">
              <View className="min-w-0 flex-1">
                <Text
                  className="text-xs font-medium uppercase tracking-[1.8px]"
                  style={{ color: printerColors.screenMuted }}
                >
                  Sent via
                </Text>
                <Text
                  className="mt-1 text-base font-semibold"
                  numberOfLines={1}
                  style={{ color: printerColors.screenForeground }}
                >
                  {parsedResult.type}
                </Text>
              </View>
              <View className="items-end">
                <Text
                  className="text-xs font-medium uppercase tracking-[1.8px]"
                  style={{ color: printerColors.screenMuted }}
                >
                  Total
                </Text>
                <Text
                  className="mt-1 text-lg font-bold"
                  style={{ color: printerColors.screenForeground }}
                >
                  {formatBitcoinAmount(parsedResult.amount_sat)}
                </Text>
              </View>
            </View>

            <View className="my-4 h-px" style={{ backgroundColor: printerColors.divider }} />

            <View className="flex-row items-center gap-2.5">
              <Icon name="checkmark-circle" size={20} color={COLORS.SUCCESS} />
              <Text className="text-sm font-medium" style={{ color: printerColors.screenStatus }}>
                Payment settled
              </Text>
            </View>
          </View>
        </View>

        <View
          accessibilityElementsHidden
          className="-mt-7 z-40 h-[14px] w-[88%] self-center rounded-full border"
          importantForAccessibility="no-hide-descendants"
          style={{
            backgroundColor: printerColors.slot,
            borderColor: printerColors.slotBorder,
            shadowColor: "#000000",
            shadowOffset: { width: 0, height: 2 },
            shadowOpacity: 0.7,
            shadowRadius: 4,
            elevation: 14,
          }}
        />

        <View className="-mt-1 z-30 w-full items-center overflow-hidden pb-2">
          <Animated.View
            className="w-[80%] px-5 pt-7 pb-9"
            style={[
              {
                backgroundColor: PAPER_COLOR,
                shadowColor: "#000000",
                shadowOffset: { width: 0, height: 12 },
                shadowOpacity: 0.22,
                shadowRadius: 18,
                elevation: 9,
              },
              paperAnimatedStyle,
            ]}
          >
            <View className="items-center">
              <Image
                accessibilityIgnoresInvertColors
                accessible={false}
                className="size-12 rounded-[14px]"
                resizeMode="cover"
                source={noahIcon}
              />
              <Text
                className="mt-3 font-mono text-sm font-bold uppercase tracking-[3px]"
                style={{ color: PAPER_INK }}
              >
                Payment receipt
              </Text>
              <Text className="mt-1 font-mono text-[10px]" style={{ color: PAPER_MUTED_INK }}>
                {formatCompletedAt(completedAt)}
              </Text>
            </View>

            <ReceiptDivider />

            <View className="flex-row items-end justify-between gap-4">
              <View>
                <Text
                  className="font-mono text-[10px] font-bold uppercase tracking-[2px]"
                  style={{ color: PAPER_MUTED_INK }}
                >
                  Total paid
                </Text>
                {fiatAmount ? (
                  <Text className="mt-1 font-mono text-xs" style={{ color: PAPER_MUTED_INK }}>
                    ≈ {formatFiatAmount(fiatAmount, fiatCurrency)}
                  </Text>
                ) : null}
              </View>
              <Text className="font-mono text-xl font-bold" style={{ color: PAPER_INK }}>
                {formatBitcoinAmount(parsedResult.amount_sat)}
              </Text>
            </View>

            <ReceiptDivider />

            <View className="flex-row items-center justify-between gap-4">
              <Text className="font-mono text-xs" style={{ color: PAPER_MUTED_INK }}>
                Payment route
              </Text>
              <Text className="font-mono text-xs font-bold" style={{ color: PAPER_INK }}>
                {parsedResult.type}
              </Text>
            </View>

            <View className="mt-3">
              <ReceiptCopyRow
                label="Paid to"
                value={parsedResult.destination}
                copyId="receipt-destination"
              />
              {proofValue ? (
                <ReceiptCopyRow label={proofLabel} value={proofValue} copyId="receipt-proof" />
              ) : null}
            </View>

            <ReceiptDivider />

            <View className="items-center">
              <Text
                className="font-mono text-[10px] uppercase tracking-[2px]"
                style={{ color: PAPER_MUTED_INK }}
              >
                Reference
              </Text>
              <Text
                className="mt-1 font-mono text-xs font-bold uppercase tracking-[2.5px]"
                style={{ color: PAPER_INK }}
              >
                {reference}
              </Text>
              <Text className="mt-4 font-mono text-[10px]" style={{ color: PAPER_MUTED_INK }}>
                Thanks for sailing with Noah.
              </Text>
            </View>

            <View
              accessibilityElementsHidden
              className="absolute right-0 -bottom-2 left-0 flex-row justify-around px-1"
              importantForAccessibility="no-hide-descendants"
            >
              {PAPER_TEETH.map((tooth) => (
                <View
                  key={tooth}
                  className="size-4 rotate-45"
                  style={{ backgroundColor: PAPER_COLOR }}
                />
              ))}
            </View>
          </Animated.View>
        </View>
      </View>

      <View className="mt-4 px-1">
        <NativeNoahButton label="Done" onPress={handleDone} fullWidth />
      </View>
    </View>
  );
};
