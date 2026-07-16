import { useEffect, useState } from "react";
import { Keyboard, Linking, Pressable, View } from "react-native";
import Icon from "@react-native-vector-icons/ionicons";

import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { FeeEstimateSummary } from "~/components/FeeEstimateSummary";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { Input } from "~/components/ui/input";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useBoardAllAmountArk, useBoardArk, useBoardArkFeeEstimate } from "~/hooks/usePayments";
import { useArkInfo, useBalance } from "~/hooks/useWallet";
import { getMempoolTxUrl } from "~/constants";
import { useCopyToClipboard } from "~/lib/clipboardUtils";
import { COLORS } from "~/lib/styleConstants";

type BoardArkBottomSheetProps = {
  isOpen: boolean;
  onClose: () => void;
};

export function BoardArkBottomSheet({ isOpen, onClose }: BoardArkBottomSheetProps) {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const { copyWithState, isCopied, resetCopiedState } = useCopyToClipboard(1200);
  const { data: balance, isLoading: isBalanceLoading } = useBalance();
  const { data: arkInfo, isLoading: isArkInfoLoading } = useArkInfo(isOpen);
  const boardMutation = useBoardArk();
  const boardAllMutation = useBoardAllAmountArk();
  const [amount, setAmount] = useState("");
  const [isMaxAmount, setIsMaxAmount] = useState(false);
  const [debouncedEstimateParams, setDebouncedEstimateParams] = useState<{
    amountSat: number;
    confirmedOnchainBalanceSat: number;
    isMaxAmount: boolean;
    minimumBoardAmountSat: number;
  } | null>(null);

  const onchainBalance = balance?.onchain.confirmed ?? 0;
  const parsedAmount = /^\d+$/.test(amount) ? Number(amount) : 0;
  const amountSat = Number.isSafeInteger(parsedAmount) ? parsedAmount : 0;
  const minimumBoardAmountSat = arkInfo?.min_board_amount ?? 0;
  const isBelowMinimum = amountSat > 0 && amountSat < minimumBoardAmountSat;
  const canEstimate =
    amountSat > 0 && onchainBalance > 0 && minimumBoardAmountSat > 0 && !isBelowMinimum;

  useEffect(() => {
    if (!canEstimate) {
      setDebouncedEstimateParams(null);
      return;
    }

    const timeout = setTimeout(
      () =>
        setDebouncedEstimateParams({
          amountSat,
          confirmedOnchainBalanceSat: onchainBalance,
          isMaxAmount,
          minimumBoardAmountSat,
        }),
      300,
    );
    return () => clearTimeout(timeout);
  }, [amountSat, canEstimate, isMaxAmount, minimumBoardAmountSat, onchainBalance]);

  const feeEstimateQuery = useBoardArkFeeEstimate(debouncedEstimateParams);
  const isWaitingForEstimate =
    canEstimate &&
    (debouncedEstimateParams?.amountSat !== amountSat ||
      debouncedEstimateParams.confirmedOnchainBalanceSat !== onchainBalance ||
      debouncedEstimateParams.isMaxAmount !== isMaxAmount ||
      debouncedEstimateParams.minimumBoardAmountSat !== minimumBoardAmountSat);
  const currentFeeEstimateResult = isWaitingForEstimate ? undefined : feeEstimateQuery.data;
  const feeEstimate =
    currentFeeEstimateResult?.kind === "estimate" ? currentFeeEstimateResult.estimate : undefined;
  const unavailableEstimate =
    currentFeeEstimateResult?.kind === "unavailable"
      ? currentFeeEstimateResult.unavailable
      : undefined;
  const isMaxEstimatePending =
    isMaxAmount && canEstimate && (isWaitingForEstimate || feeEstimateQuery.isFetching);
  const boardResult = boardMutation.data ?? boardAllMutation.data;
  const isFundingTxCopied = isCopied("funding-txid");
  const fundingTxExplorerUrl = boardResult?.funding_txid
    ? getMempoolTxUrl(boardResult.funding_txid)
    : null;
  const isSubmitting = boardMutation.isPending || boardAllMutation.isPending;
  const error = boardMutation.error ?? boardAllMutation.error;

  const reset = () => {
    setAmount("");
    setIsMaxAmount(false);
    setDebouncedEstimateParams(null);
    resetCopiedState();
    boardMutation.reset();
    boardAllMutation.reset();
  };

  const close = () => {
    if (isSubmitting) {
      return;
    }

    Keyboard.dismiss();
    onClose();
  };

  const dismiss = () => {
    if (!isSubmitting) {
      reset();
    }
  };

  const submit = () => {
    Keyboard.dismiss();

    if (
      !arkInfo ||
      amountSat <= 0 ||
      amountSat > onchainBalance ||
      isBelowMinimum ||
      unavailableEstimate ||
      isMaxEstimatePending
    ) {
      return;
    }

    if (isMaxAmount) {
      boardAllMutation.mutate();
    } else {
      boardMutation.mutate(amountSat);
    }
  };

  const copyFundingTxid = () => {
    if (!boardResult?.funding_txid) {
      return;
    }

    void copyWithState(boardResult.funding_txid, "funding-txid");
  };

  return (
    <AppBottomSheet isOpen={isOpen} onClose={close} onDismiss={dismiss} scrollable avoidKeyboard>
      {boardResult ? (
        <View className="pb-4">
          <View className="items-center">
            <View className="h-16 w-16 items-center justify-center rounded-full bg-green-500/15">
              <Icon name="checkmark" size={34} color={COLORS.SUCCESS} />
            </View>
            <Text className="mt-4 text-center text-2xl font-bold text-foreground">
              Boarding started
            </Text>
            <Text className="mt-2 max-w-[300px] text-center text-sm leading-5 text-muted-foreground">
              Your onchain bitcoin is moving into Ark. It will become spendable after the board
              completes.
            </Text>
          </View>

          <View className="mt-6 rounded-2xl border border-border bg-card px-4 py-4">
            <Text className="text-xs font-semibold uppercase tracking-[2px] text-muted-foreground">
              Funding transaction
            </Text>
            <Text className="mt-2 text-sm text-foreground" numberOfLines={1} ellipsizeMode="middle">
              {boardResult.funding_txid}
            </Text>
            <View className="mt-3 flex-row items-center gap-5">
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Copy boarding transaction ID"
                onPress={copyFundingTxid}
                className="flex-row items-center gap-2"
              >
                <Icon
                  name={isFundingTxCopied ? "checkmark-circle-outline" : "copy-outline"}
                  size={17}
                  color={isFundingTxCopied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE}
                />
                <Text
                  className="text-xs font-semibold"
                  style={{
                    color: isFundingTxCopied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE,
                  }}
                >
                  {isFundingTxCopied ? "Copied" : "Copy"}
                </Text>
              </Pressable>
              {fundingTxExplorerUrl ? (
                <Pressable
                  accessibilityRole="link"
                  accessibilityLabel="Open boarding transaction in block explorer"
                  onPress={() => {
                    void Linking.openURL(fundingTxExplorerUrl);
                  }}
                  className="flex-row items-center gap-2"
                >
                  <Icon name="open-outline" size={17} color={COLORS.BITCOIN_ORANGE} />
                  <Text className="text-xs font-semibold" style={{ color: COLORS.BITCOIN_ORANGE }}>
                    View in explorer
                  </Text>
                </Pressable>
              ) : null}
            </View>
          </View>

          <NativeNoahButton
            label="Done"
            onPress={() => {
              reset();
              onClose();
            }}
            className="mt-6"
            fullWidth
          />
        </View>
      ) : (
        <View className="pb-4">
          <View className="items-center">
            <Text className="text-center text-2xl font-bold text-foreground">Board to Ark</Text>
            <Text className="mt-2 max-w-[310px] text-center text-sm leading-5 text-muted-foreground">
              Move onchain bitcoin into Ark for fast, low-cost payments.
            </Text>
          </View>

          <View className="mt-6 rounded-2xl border border-border bg-card px-4 py-4">
            <Text className="text-xs font-semibold uppercase tracking-[2px] text-muted-foreground">
              Confirmed onchain balance
            </Text>
            <Text className="mt-2 text-2xl font-bold text-foreground">
              {isBalanceLoading ? "Loading…" : formatBitcoinAmount(onchainBalance)}
            </Text>
          </View>

          <View className="mt-5">
            <Text className="mb-2 text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
              Amount
            </Text>
            <View className="flex-row items-center gap-3">
              <Input
                value={amount}
                onChangeText={(value) => {
                  setAmount(value);
                  setIsMaxAmount(false);
                }}
                placeholder="Amount in sats"
                keyboardType="number-pad"
                editable={!isSubmitting}
                className="flex-1 rounded-2xl border-border bg-card px-4 py-4 text-foreground"
                testID="board-ark-amount-input"
              />
              <NativeNoahSecondaryButton
                label="MAX"
                onPress={() => {
                  setAmount(String(onchainBalance));
                  setIsMaxAmount(true);
                }}
                disabled={onchainBalance <= 0 || isSubmitting}
                width={88}
              />
            </View>
          </View>

          {isBelowMinimum ? (
            <View className="mt-4 rounded-2xl border border-amber-500/30 bg-amber-500/10 px-4 py-3">
              <Text className="text-sm leading-5 text-amber-700 dark:text-amber-200">
                The minimum board amount is {formatBitcoinAmount(minimumBoardAmountSat)}.
              </Text>
            </View>
          ) : null}

          <FeeEstimateSummary
            estimate={feeEstimate}
            isLoading={feeEstimateQuery.isFetching || isWaitingForEstimate}
            error={feeEstimateQuery.error}
            netLabel="Ark balance receives"
            feeLabel="Ark boarding fee"
            grossLabel="Amount boarded"
            note={
              feeEstimate
                ? `Estimated onchain fee: ${formatBitcoinAmount(feeEstimate.estimated_onchain_fee_sat)}.`
                : null
            }
            compact
          />

          {unavailableEstimate ? (
            <View className="mt-4 rounded-2xl border border-amber-500/30 bg-amber-500/10 px-4 py-3">
              <Text className="text-sm font-semibold text-amber-700 dark:text-amber-200">
                Not enough onchain balance to board MAX
              </Text>
              <Text className="mt-1 text-sm leading-5 text-amber-700 dark:text-amber-200">
                After the estimated onchain fee,{" "}
                {formatBitcoinAmount(unavailableEstimate.boardable_amount_sat)} would be available
                to board, below the{" "}
                {formatBitcoinAmount(unavailableEstimate.minimum_board_amount_sat)} minimum. You
                need at least{" "}
                {formatBitcoinAmount(unavailableEstimate.minimum_required_balance_sat)} confirmed
                onchain.
              </Text>
            </View>
          ) : null}

          {error ? (
            <View className="mt-4 rounded-2xl border border-destructive/35 bg-destructive/10 px-4 py-3">
              <Text className="text-sm font-semibold text-destructive">Boarding failed</Text>
              <Text className="mt-1 text-sm text-destructive/90">{error.message}</Text>
            </View>
          ) : null}

          <View className="mt-6 flex-row gap-3">
            <NativeNoahSecondaryButton
              label="Cancel"
              onPress={close}
              disabled={isSubmitting}
              className="flex-1"
              fullWidth
            />
            <NativeNoahButton
              label="Board"
              loadingLabel="Boarding…"
              onPress={submit}
              isLoading={isSubmitting}
              disabled={
                isSubmitting ||
                isArkInfoLoading ||
                !arkInfo ||
                amountSat <= 0 ||
                amountSat > onchainBalance ||
                isBelowMinimum ||
                !!unavailableEstimate ||
                isMaxEstimatePending
              }
              className="flex-1"
              fullWidth
              testID="board-ark-submit-button"
            />
          </View>
        </View>
      )}
    </AppBottomSheet>
  );
}
