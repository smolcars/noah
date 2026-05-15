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
import { useBalance } from "../hooks/useWallet";
import {
  useBoardAllAmountArk,
  useBoardArk,
  useOffboardAllArk,
  useOffboardAllFeeEstimate,
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
}: {
  title: string;
  amount: number;
  pendingAmount?: number;
  isLoading: boolean;
}) => (
  <View className="mb-8">
    <Text className="text-lg text-muted-foreground">{title}</Text>
    {isLoading ? (
      <NoahActivityIndicator className="mt-2" />
    ) : (
      <>
        <Text className="text-3xl font-bold text-foreground mt-1">{formatBip177(amount)}</Text>
        {pendingAmount !== undefined && pendingAmount > 0 && (
          <Text className="text-xl text-muted-foreground mt-1">
            {formatBip177(pendingAmount)} pending
          </Text>
        )}
      </>
    )}
  </View>
);

// Flow toggle component
const FlowToggle = ({ flow, onFlowChange }: { flow: Flow; onFlowChange: (flow: Flow) => void }) => (
  <View className="flex flex-row justify-around rounded-lg bg-muted p-1 mb-8">
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
  } = useBoardArk();
  const {
    mutate: boardAllArk,
    isPending: isBoardingAll,
    data: boardAllResult,
    error: boardAllError,
  } = useBoardAllAmountArk();
  const {
    mutate: offboardAll,
    isPending: isOffboarding,
    data: offboardResult,
    error: offboardError,
  } = useOffboardAllArk();

  const [flow, setFlow] = useState<Flow>("onboard");
  const [amount, setAmount] = useState("");
  const [isMaxAmount, setIsMaxAmount] = useState(false);
  const [address, setAddress] = useState("");

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

  const offboardFeeEstimateQuery = useOffboardAllFeeEstimate(debouncedOffboardEstimateAddress);

  useEffect(() => {
    if (!offboardFeeEstimateQuery.error) {
      return;
    }

    log.w("Failed to estimate offboarding fee", [offboardFeeEstimateQuery.error]);
  }, [offboardFeeEstimateQuery.error]);

  // Use custom hook for parsing results
  const { parsedData, setParsedData } = useParsedBoardingResult(boardResult, boardAllResult);

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

  const handleBoard = () => {
    if (isMaxAmount) {
      setParsedData(null);
      boardAllArk();
      return;
    }
    const amountSat = parseInt(amount, 10);
    if (isNaN(amountSat) || amountSat <= 0) {
      showAlert({
        title: "Invalid Amount",
        description: "Please enter a valid amount to board.",
      });
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
            <View className="flex-row items-center justify-between mb-8">
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
                  Swap you onchain bitcoin and enter the Ark network for fast, cheap offchain
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
              </>
            ) : (
              <>
                <Text className="text-muted-foreground text-center mb-8">
                  Exit Ark and send your off-chain balance to an on-chain Bitcoin address.
                </Text>
                <BalanceDisplay
                  title="Confirmed Off-chain Balance"
                  amount={offchainBalance}
                  pendingAmount={offchainPendingBalance}
                  isLoading={isBalanceLoading}
                />
                <View className="mb-4">
                  {isAutoBoardingEnabled ? (
                    <Text className="text-lg text-amber-600 dark:text-amber-400 mb-2">
                      Important: Please only input an external address like your cold storage
                      wallet, DO NOT use Noah wallet address, if you do, you will be boarding into
                      Ark again.
                    </Text>
                  ) : null}

                  <Input
                    value={address}
                    onChangeText={setAddress}
                    placeholder="Enter Bitcoin address"
                    className="border-border bg-card p-4 rounded-lg text-foreground"
                    autoCapitalize="none"
                    autoCorrect={false}
                  />
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
                      unavailableText="Fee estimate unavailable. The final fee will be calculated when you offboard."
                    />
                  ) : null}
                </View>
              </>
            )}

            {/* Action Button */}
            <NoahButton
              onPress={handlePress}
              isLoading={isBoarding || isBoardingAll || isOffboarding}
              disabled={
                isBoarding ||
                isBoardingAll ||
                isOffboarding ||
                (flow === "onboard" && (!amount || onchainBalance === 0)) ||
                (flow === "offboard" && (offchainBalance === 0 || !address))
              }
              className="mt-8"
            >
              {flow === "onboard" ? "Board Ark" : "Offboard Ark"}
            </NoahButton>

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
