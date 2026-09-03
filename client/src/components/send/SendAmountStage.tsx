import Icon from "@react-native-vector-icons/ionicons";
import * as Haptics from "expo-haptics";
import { useState } from "react";
import { Pressable, View } from "react-native";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import Animated, { FadeIn, FadeOut, useReducedMotion } from "react-native-reanimated";

import { AmountKeypad } from "~/components/AmountKeypad";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter, useBitcoinAmountUnit } from "~/hooks/useBitcoinAmountFormatter";
import { useThemeColors } from "~/hooks/useTheme";
import { formatFiatAmount, getFiatCurrencyInfo, satsToFiat } from "~/lib/fiatCurrency";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { COLORS } from "~/lib/styleConstants";
import { formatNumber } from "~/lib/utils";

type SendAmountStageProps = {
  amount: string;
  amountSat: number;
  currency: "FIAT" | "SATS";
  fiatCurrency: FiatCurrencyCode;
  btcPrice?: number;
  arkBalanceSat: number;
  onchainBalanceSat: number;
  error: string | null;
  isAmountEditable: boolean;
  canSendMax: boolean;
  canClear: boolean;
  recipient: string | null;
  recipientLabel: string | null;
  onBack?: () => void;
  onAmountChange: (amount: string) => void;
  onToggleCurrency: () => void;
  onContinue: () => void;
  onClear: () => void;
  onEditRecipient: () => void;
  onMax: () => void;
  onPaste: () => void;
  onScan: () => void;
};

export function SendAmountStage({
  amount,
  amountSat,
  currency,
  fiatCurrency,
  btcPrice,
  arkBalanceSat,
  onchainBalanceSat,
  error,
  isAmountEditable,
  canSendMax,
  canClear,
  recipient,
  recipientLabel,
  onBack,
  onAmountChange,
  onToggleCurrency,
  onContinue,
  onClear,
  onEditRecipient,
  onMax,
  onPaste,
  onScan,
}: SendAmountStageProps) {
  const colors = useThemeColors();
  const shouldReduceMotion = useReducedMotion();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const bitcoinAmountUnit = useBitcoinAmountUnit();
  const bottomTabBarHeight = useBottomTabBarHeight();
  const fiatCurrencyInfo = getFiatCurrencyInfo(fiatCurrency);
  const [showBalances, setShowBalances] = useState(false);
  const displayAmount = amount.length === 0 ? "0" : formatNumber(amount);
  const amountPrefix =
    currency === "FIAT" ? fiatCurrencyInfo.symbol : bitcoinAmountUnit === "bip177" ? "₿" : null;
  const primaryAmount = amountPrefix ? `${amountPrefix}${displayAmount}` : displayAmount;
  const primaryAmountFontSize =
    primaryAmount.length <= 7 ? 64 : primaryAmount.length <= 10 ? 50 : 38;
  const amountSuffix = currency === "SATS" && bitcoinAmountUnit === "sats" ? "sats" : null;
  const convertedAmount =
    currency === "SATS"
      ? btcPrice
        ? formatFiatAmount(satsToFiat(amountSat, btcPrice, fiatCurrency), fiatCurrency)
        : `${fiatCurrencyInfo.code} rate unavailable`
      : formatBitcoinAmount(amountSat);
  const canContinue = Number.isInteger(amountSat) && amountSat > 0;
  const handleClear = () => {
    void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    onClear();
  };

  return (
    <View className="flex-1 px-5" testID="send-amount-stage">
      <View className="flex-row items-center justify-between pt-4">
        {onBack ? (
          <NativeNoahBackButton onPress={onBack} testID="send-amount-back" />
        ) : (
          <View className="w-12" />
        )}
        <View className="items-center">
          <Text accessibilityRole="header" className="text-xl font-bold text-foreground">
            Send bitcoin
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`Show wallet balances, ${formatBitcoinAmount(arkBalanceSat)} available`}
            onPress={() => setShowBalances((visible) => !visible)}
            className="mt-1 px-3 py-1"
            testID="send-balance-details"
          >
            <Text className="text-sm text-muted-foreground">
              {formatBitcoinAmount(arkBalanceSat)} available
            </Text>
          </Pressable>
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Scan payment request"
          onPress={onScan}
          className="h-12 w-12 items-center justify-center rounded-full border border-border bg-card"
          testID="send-scan"
        >
          <Icon name="scan" size={23} color={colors.foreground} />
        </Pressable>
      </View>

      {showBalances ? (
        <View className="mt-4 border-y border-border/60 py-3" testID="send-balance-breakdown">
          <View className="flex-row items-center justify-between">
            <Text className="text-sm text-muted-foreground">Ark balance</Text>
            <Text className="text-sm font-semibold text-foreground">
              {formatBitcoinAmount(arkBalanceSat)}
            </Text>
          </View>
          <View className="mt-2 flex-row items-center justify-between">
            <Text className="text-sm text-muted-foreground">On-chain wallet</Text>
            <Text className="text-sm font-semibold text-foreground">
              {formatBitcoinAmount(onchainBalanceSat)}
            </Text>
          </View>
        </View>
      ) : null}

      <View className="flex-1 items-center justify-center py-3">
        <View
          accessibilityRole="text"
          accessibilityLabel={`${amount.length === 0 ? "0" : amount} ${
            currency === "SATS" ? "sats" : fiatCurrency
          }`}
          className="flex-row items-baseline justify-center px-2"
        >
          <Text
            className="text-center font-bold text-foreground"
            numberOfLines={1}
            style={{
              maxWidth: amountSuffix ? 280 : 340,
              fontSize: primaryAmountFontSize,
              lineHeight: primaryAmountFontSize + 8,
            }}
            testID="send-amount-value"
          >
            {primaryAmount}
          </Text>
          {amountSuffix ? (
            <Text className="ml-2 text-xl font-semibold text-muted-foreground">{amountSuffix}</Text>
          ) : null}
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Switch to ${currency === "SATS" ? fiatCurrency : "sats"}`}
          accessibilityState={{ disabled: !btcPrice }}
          disabled={!btcPrice}
          onPress={onToggleCurrency}
          className="mt-2 flex-row items-center gap-2 px-4 py-2"
          testID="send-currency-toggle"
        >
          <Text
            className="text-xl font-semibold leading-7"
            style={{ color: btcPrice ? COLORS.SUCCESS : colors.mutedForeground }}
          >
            {convertedAmount}
          </Text>
          <Icon
            name="swap-vertical"
            size={21}
            color={btcPrice ? COLORS.SUCCESS : colors.mutedForeground}
          />
        </Pressable>

        {recipient && recipientLabel ? (
          <Animated.View
            entering={FadeIn.duration(shouldReduceMotion ? 100 : 180)}
            exiting={FadeOut.duration(shouldReduceMotion ? 80 : 120)}
            className="mt-3 w-full max-w-[370px] flex-row items-center rounded-2xl border border-border/70 bg-card px-4 py-3"
            testID="send-amount-recipient"
          >
            <View className="min-w-0 flex-1">
              <Text className="text-xs font-medium uppercase tracking-[1.2px] text-muted-foreground">
                To · {recipientLabel}
              </Text>
              <Text
                className="mt-1 text-sm font-semibold text-foreground"
                ellipsizeMode="middle"
                numberOfLines={1}
                testID="send-amount-recipient-value"
              >
                {recipient}
              </Text>
            </View>
            <View className="ml-3 flex-row items-center gap-1">
              {canSendMax ? (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="Send maximum amount"
                  onPress={onMax}
                  className="h-10 items-center justify-center rounded-full px-3"
                  testID="send-max"
                  style={{ backgroundColor: `${COLORS.BITCOIN_ORANGE}14` }}
                >
                  <Text className="text-xs font-bold tracking-[1.2px] text-foreground">MAX</Text>
                </Pressable>
              ) : null}
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Change recipient"
                onPress={onEditRecipient}
                className="h-10 items-center justify-center rounded-full px-3"
                testID="send-amount-change-recipient"
              >
                <Text className="text-sm font-semibold" style={{ color: COLORS.BITCOIN_ORANGE }}>
                  Change
                </Text>
              </Pressable>
            </View>
          </Animated.View>
        ) : (
          <Animated.View
            entering={FadeIn.duration(shouldReduceMotion ? 100 : 180)}
            exiting={FadeOut.duration(shouldReduceMotion ? 80 : 120)}
            className="mt-3 flex-row items-center gap-3"
          >
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Send maximum amount"
              onPress={onMax}
              disabled={!canSendMax}
              accessibilityState={{ disabled: !canSendMax }}
              className="h-12 min-w-[88px] items-center justify-center rounded-full border px-6"
              testID="send-max"
              style={{
                borderColor: `${COLORS.BITCOIN_ORANGE}88`,
                opacity: canSendMax ? 1 : 0.45,
              }}
            >
              <Text className="text-sm font-bold tracking-[1.5px] text-foreground">MAX</Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Paste payment request"
              onPress={onPaste}
              className="h-12 min-w-[88px] items-center justify-center rounded-full border border-border px-6"
              testID="send-paste-from-amount"
            >
              <Text className="text-sm font-semibold text-foreground">Paste</Text>
            </Pressable>
          </Animated.View>
        )}

        {error ? (
          <Text className="mt-3 text-center text-sm text-destructive" testID="send-amount-error">
            {error}
          </Text>
        ) : null}
      </View>

      {isAmountEditable ? (
        <AmountKeypad
          amount={amount}
          currency={currency}
          fiatDecimals={fiatCurrencyInfo.decimals}
          onChange={onAmountChange}
          testIDPrefix="send-key"
        />
      ) : (
        <View className="mb-6 items-center border-y border-border/60 py-5">
          <Text className="text-sm font-semibold text-foreground">
            Amount set by payment request
          </Text>
          <Text className="mt-1 text-sm text-muted-foreground">This amount cannot be edited.</Text>
        </View>
      )}

      <View style={{ paddingBottom: Math.max(bottomTabBarHeight, 20) + 8 }}>
        <View className="flex-row gap-3">
          <View className="flex-1">
            <NativeNoahSecondaryButton
              label="Clear"
              onPress={handleClear}
              disabled={!canClear}
              size="lg"
              tone="neutral"
              fullWidth
              testID="send-amount-clear"
            />
          </View>
          <View className="flex-[2]">
            <NativeNoahButton
              label="Next"
              onPress={onContinue}
              disabled={!canContinue}
              size="lg"
              fullWidth
              testID="send-amount-next"
            />
          </View>
        </View>
      </View>
    </View>
  );
}
