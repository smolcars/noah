import { useIsFocused } from "@react-navigation/native";
import * as Clipboard from "expo-clipboard";
import { useEffect } from "react";

import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { QRCodeScanner } from "~/components/QRCodeScanner";
import { SendConfirmation } from "~/components/SendConfirmation";
import { SendSuccessBottomSheet } from "~/components/SendSuccessBottomSheet";
import { SendAmountStage } from "~/components/send/SendAmountStage";
import { SendRecipientStage } from "~/components/send/SendRecipientStage";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { useSendScreen } from "~/hooks/useSendScreen";

const SendScreen = () => {
  const isFocused = useIsFocused();
  const {
    stage,
    amount,
    amountSat,
    setAmount,
    amountError,
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
    setSelectedPaymentMethod,
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
    handleCloseSuccess,
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

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      {stage === "recipient" ? (
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
          onAmountChange={setAmount}
          onToggleCurrency={toggleCurrency}
          onContinue={handleAmountContinue}
          onMax={() => {
            handleMaxSend();
            startRecipientEntry();
          }}
          onPaste={pasteDestination}
          onScan={handleScanPress}
        />
      )}

      <AppBottomSheet isOpen={showConfirmation} onClose={handleCancelConfirmation} scrollable>
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
