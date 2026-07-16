import React from "react";
import { useSendScreen } from "../hooks/useSendScreen";
import { SendSuccessBottomSheet } from "../components/SendSuccessBottomSheet";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { QRCodeScanner } from "~/components/QRCodeScanner";
import {
  View,
  TextInput,
  TouchableOpacity,
  TouchableWithoutFeedback,
  Keyboard,
  Pressable,
  ScrollView,
} from "react-native";

import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor, useThemeColors } from "../hooks/useTheme";
import * as Clipboard from "expo-clipboard";
import { formatFiatAmount, getFiatCurrencyInfo, satsToFiat } from "~/lib/fiatCurrency";
import { useIsFocused } from "@react-navigation/native";
import { Button } from "~/components/ui/button";
import { Text } from "~/components/ui/text";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { SendConfirmation } from "~/components/SendConfirmation";
import { CurrencyToggle } from "~/components/CurrencyToggle";
import { COLORS } from "~/lib/styleConstants";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { BlinkingCaret } from "~/components/BlinkingCaret";
import { useBitcoinAmountFormatter, useBitcoinAmountUnit } from "~/hooks/useBitcoinAmountFormatter";

const SendScreen = () => {
  const isFocused = useIsFocused();
  const iconColor = useIconColor();
  const colors = useThemeColors();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const bitcoinAmountUnit = useBitcoinAmountUnit();
  const bottomTabBarHeight = useBottomTabBarHeight();
  const destinationInputRef = React.useRef<TextInput>(null);
  const amountInputRef = React.useRef<TextInput>(null);
  const [isAmountFocused, setIsAmountFocused] = React.useState(false);
  const {
    destination,
    setDestination,
    isDestinationFocused,
    setIsDestinationFocused,
    lightningAddressSuggestions,
    handleSelectLightningAddressSuggestion,
    amount,
    setAmount,
    isMaxSend,
    canSendMax,
    handleMaxSend,
    maxSendAmountSat,
    isAmountEditable,
    comment,
    setComment,
    parsedResult,
    handleSend,
    handleConfirmSend,
    handleCancelConfirmation,
    handleDone,
    isSending,
    showCamera,
    setShowCamera,
    handleScanPress,
    codeScanner,
    currency,
    fiatCurrency,
    toggleCurrency,
    amountSat,
    btcPrice,
    parsedAmount,
    bip321Data,
    selectedPaymentMethod,
    setSelectedPaymentMethod,
    onchainSourceOptions,
    selectedOnchainSource,
    setSelectedOnchainSource,
    isOnchainSourceSelectionRequired,
    isConfirmationAmountInvalid,
    isCheckingOwnOnchainAddress,
    isOwnOnchainAddress,
    isLightningAddressPaymentRouteResolutionRequired,
    onchainWalletBalance,
    offchainWalletBalance,
    handleClear,
    showConfirmation,
    destinationType,
    showSuccess,
    handleCloseSuccess,
    feeEstimate,
    isEstimatingFee,
    feeEstimateError,
    feeEstimateUnavailableText,
    feeEstimateNote,
    feeEstimateWarning,
    confirmationError,
  } = useSendScreen();
  const fiatCurrencyInfo = getFiatCurrencyInfo(fiatCurrency);
  const displayAmount = isMaxSend
    ? maxSendAmountSat.toLocaleString()
    : amount === ""
      ? currency === "FIAT"
        ? "0.00"
        : "0"
      : amount;
  const amountPrefix =
    currency === "FIAT" ? fiatCurrencyInfo.symbol : bitcoinAmountUnit === "bip177" ? "₿" : null;
  const amountSuffix = currency === "SATS" && bitcoinAmountUnit === "sats" ? "sats" : null;

  const handlePaste = async () => {
    const text = await Clipboard.getStringAsync();
    setDestination(text);
  };

  const focusDestinationInput = React.useCallback(() => {
    setIsDestinationFocused(true);
    requestAnimationFrame(() => {
      destinationInputRef.current?.focus();
    });
  }, [setIsDestinationFocused]);

  const focusAmountInput = React.useCallback(() => {
    if (!isAmountEditable) {
      return;
    }

    setAmount(amount);
    requestAnimationFrame(() => {
      amountInputRef.current?.focus();
    });
  }, [amount, isAmountEditable, setAmount]);

  React.useEffect(() => {
    if (isAmountEditable) {
      return;
    }

    amountInputRef.current?.blur();
    setIsAmountFocused(false);
  }, [isAmountEditable]);

  // Close scanner when navigating away from the screen
  React.useEffect(() => {
    if (!isFocused && showCamera) {
      setShowCamera(false);
    }
  }, [isFocused, showCamera, setShowCamera]);

  if (showCamera) {
    return <QRCodeScanner codeScanner={codeScanner} onClose={() => setShowCamera(false)} />;
  }

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <TouchableWithoutFeedback onPress={Keyboard.dismiss} accessible={false}>
        <View className="flex-1">
          <View className="flex-row items-center px-5 pt-4 pb-3">
            <Text className="text-2xl font-bold text-foreground">Send</Text>
            <View className="flex-1 items-end">
              <Pressable onPress={handleScanPress}>
                <Icon name="scan" size={28} color={iconColor} />
              </Pressable>
            </View>
          </View>
          <ScrollView
            className="flex-1"
            showsVerticalScrollIndicator={false}
            keyboardShouldPersistTaps="handled"
            contentContainerStyle={{
              paddingBottom: Math.max(bottomTabBarHeight, 20) + 112,
            }}
          >
            <View className="px-5 pb-8">
              <View className="pt-2">
                <Text className="text-[11px] font-semibold uppercase tracking-[3px] text-muted-foreground">
                  Send Bitcoin
                </Text>
                <Text className="mt-2 max-w-[320px] text-base leading-6 text-muted-foreground">
                  Paste a destination, choose the payment rail when needed, and confirm before
                  sending.
                </Text>
              </View>

              <View className="mt-5">
                <View className="flex-row items-start justify-end gap-4">
                  <View className="flex-1">
                    {canSendMax ? (
                      <Pressable
                        accessibilityRole="button"
                        accessibilityLabel="Send maximum amount"
                        onPress={handleMaxSend}
                        className="self-start rounded-full border border-border px-4 py-2"
                        style={{
                          backgroundColor: isMaxSend
                            ? `${COLORS.BITCOIN_ORANGE}20`
                            : `${colors.card}CC`,
                          borderColor: isMaxSend
                            ? COLORS.BITCOIN_ORANGE
                            : `${colors.mutedForeground}26`,
                        }}
                      >
                        <Text
                          className="text-xs font-bold tracking-[2px]"
                          style={{
                            color: isMaxSend ? COLORS.BITCOIN_ORANGE : colors.foreground,
                          }}
                        >
                          MAX
                        </Text>
                      </Pressable>
                    ) : null}
                  </View>
                  <CurrencyToggle onPress={toggleCurrency} disabled={!!parsedAmount || isMaxSend} />
                </View>

                <View className="mt-3 items-center">
                  <View className="h-[64px] justify-center">
                    <View className="self-center">
                      <Pressable onPress={focusAmountInput} disabled={!isAmountEditable}>
                        <View className="flex-row items-center justify-center">
                          {amountPrefix ? (
                            <Text className="mr-3 text-[46px] font-bold leading-[52px] text-foreground">
                              {amountPrefix}
                            </Text>
                          ) : null}
                          <Text
                            className={`text-[46px] font-bold leading-[52px] ${
                              isAmountEditable ? "text-foreground" : "text-foreground/70"
                            }`}
                          >
                            {displayAmount}
                          </Text>
                          <BlinkingCaret
                            color={COLORS.BITCOIN_ORANGE}
                            height={40}
                            visible={isAmountFocused && isAmountEditable}
                          />
                          {amountSuffix ? (
                            <Text className="ml-3 text-2xl font-bold text-muted-foreground">
                              {amountSuffix}
                            </Text>
                          ) : null}
                        </View>
                      </Pressable>
                    </View>

                    <TextInput
                      ref={amountInputRef}
                      placeholder=""
                      keyboardType="numeric"
                      value={amount}
                      onChangeText={setAmount}
                      editable={isAmountEditable}
                      autoFocus={false}
                      onFocus={() => setIsAmountFocused(true)}
                      onBlur={() => setIsAmountFocused(false)}
                      maxLength={12}
                      selectionColor={colors.foreground}
                      style={{
                        position: "absolute",
                        opacity: 0,
                        width: 1,
                        height: 1,
                      }}
                    />
                  </View>

                  <Text className="mt-3 text-lg font-medium text-muted-foreground">
                    {isMaxSend
                      ? selectedOnchainSource === "offchain"
                        ? "Full Ark balance"
                        : selectedOnchainSource === "onchain"
                          ? "Full onchain balance"
                          : "Choose the balance to sweep on confirmation"
                      : parsedAmount
                        ? `≈ ${
                            btcPrice
                              ? formatFiatAmount(
                                  satsToFiat(parsedAmount, btcPrice, fiatCurrency),
                                  fiatCurrency,
                                )
                              : formatFiatAmount("0.00", fiatCurrency)
                          }`
                        : currency === "SATS"
                          ? `≈ ${
                              btcPrice && amountSat && !isNaN(amountSat)
                                ? formatFiatAmount(
                                    satsToFiat(amountSat, btcPrice, fiatCurrency),
                                    fiatCurrency,
                                  )
                                : formatFiatAmount("0.00", fiatCurrency)
                            }`
                          : `≈ ${!isNaN(amountSat) && amount ? formatBitcoinAmount(amountSat) : formatBitcoinAmount(0)}`}
                  </Text>
                </View>
              </View>

              <View className="mt-7 border-t border-border/60 pt-5">
                <Text className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
                  Destination
                </Text>

                <View
                  className="mt-4 rounded-[22px] border px-4 py-3"
                  style={{
                    borderColor: isDestinationFocused
                      ? COLORS.BITCOIN_ORANGE
                      : `${colors.mutedForeground}26`,
                    backgroundColor: `${colors.card}CC`,
                  }}
                >
                  <View className="flex-row items-center gap-3">
                    {destination && !isDestinationFocused ? (
                      <Pressable className="flex-1" onPress={focusDestinationInput}>
                        <Text
                          className="text-base text-foreground"
                          numberOfLines={1}
                          ellipsizeMode="middle"
                        >
                          {destination}
                        </Text>
                      </Pressable>
                    ) : (
                      <TextInput
                        ref={destinationInputRef}
                        className="min-h-9 flex-1 text-base text-foreground"
                        placeholder="Address, invoice, or lightning address"
                        placeholderTextColor={colors.mutedForeground}
                        keyboardType="email-address"
                        autoCorrect={false}
                        autoCapitalize="none"
                        value={destination}
                        onChangeText={setDestination}
                        onFocus={() => setIsDestinationFocused(true)}
                        onBlur={() => setIsDestinationFocused(false)}
                        style={{ minWidth: 0, flexShrink: 1 }}
                      />
                    )}
                    <TouchableOpacity
                      onPress={handlePaste}
                      className="rounded-full px-3 py-2"
                      style={{ backgroundColor: `${colors.foreground}0D` }}
                    >
                      <Text className="text-sm font-semibold text-foreground">Paste</Text>
                    </TouchableOpacity>
                  </View>
                </View>

                {isDestinationFocused && lightningAddressSuggestions.length > 0 && (
                  <View
                    className="mt-3 overflow-hidden rounded-[18px] border"
                    style={{
                      borderColor: `${colors.mutedForeground}24`,
                      backgroundColor: colors.card,
                    }}
                  >
                    {lightningAddressSuggestions.map((suggestion, index) => (
                      <Pressable
                        key={suggestion}
                        className={`flex-row items-center gap-3 px-4 py-3 ${
                          index < lightningAddressSuggestions.length - 1
                            ? "border-b border-border/60"
                            : ""
                        }`}
                        onPress={() => {
                          handleSelectLightningAddressSuggestion(suggestion);
                          Keyboard.dismiss();
                        }}
                      >
                        <View
                          className="h-8 w-8 items-center justify-center rounded-full"
                          style={{ backgroundColor: `${COLORS.BITCOIN_ORANGE}18` }}
                        >
                          <Icon name="flash-outline" size={15} color={COLORS.BITCOIN_ORANGE} />
                        </View>
                        <View className="min-w-0 flex-1">
                          <Text
                            className="text-[15px] font-semibold text-foreground"
                            numberOfLines={1}
                            ellipsizeMode="middle"
                          >
                            {suggestion}
                          </Text>
                        </View>
                      </Pressable>
                    ))}
                  </View>
                )}
                {!bip321Data ? (
                  <View className="mt-4 rounded-[20px] border border-border/60 bg-card/70 px-4 py-3">
                    <TextInput
                      className="text-base text-foreground"
                      placeholder="Add a note (optional)"
                      placeholderTextColor={colors.mutedForeground}
                      value={comment}
                      onChangeText={setComment}
                    />
                  </View>
                ) : null}
              </View>
            </View>
          </ScrollView>

          <View
            className="border-t border-border/50 bg-background px-5 pt-4"
            style={{ paddingBottom: Math.max(bottomTabBarHeight, 20) + 8 }}
          >
            <View className="flex-row items-center gap-3">
              {destination ? (
                <Button onPress={handleClear} variant="outline" className="flex-1 rounded-2xl">
                  <Text className="font-semibold">Clear</Text>
                </Button>
              ) : null}
              <Button
                onPress={handleSend}
                disabled={!destination || isSending}
                className="flex-1 rounded-2xl py-4"
                style={{ backgroundColor: COLORS.BITCOIN_ORANGE }}
              >
                <Text className="font-bold" style={{ color: "#1a1a1a" }}>
                  {isSending ? "Sending..." : "Send"}
                </Text>
              </Button>
            </View>
          </View>
        </View>
      </TouchableWithoutFeedback>

      <AppBottomSheet isOpen={showConfirmation} onClose={handleCancelConfirmation} scrollable>
        <SendConfirmation
          destination={destination}
          amount={isMaxSend ? maxSendAmountSat : amountSat}
          amountNote={
            isMaxSend
              ? selectedOnchainSource === "offchain"
                ? "Sweeps the full Ark balance after the offboarding fee."
                : selectedOnchainSource === "onchain"
                  ? "Sweeps the full onchain balance; the miner fee is deducted when the transaction is built."
                  : "Choose which balance to sweep."
              : null
          }
          destinationType={destinationType}
          comment={comment}
          btcPrice={btcPrice}
          fiatCurrency={fiatCurrency}
          bip321Data={bip321Data}
          selectedPaymentMethod={selectedPaymentMethod}
          onSelectPaymentMethod={setSelectedPaymentMethod}
          onchainSourceOptions={onchainSourceOptions}
          selectedOnchainSource={selectedOnchainSource}
          onSelectOnchainSource={setSelectedOnchainSource}
          onchainWalletBalance={onchainWalletBalance}
          offchainWalletBalance={offchainWalletBalance}
          onConfirm={handleConfirmSend}
          onCancel={handleCancelConfirmation}
          isConfirmDisabled={
            isOnchainSourceSelectionRequired ||
            isConfirmationAmountInvalid ||
            isLightningAddressPaymentRouteResolutionRequired ||
            isCheckingOwnOnchainAddress ||
            isOwnOnchainAddress
          }
          isLoading={isSending}
          feeEstimate={feeEstimate}
          isEstimatingFee={isEstimatingFee}
          feeEstimateError={feeEstimateError}
          feeEstimateUnavailableText={feeEstimateUnavailableText}
          feeEstimateNote={feeEstimateNote}
          feeEstimateWarning={feeEstimateWarning}
          sendError={confirmationError}
        />
      </AppBottomSheet>

      <AppBottomSheet isOpen={showSuccess} onClose={handleCloseSuccess} scrollable>
        {parsedResult && (
          <SendSuccessBottomSheet
            parsedResult={parsedResult}
            handleDone={handleDone}
            btcPrice={btcPrice}
            fiatCurrency={fiatCurrency}
          />
        )}
      </AppBottomSheet>
    </NoahSafeAreaView>
  );
};

export default SendScreen;
