import Icon from "@react-native-vector-icons/ionicons";
import { useState } from "react";
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  TextInput,
  View,
} from "react-native";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";

import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useThemeColors } from "~/hooks/useTheme";
import { getBip321Rails } from "~/lib/sendFlow";
import type { DestinationTypes, ParsedBip321 } from "~/lib/sendUtils";
import { COLORS } from "~/lib/styleConstants";

type SendRecipientStageProps = {
  amountSat: number;
  destination: string;
  destinationType: DestinationTypes;
  bip321Data: ParsedBip321 | null;
  suggestions: string[];
  error: string | null;
  comment: string;
  commentAllowed: number;
  noteUsesLightning: boolean;
  isResolving: boolean;
  onBack: () => void;
  onDestinationChange: (destination: string) => void;
  onSelectSuggestion: (suggestion: string) => void;
  onCommentChange: (comment: string) => void;
  onPaste: () => void;
  onScan: () => void;
  onContinue: () => void;
};

const getDestinationLabel = (destinationType: DestinationTypes) => {
  switch (destinationType) {
    case "ark":
      return "Ark address";
    case "lightning":
      return "Lightning invoice";
    case "offer":
      return "Lightning offer";
    case "lnurl":
      return "Lightning address";
    case "onchain":
      return "On-chain address";
    case "bip321":
      return "Bitcoin payment request";
    default:
      return null;
  }
};

export function SendRecipientStage({
  amountSat,
  destination,
  destinationType,
  bip321Data,
  suggestions,
  error,
  comment,
  commentAllowed,
  noteUsesLightning,
  isResolving,
  onBack,
  onDestinationChange,
  onSelectSuggestion,
  onCommentChange,
  onPaste,
  onScan,
  onContinue,
}: SendRecipientStageProps) {
  const colors = useThemeColors();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const bottomTabBarHeight = useBottomTabBarHeight();
  const [isEditingNote, setIsEditingNote] = useState(comment.length > 0);
  const destinationLabel = getDestinationLabel(destinationType);
  const railSummary =
    destinationType === "bip321" && bip321Data
      ? getBip321Rails(bip321Data)
          .map((rail) =>
            rail === "onchain" ? "On-chain" : `${rail[0].toUpperCase()}${rail.slice(1)}`,
          )
          .join(" · ")
      : destinationLabel;
  const canContinue = destinationType !== null && !error && !isResolving;

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === "ios" ? "padding" : undefined}
      className="flex-1"
      keyboardVerticalOffset={8}
      testID="send-recipient-stage"
    >
      <View className="flex-row items-center justify-between px-5 pt-4">
        <NativeNoahBackButton onPress={onBack} testID="send-recipient-back" />
        <View className="items-center">
          <Text className="text-xl font-bold text-foreground">Recipient</Text>
          {amountSat > 0 ? (
            <Text className="mt-1 text-sm text-muted-foreground">
              {formatBitcoinAmount(amountSat)}
            </Text>
          ) : null}
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Scan payment request"
          onPress={onScan}
          className="h-11 w-11 items-center justify-center rounded-full border border-border bg-card"
          testID="send-recipient-scan"
        >
          <Icon name="scan" size={22} color={colors.foreground} />
        </Pressable>
      </View>

      <ScrollView
        className="flex-1"
        contentContainerStyle={{ paddingHorizontal: 20, paddingTop: 36, paddingBottom: 24 }}
        keyboardShouldPersistTaps="handled"
      >
        <Text accessibilityRole="header" className="text-3xl font-bold text-foreground">
          Who are you paying?
        </Text>
        <Text className="mt-2 text-base leading-6 text-muted-foreground">
          Enter a Bitcoin, Lightning, or Ark destination.
        </Text>

        <View
          className="mt-8 rounded-[22px] border px-4 py-3"
          style={{
            borderColor: error ? "#dc2626" : `${colors.mutedForeground}38`,
            backgroundColor: colors.card,
          }}
        >
          <View className="flex-row items-center gap-3">
            <TextInput
              accessibilityLabel="Payment recipient"
              autoCapitalize="none"
              autoCorrect={false}
              autoFocus
              className="min-h-11 flex-1 text-base text-foreground"
              keyboardType="email-address"
              onChangeText={onDestinationChange}
              placeholder="Address, invoice, offer, or name@domain"
              placeholderTextColor={colors.mutedForeground}
              testID="send-recipient-input"
              value={destination}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Paste payment request"
              onPress={onPaste}
              className="rounded-full px-3 py-2"
              style={{ backgroundColor: `${colors.foreground}0D` }}
              testID="send-recipient-paste"
            >
              <Text className="text-sm font-semibold text-foreground">Paste</Text>
            </Pressable>
          </View>
        </View>

        {error ? (
          <Text
            className="mt-2 px-1 text-sm leading-5 text-destructive"
            testID="send-recipient-error"
          >
            {error}
          </Text>
        ) : null}

        {suggestions.length > 0 ? (
          <View className="mt-4 border-y border-border/60">
            {suggestions.map((suggestion) => (
              <Pressable
                key={suggestion}
                accessibilityRole="button"
                onPress={() => onSelectSuggestion(suggestion)}
                className="flex-row items-center gap-3 border-b border-border/60 py-4 last:border-b-0"
              >
                <View
                  className="h-10 w-10 items-center justify-center rounded-full"
                  style={{ backgroundColor: `${COLORS.BITCOIN_ORANGE}18` }}
                >
                  <Icon name="flash-outline" size={18} color={COLORS.BITCOIN_ORANGE} />
                </View>
                <Text className="min-w-0 flex-1 text-base font-semibold text-foreground">
                  {suggestion}
                </Text>
                <Icon name="chevron-forward" size={18} color={colors.mutedForeground} />
              </Pressable>
            ))}
          </View>
        ) : null}

        {destinationLabel ? (
          <View className="mt-5 flex-row items-center gap-4 border-y border-border/60 py-4">
            <View
              className="h-12 w-12 items-center justify-center rounded-full"
              style={{ backgroundColor: `${COLORS.BITCOIN_ORANGE}1A` }}
            >
              <Icon
                name={destinationType === "onchain" ? "logo-bitcoin" : "flash-outline"}
                size={23}
                color={COLORS.BITCOIN_ORANGE}
              />
            </View>
            <View className="min-w-0 flex-1">
              <Text className="text-base font-semibold text-foreground">{destinationLabel}</Text>
              {railSummary ? (
                <Text className="mt-1 text-sm text-muted-foreground">{railSummary}</Text>
              ) : null}
            </View>
            <Icon name="checkmark-circle" size={24} color={COLORS.SUCCESS} />
          </View>
        ) : null}

        {commentAllowed > 0 ? (
          <View className="mt-5">
            {isEditingNote ? (
              <>
                <TextInput
                  accessibilityLabel="Payment note"
                  className="rounded-2xl border border-border bg-card px-4 py-4 text-base text-foreground"
                  maxLength={commentAllowed}
                  onChangeText={onCommentChange}
                  placeholder="Add a note"
                  placeholderTextColor={colors.mutedForeground}
                  testID="send-note-input"
                  value={comment}
                />
                <Text className="mt-2 text-right text-xs text-muted-foreground">
                  {comment.length}/{commentAllowed}
                </Text>
              </>
            ) : (
              <Pressable
                accessibilityRole="button"
                onPress={() => setIsEditingNote(true)}
                className="self-start px-1 py-2"
                testID="send-add-note"
              >
                <Text className="font-semibold" style={{ color: COLORS.BITCOIN_ORANGE }}>
                  Add a note
                </Text>
              </Pressable>
            )}

            {noteUsesLightning ? (
              <Text className="mt-2 text-sm leading-5 text-muted-foreground">
                Adding a note will send this payment over Lightning.
              </Text>
            ) : null}
          </View>
        ) : null}
      </ScrollView>

      <View
        className="border-t border-border/50 bg-background px-5 pt-4"
        style={{ paddingBottom: Math.max(bottomTabBarHeight, 20) + 8 }}
      >
        <NativeNoahButton
          label="Continue"
          loadingLabel="Checking recipient…"
          onPress={onContinue}
          disabled={!canContinue}
          isLoading={isResolving}
          size="lg"
          fullWidth
          testID="send-recipient-next"
        />
      </View>
    </KeyboardAvoidingView>
  );
}
