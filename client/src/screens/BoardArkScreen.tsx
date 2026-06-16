import React, { useState, useEffect, useMemo } from "react";
import {
  View,
  Pressable,
  ScrollView,
  TouchableWithoutFeedback,
  Keyboard,
  KeyboardAvoidingView,
} from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { Text } from "../components/ui/text";
import { Input } from "../components/ui/input";
import { Button } from "../components/ui/button";
import { validateBitcoinAddress } from "bip-321";
import { APP_VARIANT } from "../config";
import { NoahButton } from "../components/ui/NoahButton";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import { useArkInfo, useBalance } from "../hooks/useWallet";
import {
  useBoardAllAmountArk,
  useBoardArk,
  useBoardArkFeeEstimate,
  useOffboardAllArk,
  useOffboardAllFeeEstimate,
  type BoardArkFeeEstimateResult,
} from "../hooks/usePayments";
import { copyToClipboard } from "../lib/clipboardUtils";
import { cn, formatBip177, isNetworkMatch } from "../lib/utils";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { useAlert } from "~/contexts/AlertProvider";
import logger from "~/lib/log";
import { HomeStackParamList } from "~/Navigators";
import { BoardResult } from "react-native-nitro-ark";
import { useTransactionStore } from "~/store/transactionStore";
import { FeeEstimateSummary } from "~/components/FeeEstimateSummary";
import { FeeEstimateBox, FeeEstimateRow, FeeEstimateSeparator } from "~/components/FeeEstimateBox";

const log = logger("BoardArkScreen");

type Vtxo = {
  id: string;
  amount_sat: number;
  vtxo_type: string;
  utxo: string;
  user_pubkey: string;
  asp_pubkey: string;
  expiry_height: number;
  exit_delta: number;
  spk: string;
};

type BoardingResponse = {
  funding_txid: string;
  vtxos: Vtxo[];
};

type Flow = "onboard" | "offboard";

// Custom hook for parsing boarding results
const useParsedBoardingResult = (boardResult?: BoardResult, boardAllResult?: BoardResult) => {
  const [parsedData, setParsedData] = useState<BoardingResponse | null>(null);

  useEffect(() => {
    const result = boardResult || boardAllResult;

    log.i("BoardingResult", [result]);
    if (result) {
      const parsed: BoardingResponse = {
        funding_txid: result.funding_txid,
        vtxos: result.vtxos as unknown as Vtxo[],
      };
      log.i("Boarding result ParsedData", [parsed]);
      setParsedData(parsed);
    }
  }, [boardResult, boardAllResult]);

  return { parsedData, setParsedData };
};

// Balance display component
const BalanceDisplay = ({
  title,
  amount,
  pendingAmount,
  isLoading,
  compact = false,
}: {
  title: string;
  amount: number;
  pendingAmount?: number;
  isLoading: boolean;
  compact?: boolean;
}) => (
  <View className={compact ? "mb-5" : "mb-8"}>
    <Text className={compact ? "text-sm text-muted-foreground" : "text-lg text-muted-foreground"}>
      {title}
    </Text>
    {isLoading ? (
      <NoahActivityIndicator className="mt-2" />
    ) : (
      <>
        <Text className={cn("font-bold text-foreground mt-1", compact ? "text-2xl" : "text-3xl")}>
          {formatBip177(amount)}
        </Text>
        {pendingAmount !== undefined && pendingAmount > 0 && (
          <Text
            className={
              compact ? "text-sm text-muted-foreground mt-1" : "text-xl text-muted-foreground mt-1"
            }
          >
            {formatBip177(pendingAmount)} pending
          </Text>
        )}
      </>
    )}
  </View>
);

// Flow toggle component
const FlowToggle = ({ flow, onFlowChange }: { flow: Flow; onFlowChange: (flow: Flow) => void }) => (
  <View className="flex flex-row justify-around rounded-lg bg-muted p-1 mb-6">
    <Pressable
      onPress={() => onFlowChange("onboard")}
      className={cn(
        "flex-1 items-center justify-center rounded-md p-2",
        flow === "onboard" && "bg-background",
      )}
    >
      <Text
        className={cn(
          "font-bold",
          flow === "onboard" ? "text-foreground" : "text-muted-foreground",
        )}
      >
        Board the Ark
      </Text>
    </Pressable>
    <Pressable
      onPress={() => onFlowChange("offboard")}
      className={cn(
        "flex-1 items-center justify-center rounded-md p-2",
        flow === "offboard" && "bg-background",
      )}
    >
      <Text
        className={cn(
          "font-bold",
          flow === "offboard" ? "text-foreground" : "text-muted-foreground",
        )}
      >
        Offboard Ark
      </Text>
    </Pressable>
  </View>
);

// Onboard input form component
const OnboardForm = ({
  amount,
  setAmount,
  onchainBalance,
  setIsMaxAmount,
}: {
  amount: string;
  setAmount: (amount: string) => void;
  onchainBalance: number;
  setIsMaxAmount: (isMax: boolean) => void;
}) => (
  <View className="mb-4">
    <Text className="text-lg text-muted-foreground mb-2">Amount to Board</Text>
    <View className="flex-row items-center">
      <Input
        value={amount}
        onChangeText={(text) => {
          setAmount(text);
          setIsMaxAmount(false);
        }}
        placeholder="Enter amount in sats"
        keyboardType="numeric"
        className="flex-1 border-border bg-card p-4 rounded-lg text-foreground"
      />
      <Button
        variant="outline"
        onPress={() => {
          setAmount(String(onchainBalance));
          setIsMaxAmount(true);
        }}
        className="ml-2"
      >
        <Text>Max</Text>
      </Button>
    </View>
  </View>
);

const BoardFeeEstimateSummary = ({
  result,
  isLoading,
  error,
  isWaitingForDebounce,
}: {
  result?: BoardArkFeeEstimateResult;
  isLoading: boolean;
  error: Error | null;
  isWaitingForDebounce: boolean;
}) => {
  if (!result && !isLoading && !error && !isWaitingForDebounce) {
    return null;
  }

  const estimate = result?.kind === "estimate" ? result.estimate : null;
  const unavailable = result?.kind === "unavailable" ? result.unavailable : null;

  return (
    <FeeEstimateBox
      title="Rough fee estimate"
      isLoading={isLoading || isWaitingForDebounce}
      compact
    >
      {estimate ? (
        <>
          {estimate.is_below_minimum_board_amount ? (
            <Text className="mb-2 text-xs leading-5 text-muted-foreground">
              This estimate is below Ark's minimum board amount of{" "}
              {formatBip177(estimate.minimum_board_amount_sat)}. The final amount will be
              calculated when you board.
            </Text>
          ) : estimate.is_max_amount ? (
            <Text className="mb-2 text-xs leading-5 text-muted-foreground">
              Max estimate leaves room for the onchain transaction fee.
            </Text>
          ) : null}
          <FeeEstimateRow
            label="Amount to board"
            value={formatBip177(estimate.gross_amount_sat)}
            compact
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Ark boarding fee"
            value={formatBip177(estimate.fee_sat)}
            compact
            valueClassName="text-red-500"
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Estimated onchain fee"
            value={formatBip177(estimate.estimated_onchain_fee_sat)}
            compact
            valueClassName="text-red-500"
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Stays in onchain wallet"
            value={formatBip177(estimate.estimated_remaining_onchain_sat)}
            compact
            valueClassName={
              estimate.estimated_remaining_onchain_sat < 0 ? "text-red-500" : undefined
            }
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Ark amount after fee"
            value={formatBip177(estimate.net_amount_sat)}
            compact
            valueClassName="text-green-500"
          />
          <Text className="mt-2 text-xs leading-5 text-muted-foreground">
            Onchain fee uses the regular fee rate and a {estimate.estimated_vbytes} vB 2-in/2-out
            SegWit estimate.
          </Text>
        </>
      ) : unavailable ? (
        <>
          <Text className="text-sm leading-5 text-muted-foreground">
            {unavailable.is_max_amount
              ? `This Max estimate is below Ark's minimum board amount of ${formatBip177(unavailable.minimum_board_amount_sat)}. The final amount will be calculated when you board.`
              : `This estimate is below Ark's minimum board amount of ${formatBip177(unavailable.minimum_board_amount_sat)}. The final amount will be calculated when you board.`}
          </Text>
          <FeeEstimateSeparator className="mt-2" />
          <FeeEstimateRow
            label="Amount to board"
            value={formatBip177(unavailable.boardable_amount_sat)}
            compact
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Ark boarding fee"
            value="Not estimated"
            compact
            valueClassName="text-muted-foreground"
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Estimated onchain fee"
            value={formatBip177(unavailable.estimated_onchain_fee_sat)}
            compact
            valueClassName="text-red-500"
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Stays in onchain wallet"
            value={formatBip177(unavailable.estimated_remaining_onchain_sat)}
            compact
            valueClassName={
              unavailable.estimated_remaining_onchain_sat < 0 ? "text-red-500" : undefined
            }
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Ark amount after fee"
            value="Not estimated"
            compact
            valueClassName="text-muted-foreground"
          />
          <FeeEstimateSeparator />
          <FeeEstimateRow
            label="Minimum board amount"
            value={formatBip177(unavailable.minimum_board_amount_sat)}
            compact
          />
          <Text className="mt-2 text-xs leading-5 text-muted-foreground">
            Onchain fee uses the regular fee rate and a {unavailable.estimated_vbytes} vB 2-in/2-out
            SegWit estimate.
          </Text>
        </>
      ) : error ? (
        <Text className="text-sm leading-5 text-muted-foreground">
          Fee estimate unavailable. The final fee will be calculated when you board.
        </Text>
      ) : (
        <Text className="text-sm text-muted-foreground">Estimating fees...</Text>
      )}
    </FeeEstimateBox>
  );
};

const MinimumBoardAmountSummary = ({ minimumBoardAmountSat }: { minimumBoardAmountSat: number }) => (
  <FeeEstimateBox title="Minimum board amount" compact>
    <Text className="text-sm leading-5 text-muted-foreground">
      Enter at least {formatBip177(minimumBoardAmountSat)} to board to Ark.
    </Text>
    <FeeEstimateSeparator className="mt-2" />
    <FeeEstimateRow
      label="Minimum board amount"
      value={formatBip177(minimumBoardAmountSat)}
      compact
    />
  </FeeEstimateBox>
);

// Transaction result component
const TransactionResult = ({
  parsedData,
  flow,
  onCopyTxid,
}: {
  parsedData: BoardingResponse;
  flow: Flow;
  onCopyTxid: (txid: string) => void;
}) => (
  <View className="mt-8 space-y-4">
    <Card>
      <CardHeader>
        <CardTitle className="text-lg text-green-500">
          {flow === "onboard" ? "Boarding" : "Offboarding"} Transaction Sent!
        </CardTitle>
        <CardDescription>Funding TXID</CardDescription>
      </CardHeader>
      <CardContent>
        <Pressable onPress={() => onCopyTxid(parsedData.funding_txid)}>
          <Text
            className="text-base text-primary break-words"
            numberOfLines={1}
            ellipsizeMode="middle"
          >
            {parsedData.funding_txid}
          </Text>
        </Pressable>
      </CardContent>
    </Card>
  </View>
);

// Offboarding result component
const OffboardingResult = ({
  txid,
  onCopyTxid,
}: {
  txid: string;
  onCopyTxid: (txid: string) => void;
}) => (
  <View className="mt-8 space-y-4">
    <Card>
      <CardHeader>
        <CardTitle className="text-lg text-green-500">Offboarding Transaction Sent!</CardTitle>
        <CardDescription>Transaction ID</CardDescription>
      </CardHeader>
      <CardContent>
        <Pressable onPress={() => onCopyTxid(txid)}>
          <Text
            className="text-base text-primary break-words"
            numberOfLines={1}
            ellipsizeMode="middle"
          >
            {txid}
          </Text>
        </Pressable>
      </CardContent>
    </Card>
  </View>
);

// Error display component
const ErrorDisplay = ({ errorMessage }: { errorMessage: string }) => (
  <Card className="mt-8 bg-destructive">
    <CardHeader>
      <CardTitle className="text-destructive-foreground">Error</CardTitle>
    </CardHeader>
    <CardContent>
      <Text className="text-base text-center text-destructive-foreground">{errorMessage}</Text>
    </CardContent>
  </Card>
);

const BoardArkScreen = () => {
  const { showAlert } = useAlert();
  const navigation = useNavigation<NativeStackNavigationProp<HomeStackParamList>>();
  const iconColor = useIconColor();
  const isAutoBoardingEnabled = useTransactionStore((state) => state.isAutoBoardingEnabled);
  const { data: balance, isLoading: isBalanceLoading } = useBalance();
  const {
    mutate: boardArk,
    isPending: isBoarding,
    data: boardResult,
    error: boardError,
    reset: resetBoardArk,
  } = useBoardArk();
  const {
    mutate: boardAllArk,
    isPending: isBoardingAll,
    data: boardAllResult,
    error: boardAllError,
    reset: resetBoardAllArk,
  } = useBoardAllAmountArk();
  const {
    mutate: offboardAll,
    isPending: isOffboarding,
    data: offboardResult,
    error: offboardError,
    reset: resetOffboardAll,
  } = useOffboardAllArk();

  const [flow, setFlow] = useState<Flow>("onboard");
  const [amount, setAmount] = useState("");
  const [isMaxAmount, setIsMaxAmount] = useState(false);
  const [address, setAddress] = useState("");
  const { data: arkInfo } = useArkInfo(flow === "onboard");

  const onchainBalance = balance?.onchain.confirmed ?? 0;
  const onchainPendingBalance =
    (balance?.onchain.immature ?? 0) +
    (balance?.onchain.trusted_pending ?? 0) +
    (balance?.onchain.untrusted_pending ?? 0);

  const offchainBalance = balance?.offchain.spendable ?? 0;
  const offchainPendingBalance =
    (balance?.offchain.pending_lightning_send ?? 0) +
    (balance?.offchain.pending_in_round ?? 0) +
    (balance?.offchain.pending_exit ?? 0);

  const boardAmountSat = useMemo(() => {
    const trimmedAmount = amount.trim();
    if (!/^\d+$/.test(trimmedAmount)) {
      return null;
    }

    const parsedAmount = Number(trimmedAmount);
    return Number.isSafeInteger(parsedAmount) && parsedAmount > 0 ? parsedAmount : null;
  }, [amount]);
  const isBoardAmountBelowMinimum =
    flow === "onboard" &&
    boardAmountSat !== null &&
    !!arkInfo &&
    boardAmountSat < arkInfo.min_board_amount;

  const validOffboardEstimateAddress = useMemo(() => {
    if (flow !== "offboard") {
      return null;
    }

    const trimmedAddress = address.trim();
    if (!trimmedAddress) {
      return null;
    }

    const btcValidation = validateBitcoinAddress(trimmedAddress);
    if (!btcValidation.valid || !isNetworkMatch(btcValidation.network, "onchain")) {
      return null;
    }

    return trimmedAddress;
  }, [address, flow]);

  const [debouncedOffboardEstimateAddress, setDebouncedOffboardEstimateAddress] = useState<
    string | null
  >(null);
  const [debouncedBoardEstimateParams, setDebouncedBoardEstimateParams] = useState<{
    amountSat: number;
    confirmedOnchainBalanceSat: number;
    isMaxAmount: boolean;
    minimumBoardAmountSat: number;
  } | null>(null);

  const boardEstimateParams = useMemo(() => {
    if (flow !== "onboard" || boardAmountSat === null || onchainBalance <= 0 || !arkInfo) {
      return null;
    }

    if (boardAmountSat < arkInfo.min_board_amount) {
      return null;
    }

    return {
      amountSat: boardAmountSat,
      confirmedOnchainBalanceSat: onchainBalance,
      isMaxAmount,
      minimumBoardAmountSat: arkInfo.min_board_amount,
    };
  }, [arkInfo, boardAmountSat, flow, isMaxAmount, onchainBalance]);

  useEffect(() => {
    if (!boardEstimateParams) {
      setDebouncedBoardEstimateParams(null);
      return;
    }

    const timeout = setTimeout(() => {
      setDebouncedBoardEstimateParams(boardEstimateParams);
    }, 350);

    return () => {
      clearTimeout(timeout);
    };
  }, [boardEstimateParams]);

  useEffect(() => {
    if (!validOffboardEstimateAddress) {
      setDebouncedOffboardEstimateAddress(null);
      return;
    }

    const timeout = setTimeout(() => {
      setDebouncedOffboardEstimateAddress(validOffboardEstimateAddress);
    }, 350);

    return () => {
      clearTimeout(timeout);
    };
  }, [validOffboardEstimateAddress]);

  const boardFeeEstimateQuery = useBoardArkFeeEstimate(debouncedBoardEstimateParams);
  const offboardFeeEstimateQuery = useOffboardAllFeeEstimate(debouncedOffboardEstimateAddress);
  const isCurrentBoardEstimate =
    !!boardEstimateParams && debouncedBoardEstimateParams === boardEstimateParams;
  const currentBoardEstimateResult = isCurrentBoardEstimate
    ? boardFeeEstimateQuery.data
    : undefined;

  useEffect(() => {
    if (!boardFeeEstimateQuery.error) {
      return;
    }

    log.w("Failed to estimate boarding fee", [boardFeeEstimateQuery.error]);
  }, [boardFeeEstimateQuery.error]);

  useEffect(() => {
    if (!offboardFeeEstimateQuery.error) {
      return;
    }

    log.w("Failed to estimate offboarding fee", [offboardFeeEstimateQuery.error]);
  }, [offboardFeeEstimateQuery.error]);

  // Use custom hook for parsing results
  const { parsedData, setParsedData } = useParsedBoardingResult(boardResult, boardAllResult);

  const handlePress = () => {
    Keyboard.dismiss();
    if (flow === "onboard") {
      handleBoard();
    } else {
      handleOffboard();
    }
  };

  const handleOffboard = () => {
    const btcValidation = validateBitcoinAddress(address);

    if (!address || !btcValidation.valid) {
      showAlert({
        title: "Invalid Address",
        description: "Please enter a valid Bitcoin address.",
      });
      return;
    }

    if (!isNetworkMatch(btcValidation.network, "onchain")) {
      showAlert({
        title: "Network Mismatch",
        description: `Please enter a ${APP_VARIANT} address. Detected ${btcValidation.network} address.`,
      });
      return;
    }

    offboardAll(address);
  };

  const handleClearForm = () => {
    Keyboard.dismiss();

    if (flow === "onboard") {
      setAmount("");
      setIsMaxAmount(false);
      setParsedData(null);
      resetBoardArk();
      resetBoardAllArk();
      return;
    }

    setAddress("");
    resetOffboardAll();
  };

  const handleBoard = () => {
    const amountSat = parseInt(amount, 10);
    if (isNaN(amountSat) || amountSat <= 0) {
      showAlert({
        title: "Invalid Amount",
        description: "Please enter a valid amount to board.",
      });
      return;
    }
    if (arkInfo && amountSat < arkInfo.min_board_amount) {
      showAlert({
        title: "Amount Too Low",
        description: `Enter at least ${formatBip177(arkInfo.min_board_amount)} to board to Ark.`,
      });
      return;
    }
    if (isMaxAmount) {
      setParsedData(null);
      boardAllArk();
      return;
    }
    if (amountSat > onchainBalance) {
      showAlert({
        title: "Insufficient Funds",
        description: "The amount exceeds your on-chain balance.",
      });
      return;
    }
    setParsedData(null);
    boardArk(amountSat);
  };

  const handleCopyToClipboard = async (value: string) => {
    await copyToClipboard(value, {
      onCopy: () => {
        showAlert({ title: "Copied!", description: "TXID copied to clipboard." });
      },
    });
  };

  const errorMessage =
    (boardError instanceof Error ? boardError.message : String(boardError ?? "")) ||
    (boardAllError instanceof Error ? boardAllError.message : String(boardAllError ?? "")) ||
    (offboardError instanceof Error ? offboardError.message : String(offboardError ?? ""));
  const isActionLoading = isBoarding || isBoardingAll || isOffboarding;
  const hasClearableInput = flow === "onboard" ? amount.length > 0 : address.length > 0;
  const isPrimaryActionDisabled =
    isActionLoading ||
    isBoardAmountBelowMinimum ||
    (flow === "onboard" && (!amount || onchainBalance === 0)) ||
    (flow === "offboard" && (offchainBalance === 0 || !address));

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <KeyboardAvoidingView behavior="padding" className="flex-1">
        <TouchableWithoutFeedback onPress={Keyboard.dismiss} accessible={false}>
          <ScrollView
            className="p-4"
            contentContainerStyle={{ flexGrow: 1, paddingBottom: 100 }}
            keyboardShouldPersistTaps="handled"
          >
            {/* Header */}
            <View className="flex-row items-center justify-between mb-6">
              <View className="flex-row items-center">
                <Pressable onPress={() => navigation.goBack()} className="mr-4">
                  <Icon name="arrow-back-outline" size={24} color={iconColor} />
                </Pressable>
                <Text className="text-2xl font-bold text-foreground">
                  {flow === "onboard" ? "Board Ark" : "Offboard Ark"}
                </Text>
              </View>
              <Pressable
                onPress={() => navigation.navigate("BoardingTransactions")}
                className="p-2"
              >
                <Icon name="time-outline" size={24} color={iconColor} />
              </Pressable>
            </View>

            {/* Flow Toggle */}
            <FlowToggle flow={flow} onFlowChange={setFlow} />

            {/* Description and Form */}
            {flow === "onboard" ? (
              <>
                <Text className="text-muted-foreground text-center mb-8">
                  Swap your onchain bitcoin and enter the Ark network for fast, cheap offchain
                  transactions.
                </Text>
                <BalanceDisplay
                  title="Confirmed On-chain Balance"
                  amount={onchainBalance}
                  pendingAmount={onchainPendingBalance}
                  isLoading={isBalanceLoading}
                />
                <OnboardForm
                  amount={amount}
                  setAmount={setAmount}
                  onchainBalance={onchainBalance}
                  setIsMaxAmount={setIsMaxAmount}
                />
                {isBoardAmountBelowMinimum && arkInfo ? (
                  <MinimumBoardAmountSummary minimumBoardAmountSat={arkInfo.min_board_amount} />
                ) : boardEstimateParams ? (
                  <BoardFeeEstimateSummary
                    result={currentBoardEstimateResult}
                    isLoading={boardFeeEstimateQuery.isFetching}
                    error={boardFeeEstimateQuery.error}
                    isWaitingForDebounce={debouncedBoardEstimateParams !== boardEstimateParams}
                  />
                ) : null}
              </>
            ) : (
              <>
                <Text className="text-muted-foreground text-center mb-5">
                  Exit Ark and send your off-chain balance to an on-chain Bitcoin address.
                </Text>
                <BalanceDisplay
                  title="Confirmed Off-chain Balance"
                  amount={offchainBalance}
                  pendingAmount={offchainPendingBalance}
                  isLoading={isBalanceLoading}
                  compact
                />
                <View className="mb-2">
                  <Text className="mb-2 text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
                    Destination
                  </Text>
                  <View className="rounded-2xl border border-border bg-card px-4 py-3">
                    <Input
                      value={address}
                      onChangeText={setAddress}
                      placeholder="Enter Bitcoin address"
                      className="border-0 bg-transparent p-0 text-foreground"
                      autoCapitalize="none"
                      autoCorrect={false}
                    />
                  </View>

                  {isAutoBoardingEnabled ? (
                    <View className="mt-3 flex-row items-start rounded-2xl border border-amber-500/30 bg-amber-500/10 px-3 py-2">
                      <Icon
                        name="alert-circle-outline"
                        size={17}
                        color="#d97706"
                        style={{ marginTop: 1, marginRight: 8 }}
                      />
                      <View className="flex-1">
                        <Text className="text-sm font-semibold text-amber-700 dark:text-amber-300">
                          Use an external address
                        </Text>
                        <Text className="mt-0.5 text-xs leading-4 text-amber-700/90 dark:text-amber-200/90">
                          Sending to your Noah wallet address can board the funds back into Ark.
                        </Text>
                      </View>
                    </View>
                  ) : null}
                  {validOffboardEstimateAddress ? (
                    <FeeEstimateSummary
                      estimate={offboardFeeEstimateQuery.data}
                      isLoading={
                        offboardFeeEstimateQuery.isFetching ||
                        debouncedOffboardEstimateAddress !== validOffboardEstimateAddress
                      }
                      error={offboardFeeEstimateQuery.error}
                      netLabel="You receive"
                      feeLabel="Estimated fee"
                      grossLabel="Total offboarded"
                      compact
                      feeValueClassName="text-red-500"
                      rowOrder={["gross", "fee", "net"]}
                      unavailableText="Fee estimate unavailable. The final fee will be calculated when you offboard."
                    />
                  ) : null}
                </View>
              </>
            )}

            {/* Action Button */}
            <View
              className={`flex-row items-center gap-3 ${flow === "offboard" ? "mt-5" : "mt-8"}`}
            >
              <Button
                onPress={handleClearForm}
                variant="outline"
                disabled={isActionLoading || !hasClearableInput}
                className="flex-1 rounded-2xl py-3"
              >
                <Text className="font-semibold">Cancel</Text>
              </Button>
              <NoahButton
                onPress={handlePress}
                isLoading={isActionLoading}
                disabled={isPrimaryActionDisabled}
                className="flex-1 rounded-2xl py-3"
              >
                {flow === "onboard" ? "Board Ark" : "Offboard Ark"}
              </NoahButton>
            </View>

            {/* Boarding Transaction Result */}
            {parsedData && flow === "onboard" && (
              <TransactionResult
                parsedData={parsedData}
                flow={flow}
                onCopyTxid={handleCopyToClipboard}
              />
            )}

            {/* Offboarding Result */}
            {offboardResult && (
              <OffboardingResult txid={offboardResult} onCopyTxid={handleCopyToClipboard} />
            )}

            {/* Error Display */}
            {(boardError || boardAllError || offboardError) && (
              <ErrorDisplay errorMessage={errorMessage} />
            )}
          </ScrollView>
        </TouchableWithoutFeedback>
      </KeyboardAvoidingView>
    </NoahSafeAreaView>
  );
};

export default BoardArkScreen;
