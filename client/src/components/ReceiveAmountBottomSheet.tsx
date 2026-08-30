import Icon from "@react-native-vector-icons/ionicons";
import { useEffect, useState } from "react";
import { Keyboard, Pressable, TextInput, View } from "react-native";

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
  const [noteDraft, setNoteDraft] = useState("");
  const [isEditingNote, setIsEditingNote] = useState(false);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    setCurrency("SATS");
    setAmount(initialAmountSat?.toString() ?? "");
    setDescription(initialDescription);
    setNoteDraft(initialDescription);
    setIsEditingNote(false);
  }, [initialAmountSat, initialDescription, isOpen, setAmount, setCurrency]);

  const isDescriptionValid = isInvoiceDescriptionValid(description);
  const noteDraftLength = getInvoiceDescriptionLength(noteDraft);
  const isNoteDraftValid = isInvoiceDescriptionValid(noteDraft);
  const canSaveNote =
    isNoteDraftValid && (noteDraft.trim().length > 0 || description.trim().length > 0);
  const canSubmit = Number.isInteger(amountSat) && amountSat > 0 && isDescriptionValid;
  const displayAmount = amount.length === 0 ? "0" : formatNumber(amount);
  const amountPrefix =
    currency === "FIAT" ? fiatCurrencyInfo.symbol : bitcoinAmountUnit === "bip177" ? "₿" : null;
  const primaryAmount = amountPrefix ? `${amountPrefix}${displayAmount}` : displayAmount;
  const primaryAmountFontSize =
    primaryAmount.length <= 7 ? 56 : primaryAmount.length <= 10 ? 44 : 34;
  const amountSuffix = currency === "SATS" && bitcoinAmountUnit === "sats" ? "sats" : null;
  const convertedAmount =
    currency === "SATS"
      ? btcPrice
        ? formatFiatAmount(satsToFiat(amountSat, btcPrice, fiatCurrency), fiatCurrency)
        : `${fiatCurrencyInfo.code} rate unavailable`
      : formatBitcoinAmount(amountSat);

  const close = () => {
    if (!isSubmitting) {
      Keyboard.dismiss();
      onClose();
    }
  };

  const openNoteEditor = () => {
    setNoteDraft(description);
    setIsEditingNote(true);
  };

  const closeNoteEditor = () => {
    Keyboard.dismiss();
    setNoteDraft(description);
    setIsEditingNote(false);
  };

  const saveNote = () => {
    if (!canSaveNote || isSubmitting) {
      return;
    }

    Keyboard.dismiss();
    setDescription(noteDraft.trim());
    setIsEditingNote(false);
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
    <AppBottomSheet isOpen={isOpen} onClose={close}>
      {isEditingNote ? (
        <View className="flex-1">
          <View className="flex-row items-center justify-between">
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Close note editor"
              onPress={closeNoteEditor}
              className="h-12 w-12 items-center justify-center rounded-full border border-border"
            >
              <Icon name="close" size={24} color={colors.foreground} />
            </Pressable>
            <View className="h-12 w-12" />
          </View>

          <Text
            accessibilityRole="header"
            className="mt-8 text-2xl font-bold leading-8 text-foreground"
          >
            What is the payment for?
          </Text>

          <TextInput
            accessibilityLabel="Lightning note"
            autoFocus
            className="mt-8 rounded-2xl border border-foreground bg-background px-5 py-4 text-lg text-foreground"
            editable={!isSubmitting}
            maxLength={MAX_INVOICE_DESCRIPTION_LENGTH}
            onChangeText={setNoteDraft}
            onSubmitEditing={saveNote}
            placeholder="Your note"
            placeholderTextColor={colors.mutedForeground}
            returnKeyType="done"
            testID="receive-note-input"
            value={noteDraft}
          />
          {noteDraftLength >= MAX_INVOICE_DESCRIPTION_LENGTH - 40 || !isNoteDraftValid ? (
            <Text
              className={`mt-2 text-right text-sm ${
                isNoteDraftValid ? "text-muted-foreground" : "text-destructive"
              }`}
            >
              {noteDraftLength}/{MAX_INVOICE_DESCRIPTION_LENGTH}
            </Text>
          ) : null}

          <View className="mt-6 px-1">
            <NativeNoahButton
              label="Done"
              onPress={saveNote}
              disabled={!canSaveNote}
              size="lg"
              fullWidth
              testID="receive-note-done"
            />
          </View>
        </View>
      ) : (
        <View className="flex-1">
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

            <Pressable
              accessibilityRole="button"
              accessibilityLabel={description ? "Edit Lightning note" : "Add a Lightning note"}
              accessibilityState={{ disabled: isSubmitting }}
              disabled={isSubmitting}
              onPress={openNoteEditor}
              className="rounded-full border border-border bg-card px-5 py-3"
              testID="receive-note-button"
            >
              <Text className="font-semibold text-foreground">
                {description ? "Edit note" : "Add a note"}
              </Text>
            </Pressable>

            <View className="h-12 w-12" />
          </View>

          <View className="flex-1 items-center justify-center py-5">
            <View
              accessibilityRole="text"
              accessibilityLabel={`${amount.length === 0 ? "0" : amount} ${
                currency === "SATS" ? "sats" : fiatCurrency
              }`}
              className="flex-row items-baseline justify-center px-4"
            >
              <Text
                className="text-center font-bold text-foreground"
                numberOfLines={1}
                style={{
                  width: amountSuffix ? 250 : 320,
                  fontSize: primaryAmountFontSize,
                  lineHeight: primaryAmountFontSize + 8,
                }}
              >
                {primaryAmount}
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
              className="mt-2 flex-row items-center gap-2 px-4 py-2"
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

          <View className="px-1">
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
          </View>

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
      )}
    </AppBottomSheet>
  );
}
