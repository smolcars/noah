import Icon from "@react-native-vector-icons/ionicons";
import { useEffect, useState } from "react";
import { Pressable, TextInput, View } from "react-native";

import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter, useBitcoinAmountUnit } from "~/hooks/useBitcoinAmountFormatter";
import { useReceiveScreen } from "~/hooks/useReceiveScreen";
import { useThemeColors } from "~/hooks/useTheme";
import { formatFiatAmount, getFiatCurrencyInfo, satsToFiat } from "~/lib/fiatCurrency";
import {
  getInvoiceDescriptionLength,
  isInvoiceDescriptionValid,
  MAX_INVOICE_DESCRIPTION_LENGTH,
} from "~/lib/lightningInvoice";
import { COLORS } from "~/lib/styleConstants";
import { formatNumber } from "~/lib/utils";

type ReceiveAmountBottomSheetProps = {
  initialAmountSat: number | null;
  initialDescription: string;
  isOpen: boolean;
  isSubmitting: boolean;
  onClose: () => void;
  onRemove: () => void;
  onSubmit: (request: { amountSat: number; description: string }) => void;
};

type KeypadKey = "backspace" | "." | `${number}`;

const KEYPAD_ROWS: KeypadKey[][] = [
  ["1", "2", "3"],
  ["4", "5", "6"],
  ["7", "8", "9"],
  [".", "0", "backspace"],
];

const MAX_AMOUNT_LENGTH = 12;

export function ReceiveAmountBottomSheet({
  initialAmountSat,
  initialDescription,
  isOpen,
  isSubmitting,
  onClose,
  onRemove,
  onSubmit,
}: ReceiveAmountBottomSheetProps) {
  const colors = useThemeColors();
  const bitcoinAmountUnit = useBitcoinAmountUnit();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const {
    amount,
    amountSat,
    btcPrice,
    currency,
    fiatCurrency,
    setAmount,
    setCurrency,
    toggleCurrency,
  } = useReceiveScreen();
  const fiatCurrencyInfo = getFiatCurrencyInfo(fiatCurrency);
  const [description, setDescription] = useState("");
  const [isNoteVisible, setIsNoteVisible] = useState(false);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    setCurrency("SATS");
    setAmount(initialAmountSat?.toString() ?? "");
    setDescription(initialDescription);
    setIsNoteVisible(initialDescription.length > 0);
  }, [initialAmountSat, initialDescription, isOpen, setAmount, setCurrency]);

  const descriptionLength = getInvoiceDescriptionLength(description);
  const isDescriptionValid = isInvoiceDescriptionValid(description);
  const canSubmit = Number.isInteger(amountSat) && amountSat > 0 && isDescriptionValid;
  const displayAmount = amount.length === 0 ? "0" : formatNumber(amount);
  const amountPrefix =
    currency === "FIAT" ? fiatCurrencyInfo.symbol : bitcoinAmountUnit === "bip177" ? "₿" : null;
  const amountSuffix = currency === "SATS" && bitcoinAmountUnit === "sats" ? "sats" : null;
  const convertedAmount =
    currency === "SATS"
      ? btcPrice
        ? formatFiatAmount(satsToFiat(amountSat, btcPrice, fiatCurrency), fiatCurrency)
        : `${fiatCurrencyInfo.code} rate unavailable`
      : formatBitcoinAmount(amountSat);

  const close = () => {
    if (!isSubmitting) {
      onClose();
    }
  };

  const enterKey = (key: KeypadKey) => {
    if (isSubmitting) {
      return;
    }

    if (key === "backspace") {
      setAmount(amount.slice(0, -1));
      return;
    }

    if (key === ".") {
      if (currency !== "FIAT" || fiatCurrencyInfo.decimals === 0 || amount.includes(".")) {
        return;
      }

      setAmount(amount.length === 0 ? "0." : `${amount}.`);
      return;
    }

    if (amount.length >= MAX_AMOUNT_LENGTH) {
      return;
    }

    const decimalPlaces = amount.split(".")[1]?.length ?? 0;
    if (currency === "FIAT" && amount.includes(".") && decimalPlaces >= fiatCurrencyInfo.decimals) {
      return;
    }

    setAmount(amount === "0" ? key : `${amount}${key}`);
  };

  const submit = () => {
    if (!canSubmit || isSubmitting) {
      return;
    }

    onSubmit({ amountSat, description: description.trim() });
  };

  return (
    <AppBottomSheet isOpen={isOpen} onClose={close} avoidKeyboard>
      <View className="flex-1 pb-4">
        <View className="flex-row items-center justify-between">
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Close amount entry"
            accessibilityState={{ disabled: isSubmitting }}
            disabled={isSubmitting}
            onPress={close}
            className="h-12 w-12 items-center justify-center rounded-full border border-border"
          >
            <Icon name="close" size={24} color={colors.foreground} />
          </Pressable>

          {isNoteVisible ? (
            <Text className="text-sm font-semibold text-muted-foreground">Lightning note</Text>
          ) : (
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Add a Lightning note"
              onPress={() => setIsNoteVisible(true)}
              className="rounded-full border border-border bg-card px-5 py-3"
            >
              <Text className="font-semibold text-foreground">Add a note</Text>
            </Pressable>
          )}

          <View className="h-12 w-12" />
        </View>

        {isNoteVisible ? (
          <View className="mt-4">
            <TextInput
              accessibilityLabel="Lightning note"
              autoFocus={initialDescription.length === 0}
              className="rounded-2xl border border-border bg-card px-4 py-4 text-base text-foreground"
              editable={!isSubmitting}
              maxLength={MAX_INVOICE_DESCRIPTION_LENGTH}
              onChangeText={setDescription}
              placeholder="What is this payment for?"
              placeholderTextColor={colors.mutedForeground}
              returnKeyType="done"
              value={description}
            />
            <Text
              className={`mt-2 text-right text-xs ${
                isDescriptionValid ? "text-muted-foreground" : "text-destructive"
              }`}
            >
              {descriptionLength}/{MAX_INVOICE_DESCRIPTION_LENGTH}
            </Text>
          </View>
        ) : null}

        <View className="flex-1 items-center justify-center py-5">
          <View
            accessibilityRole="text"
            accessibilityLabel={`${amount.length === 0 ? "0" : amount} ${
              currency === "SATS" ? "sats" : fiatCurrency
            }`}
            className="flex-row items-baseline justify-center px-4"
          >
            {amountPrefix ? (
              <Text className="mr-2 text-[56px] font-bold leading-[64px] text-foreground">
                {amountPrefix}
              </Text>
            ) : null}
            <Text
              adjustsFontSizeToFit
              className="max-w-[260px] text-[56px] font-bold leading-[64px] text-foreground"
              minimumFontScale={0.55}
              numberOfLines={1}
            >
              {displayAmount}
            </Text>
            {amountSuffix ? (
              <Text className="ml-2 text-xl font-semibold text-muted-foreground">
                {amountSuffix}
              </Text>
            ) : null}
          </View>

          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`Switch to ${currency === "SATS" ? fiatCurrency : "sats"}`}
            accessibilityState={{ disabled: !btcPrice || isSubmitting }}
            disabled={!btcPrice || isSubmitting}
            onPress={toggleCurrency}
            className="mt-3 flex-row items-center gap-2 px-4 py-2"
          >
            <Text
              className="text-base font-semibold"
              style={{ color: btcPrice ? COLORS.SUCCESS : colors.mutedForeground }}
            >
              {convertedAmount}
            </Text>
            <Icon
              name="swap-vertical"
              size={18}
              color={btcPrice ? COLORS.SUCCESS : colors.mutedForeground}
            />
          </Pressable>
        </View>

        <View accessibilityLabel="Amount keypad" className="mb-5">
          {KEYPAD_ROWS.map((row, rowIndex) => (
            <View key={rowIndex} className="flex-row">
              {row.map((key) => {
                const isDecimalDisabled =
                  key === "." && (currency !== "FIAT" || fiatCurrencyInfo.decimals === 0);

                return (
                  <Pressable
                    key={key}
                    accessibilityRole="button"
                    accessibilityLabel={key === "backspace" ? "Delete digit" : key}
                    accessibilityState={{ disabled: isDecimalDisabled || isSubmitting }}
                    disabled={isDecimalDisabled || isSubmitting}
                    onPress={() => enterKey(key)}
                    className="h-[66px] flex-1 items-center justify-center"
                    testID={`receive-key-${key}`}
                  >
                    {key === "backspace" ? (
                      <Icon name="backspace-outline" size={27} color={colors.foreground} />
                    ) : isDecimalDisabled ? null : (
                      <Text className="text-3xl font-medium text-foreground">{key}</Text>
                    )}
                  </Pressable>
                );
              })}
            </View>
          ))}
        </View>

        <NativeNoahButton
          label={initialAmountSat === null ? "Next" : "Update request"}
          loadingLabel="Generating…"
          onPress={submit}
          disabled={!canSubmit}
          isLoading={isSubmitting}
          size="lg"
          fullWidth
          testID="receive-amount-submit"
        />

        {initialAmountSat !== null ? (
          <NativeNoahSecondaryButton
            label="Remove amount"
            onPress={onRemove}
            disabled={isSubmitting}
            emphasis="ghost"
            tone="destructive"
            className="mt-2"
            fullWidth
            testID="receive-amount-remove"
          />
        ) : null}
      </View>
    </AppBottomSheet>
  );
}
