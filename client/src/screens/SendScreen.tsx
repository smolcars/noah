import { useIsFocused } from "@react-navigation/native";
import * as Clipboard from "expo-clipboard";
import { useEffect } from "react";

import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { QRCodeScanner } from "~/components/QRCodeScanner";
import { SendConfirmation } from "~/components/SendConfirmation";
import { SendSuccessBottomSheet } from "~/components/SendSuccessBottomSheet";
import { SendAmountStage } from "~/components/send/SendAmountStage";
import { SendChoiceStage, type SendChoiceOption } from "~/components/send/SendChoiceStage";
import { SendRecipientStage } from "~/components/send/SendRecipientStage";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { useSendScreen } from "~/hooks/useSendScreen";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import type { OnchainSendSource } from "~/lib/paymentsApi";
import type { SendRail } from "~/lib/sendFlow";

const SendScreen = () => {
  const isFocused = useIsFocused();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const {
    stage,
    amount,
    amountSat,
    setAmount,
    amountError,
    isAmountEditable,
    canSendMax,
    currency,
    fiatCurrency,
    btcPrice,
    toggleCurrency,
    offchainWalletBalance,
    onchainWalletBalance,
    destination,
    setDestination,
    destinationType,
    bip321Data,
    recipientError,
    lightningAddressSuggestions,
    handleSelectLightningAddressSuggestion,
    comment,
    setComment,
    commentAllowed,
    noteUsesLightning,
    isResolvingRecipient,
    startRecipientEntry,
    handleStageBack,
    handleAmountContinue,
    handleRecipientContinue,
    handleRailContinue,
    handleSourceContinue,
    handleMaxSend,
    parsedResult,
    handleConfirmSend,
    handleCancelConfirmation,
    handleDone,
    isSending,
    showCamera,
    setShowCamera,
    handleScanPress,
    codeScanner,
    selectedPaymentMethod,
    paymentRailOptions,
    railAvailability,
    selectedRail,
    setSelectedRail,
    onchainSourceOptions,
    selectedOnchainSource,
    setSelectedOnchainSource,
    isOnchainSourceSelectionRequired,
    isConfirmationAmountInvalid,
    isCheckingOwnOnchainAddress,
    isOwnOnchainAddress,
    isLightningAddressPaymentRouteResolutionRequired,
    showConfirmation,
    showSuccess,
    feeEstimate,
    isEstimatingFee,
    feeEstimateError,
    feeEstimateUnavailableText,
    feeEstimateNote,
    feeEstimateWarning,
    confirmationError,
    isMaxSend,
    maxSendAmountSat,
  } = useSendScreen();

  useEffect(() => {
    if (!isFocused && showCamera) {
      setShowCamera(false);
    }
  }, [isFocused, setShowCamera, showCamera]);

  const pasteDestination = async () => {
    const value = await Clipboard.getStringAsync();
    if (!value.trim()) {
      return;
    }

    setDestination(value);
    startRecipientEntry();
  };

  if (showCamera) {
    return <QRCodeScanner codeScanner={codeScanner} onClose={() => setShowCamera(false)} />;
  }

  const railChoices: SendChoiceOption<SendRail>[] = paymentRailOptions.map((rail) => ({
    value: rail,
    title: rail === "ark" ? "Ark" : rail === "lightning" ? "Lightning" : "On-chain",
    subtitle:
      rail === "ark"
        ? "Direct Ark payment with the lowest latency"
        : rail === "lightning"
          ? "Pay through the Lightning Network"
          : "Broadcast a Bitcoin transaction",
    unavailableReason: railAvailability[rail]
      ? undefined
      : rail === "onchain"
        ? "Neither balance can cover this amount"
        : "Insufficient Ark balance",
  }));
  const sourceChoices: SendChoiceOption<OnchainSendSource>[] = [
    {
      value: "offchain",
      title: "Ark balance",
      subtitle: "Send on-chain by offboarding from Ark",
      detail: formatBitcoinAmount(offchainWalletBalance),
      unavailableReason: onchainSourceOptions.includes("offchain")
        ? undefined
        : isMaxSend
          ? "No Ark balance available"
          : "Insufficient Ark balance",
    },
    {
      value: "onchain",
      title: "On-chain wallet",
      subtitle: "Spend confirmed on-chain funds",
      detail: formatBitcoinAmount(onchainWalletBalance),
      unavailableReason: onchainSourceOptions.includes("onchain")
        ? undefined
        : isMaxSend
          ? "No confirmed on-chain balance available"
          : "Insufficient confirmed balance",
    },
  ];

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      {stage === "method" ? (
        <SendChoiceStage
          title="How should Noah pay?"
          description="Noah recommends the first available method. You can choose another method supplied by this request."
          options={railChoices}
          value={selectedRail}
          onBack={handleStageBack}
          onChange={setSelectedRail}
          onContinue={handleRailContinue}
          testIDPrefix="send-method"
        />
      ) : stage === "source" ? (
        <SendChoiceStage
          title={isMaxSend ? "Which balance should Noah empty?" : "Which balance should Noah use?"}
          description={
            isMaxSend
              ? "MAX sends one balance in full. The miner fee is deducted from the final amount."
              : "Choose the balance that will fund this on-chain payment."
          }
          options={sourceChoices}
          value={selectedOnchainSource}
          onBack={handleStageBack}
          onChange={setSelectedOnchainSource}
          onContinue={handleSourceContinue}
          testIDPrefix="send-source"
        />
      ) : stage === "recipient" ? (
        <SendRecipientStage
          amountSat={isMaxSend ? maxSendAmountSat : amountSat}
          destination={destination}
          destinationType={destinationType}
          bip321Data={bip321Data}
          suggestions={lightningAddressSuggestions}
          error={recipientError}
          comment={comment}
          commentAllowed={commentAllowed}
          noteUsesLightning={noteUsesLightning}
          isResolving={isResolvingRecipient}
          onBack={handleStageBack}
          onDestinationChange={setDestination}
          onSelectSuggestion={handleSelectLightningAddressSuggestion}
          onCommentChange={setComment}
          onPaste={pasteDestination}
          onScan={handleScanPress}
          onContinue={handleRecipientContinue}
        />
      ) : (
        <SendAmountStage
          amount={amount}
          amountSat={amountSat}
          currency={currency}
          fiatCurrency={fiatCurrency}
          btcPrice={btcPrice}
          arkBalanceSat={offchainWalletBalance}
          onchainBalanceSat={onchainWalletBalance}
          error={amountError}
          isAmountEditable={isAmountEditable}
          canSendMax={canSendMax}
          onAmountChange={setAmount}
          onToggleCurrency={toggleCurrency}
          onContinue={handleAmountContinue}
          onMax={handleMaxSend}
          onPaste={pasteDestination}
          onScan={handleScanPress}
        />
      )}

      <AppBottomSheet
        isOpen={showConfirmation}
        onClose={handleCancelConfirmation}
        scrollable
        dismissible={!isSending}
      >
        <SendConfirmation
          destination={destination}
          amount={isMaxSend ? maxSendAmountSat : amountSat}
          amountNote={
            isMaxSend
              ? selectedOnchainSource === "offchain"
                ? "Estimated amount after offboarding fees"
                : selectedOnchainSource === "onchain"
                  ? "The final miner fee is calculated when the transaction is built"
                  : "Choose the balance to sweep"
              : null
          }
          destinationType={destinationType}
          comment={comment}
          btcPrice={btcPrice}
          fiatCurrency={fiatCurrency}
          bip321Data={bip321Data}
          selectedPaymentMethod={selectedPaymentMethod}
          selectedRail={selectedRail}
          selectedOnchainSource={selectedOnchainSource}
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

      <AppBottomSheet isOpen={showSuccess} onClose={handleDone} scrollable>
        {parsedResult ? (
          <SendSuccessBottomSheet
            parsedResult={parsedResult}
            handleDone={handleDone}
            btcPrice={btcPrice}
            fiatCurrency={fiatCurrency}
          />
        ) : null}
      </AppBottomSheet>
    </NoahSafeAreaView>
  );
};

export default SendScreen;
