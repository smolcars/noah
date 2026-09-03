import { useState, useEffect, useMemo } from "react";
import { Keyboard } from "react-native";
import { useRoute } from "@react-navigation/native";
import type { RouteProp } from "@react-navigation/native";
import { useAlert } from "~/contexts/AlertProvider";
import {
  parseDestination,
  isValidDestination,
  normalizeLightningAddress,
  normalizeLightningAddressDestination,
  type DestinationTypes,
  ParsedBip321,
} from "../lib/sendUtils";
import {
  useLightningAddressPaymentRoute,
  useIsOnchainAddressMine,
  useSend,
  useSendFeeEstimate,
  type SendFeeEstimateParams,
} from "./usePayments";
import {
  type OnchainWalletFeeEstimate,
  type OnchainSendSource,
  type PaymentResult,
} from "../lib/paymentsApi";
import { useQRCodeScanner } from "~/hooks/useQRCodeScanner";
import { useBtcToFiatRate } from "./useMarketData";
import { useBalance } from "./useWallet";
import { useLightningAddressSuggestions } from "./useLightningAddressSuggestions";
import { formatBitcoinAmount } from "~/lib/bitcoinAmount";
import { fiatToSats, satsToFiat } from "~/lib/fiatCurrency";
import {
  getRepeatPaymentPrefill,
  shouldUseArkDirectLightningAddressRoute,
} from "~/lib/repeatPayment";
import { getMaxSendBalanceSat } from "~/lib/onchainSend";
import {
  getBip321MethodForRail,
  getBip321Rails,
  getDestinationRails,
  getNextSendStage,
  getRecommendedRail,
  isMaxCompatibleDestination,
  type SendEntry,
  type SendRail,
  type SendStage,
} from "~/lib/sendFlow";
import { useProfileStore } from "~/store/profileStore";
import logger from "~/lib/log";
import type { TabParamList } from "~/Navigators";

const log = logger("useSendScreen");
const INVALID_DESTINATION_MESSAGE =
  "Enter a valid Bitcoin address, Lightning invoice, Lightning offer, Lightning address, or Ark address.";

type DisplayResult = {
  amount_sat: number;
  destination: string;
  txid?: string;
  preimage?: string;
  success: boolean;
  type: string;
};

type SendScreenRouteProp = RouteProp<TabParamList, "Send">;

const formatFeeRate = (feeRateSatVb: number) => {
  if (Number.isInteger(feeRateSatVb)) {
    return feeRateSatVb.toString();
  }

  return feeRateSatVb.toFixed(2).replace(/\.?0+$/, "");
};

const isOnchainWalletFeeEstimate = (estimate: unknown): estimate is OnchainWalletFeeEstimate => {
  if (!estimate || typeof estimate !== "object") {
    return false;
  }

  const maybeEstimate = estimate as Partial<OnchainWalletFeeEstimate>;
  return (
    typeof maybeEstimate.fee_rate_sat_vb === "number" &&
    typeof maybeEstimate.estimated_vbytes === "number"
  );
};

export const useSendScreen = () => {
  const route = useRoute<SendScreenRouteProp>();
  const { showAlert } = useAlert();
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const bitcoinAmountUnit = useProfileStore((state) => state.bitcoinAmountUnit);
  const { data: btcPrice } = useBtcToFiatRate();
  const { data: balance } = useBalance();
  const [destination, setDestination] = useState("");
  const [amount, setAmount] = useState("");
  const [isAmountEditable, setIsAmountEditable] = useState(true);
  const [comment, setComment] = useState("");
  const [parsedResult, setParsedResult] = useState<DisplayResult | null>(null);
  const [destinationType, setDestinationType] = useState<DestinationTypes | null>(null);
  const [currency, setCurrency] = useState<"FIAT" | "SATS">("SATS");
  const [parsedAmount, setParsedAmount] = useState<number | null>(null);
  const [bip321Data, setBip321Data] = useState<ParsedBip321 | null>(null);
  const [selectedPaymentMethod, setSelectedPaymentMethod] = useState<
    "ark" | "lightning" | "onchain" | "offer"
  >("onchain");
  const [selectedOnchainSource, setSelectedOnchainSource] = useState<OnchainSendSource | null>(
    null,
  );
  const [showConfirmation, setShowConfirmation] = useState(false);
  const [showSuccess, setShowSuccess] = useState(false);
  const [destinationRequestRevision, setDestinationRequestRevision] = useState(0);
  const [isMaxSend, setIsMaxSend] = useState(false);
  const [stageHistory, setStageHistory] = useState<SendStage[]>(["amount"]);
  const [stageDirection, setStageDirection] = useState<"back" | "forward">("forward");
  const [amountError, setAmountError] = useState<string | null>(null);
  const [recipientError, setRecipientError] = useState<string | null>(null);
  const [entry, setEntry] = useState<SendEntry>("amount-first");
  const [selectedRail, setSelectedRail] = useState<SendRail>("onchain");
  const [railConfirmed, setRailConfirmed] = useState(false);
  const [sourceConfirmed, setSourceConfirmed] = useState(false);
  const [recipientConfirmed, setRecipientConfirmed] = useState(false);
  const [isEditingRecipient, setIsEditingRecipient] = useState(false);
  const stage = stageHistory.at(-1) ?? "amount";

  const resetSendDraftState = () => {
    setDestination("");
    setAmount("");
    setIsAmountEditable(true);
    setComment("");
    setParsedResult(null);
    setDestinationType(null);
    setParsedAmount(null);
    setBip321Data(null);
    setSelectedPaymentMethod("onchain");
    setSelectedOnchainSource(null);
    setShowConfirmation(false);
    setShowSuccess(false);
    setIsMaxSend(false);
    setStageHistory(["amount"]);
    setStageDirection("forward");
    setAmountError(null);
    setRecipientError(null);
    setEntry("amount-first");
    setSelectedRail("onchain");
    setRailConfirmed(false);
    setSourceConfirmed(false);
    setRecipientConfirmed(false);
    setIsEditingRecipient(false);
  };

  const setEnteredDestination = (nextDestination: string) => {
    if (parsedAmount !== null) {
      setAmount("");
      setParsedAmount(null);
    }
    setRecipientError(null);
    setRecipientConfirmed(false);
    setDestination(nextDestination);
  };

  const showStage = (nextStage: SendStage) => {
    setShowConfirmation(false);
    setStageDirection("forward");
    setStageHistory((history) =>
      history.at(-1) === nextStage ? history : [...history, nextStage],
    );
  };

  const handleStageBack = () => {
    setShowConfirmation(false);
    setStageDirection("back");
    if (stage === "recipient" && !isAmountEditable && !isEditingRecipient) {
      resetSendDraftState();
      setStageDirection("back");
      return;
    }
    if (
      stage === "recipient" &&
      entry === "amount-first" &&
      stageHistory.at(-2) === "amount" &&
      !isEditingRecipient
    ) {
      setDestination("");
      setBip321Data(null);
      setDestinationType(null);
      setComment("");
      setSelectedRail("onchain");
      setSelectedPaymentMethod("onchain");
      setSelectedOnchainSource(null);
      setRailConfirmed(false);
      setSourceConfirmed(false);
      setRecipientError(null);
      setRecipientConfirmed(false);
    }
    if (stage === "method") {
      setRailConfirmed(false);
    }
    if (stage === "source") {
      setSourceConfirmed(false);
      if (entry === "max" && stageHistory.length === 1) {
        setIsMaxSend(false);
        setEntry("amount-first");
        setSelectedOnchainSource(null);
        setRailConfirmed(false);
      }
    }
    if (stage === "recipient") {
      setIsEditingRecipient(false);
    }
    setStageHistory((history) =>
      history.length > 1 ? history.slice(0, -1) : history[0] === "amount" ? history : ["amount"],
    );
  };

  useEffect(() => {
    if (destination) {
      const {
        destinationType: newDestinationType,
        amount: newAmount,
        isAmountEditable: newIsAmountEditable,
        error: parseError,
        bip321,
      } = parseDestination(destination);

      setRecipientError(
        (currentError) => parseError ?? (newDestinationType === null ? currentError : null),
      );

      setDestinationType(newDestinationType);
      if (newAmount) {
        if (!isMaxSend) {
          setCurrency("SATS");
          setAmount(newAmount.toString());
        }
        setParsedAmount(newAmount);
      } else if (parsedAmount) {
        setAmount("");
        setParsedAmount(null);
      }
      setIsAmountEditable(newIsAmountEditable);

      if (newDestinationType === "bip321" && bip321) {
        setBip321Data(bip321);
        const rails = getBip321Rails(bip321);
        const recommendedRail = isMaxSend && bip321.onchainAddress ? "onchain" : rails[0];
        if (recommendedRail) {
          setSelectedRail(recommendedRail);
          const method = getBip321MethodForRail(recommendedRail, bip321);
          if (method) {
            setSelectedPaymentMethod(method);
          }
        }
      } else {
        setBip321Data(null);
        if (newDestinationType === "ark") {
          setSelectedRail("ark");
          setSelectedPaymentMethod("ark");
        } else if (
          newDestinationType === "lightning" ||
          newDestinationType === "offer" ||
          newDestinationType === "lnurl"
        ) {
          setSelectedRail("lightning");
          setSelectedPaymentMethod(newDestinationType === "offer" ? "offer" : "lightning");
        } else if (newDestinationType === "onchain") {
          setSelectedRail("onchain");
          setSelectedPaymentMethod("onchain");
        }
      }
      if (!isMaxSend) {
        setRailConfirmed(false);
        setSourceConfirmed(false);
      }
    } else {
      setDestinationType(null);
      setIsAmountEditable(true);
      setParsedAmount(null);
      setBip321Data(null);
      if (!isMaxSend) {
        setRailConfirmed(false);
        setSourceConfirmed(false);
      }
    }
  }, [destination, destinationRequestRevision, isMaxSend]);

  const finalDestinationType =
    destinationType === "bip321" ? selectedPaymentMethod : destinationType;
  const cleanedDestination = destination.trim().replace(/^(bitcoin:|lightning:)/i, "");
  const normalizedLnurlDestination = normalizeLightningAddress(cleanedDestination);
  const lightningAddressPaymentRouteDestination =
    finalDestinationType === "lnurl" ? normalizedLnurlDestination : null;
  const lightningAddressPaymentRouteQuery = useLightningAddressPaymentRoute(
    lightningAddressPaymentRouteDestination,
  );
  const isResolvingRecipient =
    finalDestinationType === "lnurl" && lightningAddressPaymentRouteQuery.isFetching;
  const selectedLightningAddressPaymentRoute = useMemo(() => {
    const resolvedRoute = lightningAddressPaymentRouteQuery.data;
    if (!resolvedRoute || selectedRail !== "lightning" || resolvedRoute.method !== "ark") {
      return resolvedRoute;
    }

    return {
      method: "lightning" as const,
      minSendableMsat: resolvedRoute.minSendableMsat,
      maxSendableMsat: resolvedRoute.maxSendableMsat,
      commentAllowed: resolvedRoute.commentAllowed,
    };
  }, [lightningAddressPaymentRouteQuery.data, selectedRail]);

  const {
    mutate: send,
    isPending: isSending,
    data: result,
    error,
    reset,
  } = useSend(finalDestinationType);

  useEffect(() => {
    const repeatPayment = route.params?.repeatPayment;
    const requestedDestination = repeatPayment?.destination ?? route.params?.destination;
    if (!requestedDestination) {
      return;
    }

    const repeatPaymentPrefill = repeatPayment
      ? getRepeatPaymentPrefill(repeatPayment, fiatCurrency)
      : undefined;

    reset();
    setAmount(repeatPaymentPrefill?.amountInput ?? "");
    setIsAmountEditable(true);
    setComment(repeatPayment?.comment ?? "");
    setParsedResult(null);
    setDestinationType(null);
    setCurrency(repeatPaymentPrefill?.amountMode ?? "SATS");
    setParsedAmount(null);
    setBip321Data(null);
    setSelectedPaymentMethod("onchain");
    setSelectedRail("onchain");
    setSelectedOnchainSource(null);
    setIsMaxSend(false);
    setRecipientConfirmed(false);
    setIsEditingRecipient(false);
    setEntry("recipient-first");
    setRailConfirmed(false);
    setSourceConfirmed(false);
    setShowConfirmation(false);
    setShowSuccess(false);
    setAmountError(null);
    setRecipientError(null);
    setStageDirection("forward");
    setStageHistory(["recipient"]);
    setDestination(normalizeLightningAddressDestination(requestedDestination));
    setDestinationRequestRevision((revision) => revision + 1);
  }, [fiatCurrency, reset, route.params]);

  const { suggestions: lightningAddressSuggestions } = useLightningAddressSuggestions({
    destination,
    isDestinationFocused: stage === "recipient",
  });

  const amountSat = useMemo(() => {
    if (currency === "SATS") {
      return parseInt(amount, 10) || 0;
    }
    if (btcPrice) {
      return fiatToSats(parseFloat(amount), btcPrice);
    }
    return 0;
  }, [amount, currency, btcPrice]);

  const isOnchainSend = finalDestinationType === "onchain";
  const onchainWalletBalance = balance?.onchain.confirmed ?? 0;
  const offchainWalletBalance = balance?.offchain.spendable ?? 0;

  const paymentRailOptions = useMemo<SendRail[]>(() => {
    if (destinationType === "bip321" && bip321Data) {
      return getBip321Rails(bip321Data);
    }
    if (destinationType === "lnurl") {
      return lightningAddressPaymentRouteQuery.data?.method === "ark" && !comment.trim()
        ? ["ark", "lightning"]
        : ["lightning"];
    }
    if (destinationType === "ark") {
      return ["ark"];
    }
    if (destinationType === "lightning" || destinationType === "offer") {
      return ["lightning"];
    }
    if (destinationType === "onchain") {
      return ["onchain"];
    }
    return [];
  }, [bip321Data, comment, destinationType, lightningAddressPaymentRouteQuery.data?.method]);

  const railAvailability = useMemo<Record<SendRail, boolean>>(
    () => ({
      ark: amountSat > 0 && offchainWalletBalance >= amountSat,
      lightning: amountSat > 0 && offchainWalletBalance >= amountSat,
      onchain:
        isMaxSend ||
        (amountSat > 0 &&
          (offchainWalletBalance >= amountSat || onchainWalletBalance >= amountSat)),
    }),
    [amountSat, isMaxSend, offchainWalletBalance, onchainWalletBalance],
  );

  useEffect(() => {
    if (railConfirmed || isMaxSend || paymentRailOptions.length === 0) {
      return;
    }

    const recommendedRail = getRecommendedRail(paymentRailOptions, railAvailability);
    if (!recommendedRail) {
      return;
    }

    setSelectedRail(recommendedRail);
    if (destinationType === "bip321" && bip321Data) {
      const method = getBip321MethodForRail(recommendedRail, bip321Data);
      if (method) {
        setSelectedPaymentMethod(method);
      }
    }
  }, [bip321Data, destinationType, isMaxSend, paymentRailOptions, railAvailability, railConfirmed]);

  const onchainSourceOptions = useMemo<OnchainSendSource[]>(() => {
    if ((!isOnchainSend && !isMaxSend) || !balance) {
      return [];
    }

    const options: OnchainSendSource[] = [];
    if (
      isMaxSend ? offchainWalletBalance > 0 : amountSat > 0 && offchainWalletBalance >= amountSat
    ) {
      options.push("offchain");
    }
    if (isMaxSend ? onchainWalletBalance > 0 : amountSat > 0 && onchainWalletBalance >= amountSat) {
      options.push("onchain");
    }
    return options;
  }, [amountSat, balance, isMaxSend, isOnchainSend, offchainWalletBalance, onchainWalletBalance]);

  useEffect(() => {
    if ((!isOnchainSend && !isMaxSend) || (!isMaxSend && amountSat <= 0)) {
      setSelectedOnchainSource(null);
      return;
    }

    if (selectedOnchainSource !== null && !onchainSourceOptions.includes(selectedOnchainSource)) {
      setSelectedOnchainSource(null);
    }
  }, [amountSat, isMaxSend, isOnchainSend, onchainSourceOptions, selectedOnchainSource]);

  const resolvedOnchainSource =
    selectedOnchainSource ?? (onchainSourceOptions.length === 1 ? onchainSourceOptions[0] : null);

  const isOnchainSourceSelectionRequired =
    isOnchainSend && onchainSourceOptions.length > 1 && resolvedOnchainSource === null;

  const resolvedOnchainDestination = !isOnchainSend
    ? null
    : destinationType === "bip321"
      ? (bip321Data?.onchainAddress ?? null)
      : cleanedDestination || null;
  const ownOnchainAddressQuery = useIsOnchainAddressMine(
    showConfirmation && isMaxSend && resolvedOnchainSource === "offchain"
      ? resolvedOnchainDestination
      : null,
  );

  const feeEstimateParams = useMemo<SendFeeEstimateParams | null>(() => {
    if (!showConfirmation) {
      return null;
    }

    if (!isMaxSend && (!amountSat || amountSat <= 0)) {
      return null;
    }

    if (destinationType === "bip321" && bip321Data) {
      switch (selectedPaymentMethod) {
        case "ark":
          return bip321Data.arkAddress ? { method: "ark", amountSat } : null;
        case "lightning":
          return bip321Data.lightningInvoice ? { method: "lightning", amountSat } : null;
        case "offer":
          return bip321Data.offer ? { method: "lightning", amountSat } : null;
        case "onchain":
          if (isMaxSend && resolvedOnchainSource === "onchain") {
            return null;
          }

          return bip321Data.onchainAddress && resolvedOnchainSource !== null
            ? {
                method: "onchain",
                source: resolvedOnchainSource,
                destination: bip321Data.onchainAddress,
                amountSat:
                  isMaxSend && resolvedOnchainSource === "offchain"
                    ? offchainWalletBalance
                    : amountSat,
                isMaxAmount: isMaxSend,
              }
            : null;
      }
    }

    switch (finalDestinationType) {
      case "ark":
        return { method: "ark", amountSat };
      case "lightning":
      case "offer":
        return { method: "lightning", amountSat };
      case "lnurl": {
        const route = selectedLightningAddressPaymentRoute;
        return route
          ? {
              method:
                selectedRail === "ark" &&
                shouldUseArkDirectLightningAddressRoute(route.method, comment || null)
                  ? "ark"
                  : "lightning",
              amountSat,
            }
          : null;
      }
      case "onchain":
        if (isMaxSend && resolvedOnchainSource === "onchain") {
          return null;
        }

        return cleanedDestination && resolvedOnchainSource !== null
          ? {
              method: "onchain",
              source: resolvedOnchainSource,
              destination: cleanedDestination,
              amountSat:
                isMaxSend && resolvedOnchainSource === "offchain"
                  ? offchainWalletBalance
                  : amountSat,
              isMaxAmount: isMaxSend,
            }
          : null;
      default:
        return null;
    }
  }, [
    amountSat,
    bip321Data,
    cleanedDestination,
    comment,
    destinationType,
    finalDestinationType,
    isMaxSend,
    lightningAddressPaymentRouteQuery.data,
    offchainWalletBalance,
    resolvedOnchainSource,
    selectedPaymentMethod,
    selectedLightningAddressPaymentRoute,
    selectedRail,
    showConfirmation,
  ]);

  const feeEstimateQuery = useSendFeeEstimate(feeEstimateParams);
  const isWaitingForFeeEstimate =
    feeEstimateParams !== null && !feeEstimateQuery.data && !feeEstimateQuery.error;

  const feeEstimateNote = useMemo(() => {
    if (isMaxSend && resolvedOnchainSource === "onchain") {
      return "The onchain wallet will send its full confirmed balance. The final miner fee is calculated when the transaction is built.";
    }

    if (!isOnchainSend || resolvedOnchainSource !== "onchain") {
      return null;
    }

    const estimate = feeEstimateQuery.data;
    if (!isOnchainWalletFeeEstimate(estimate)) {
      return null;
    }

    return `Regular fee rate: ${formatFeeRate(estimate.fee_rate_sat_vb)} sat/vB. Estimated as a ${estimate.estimated_vbytes} vB 2-in/2-out SegWit transaction.`;
  }, [feeEstimateQuery.data, isMaxSend, isOnchainSend, resolvedOnchainSource]);

  const feeEstimateWarning = useMemo(() => {
    if (ownOnchainAddressQuery.data) {
      return "Your Ark balance cannot be swept to this wallet's own onchain address. Use an external Bitcoin address.";
    }

    if (isMaxSend) {
      return null;
    }

    if (!isOnchainSend || resolvedOnchainSource === null || !feeEstimateQuery.data) {
      return null;
    }

    const sourceBalance =
      resolvedOnchainSource === "offchain" ? offchainWalletBalance : onchainWalletBalance;
    const estimatedTotal = feeEstimateQuery.data.gross_amount_sat;

    if (estimatedTotal <= sourceBalance) {
      return null;
    }

    const sourceLabel = resolvedOnchainSource === "offchain" ? "Ark" : "onchain";
    return `Estimated total is ${formatBitcoinAmount(estimatedTotal, bitcoinAmountUnit)}, but your ${sourceLabel} balance is ${formatBitcoinAmount(sourceBalance, bitcoinAmountUnit)}. The send may fail if the final fee is not lower.`;
  }, [
    bitcoinAmountUnit,
    feeEstimateQuery.data,
    isMaxSend,
    isOnchainSend,
    offchainWalletBalance,
    onchainWalletBalance,
    ownOnchainAddressQuery.data,
    resolvedOnchainSource,
  ]);

  const confirmationAmountSat = isMaxSend
    ? resolvedOnchainSource === "offchain"
      ? (feeEstimateQuery.data?.net_amount_sat ?? offchainWalletBalance)
      : resolvedOnchainSource === "onchain"
        ? onchainWalletBalance
        : 0
    : amountSat;
  const maxSendBalanceSat = getMaxSendBalanceSat(
    resolvedOnchainSource,
    onchainWalletBalance,
    offchainWalletBalance,
  );

  const setEnteredAmount = (nextAmount: string) => {
    setIsMaxSend(false);
    if (entry === "max") {
      setEntry("amount-first");
      setSelectedOnchainSource(null);
      setSourceConfirmed(false);
    }
    setAmountError(null);
    setAmount(nextAmount);
  };

  const setEnteredComment = (nextComment: string) => {
    setComment(nextComment);
    setRailConfirmed(false);
  };

  const handleMaxSend = () => {
    const preserveRecipient =
      recipientConfirmed &&
      parsedAmount === null &&
      isMaxCompatibleDestination(destinationType, bip321Data);

    setAmount("");
    setCurrency("SATS");
    setParsedAmount(null);
    setIsMaxSend(true);
    setEntry("max");
    if (!preserveRecipient) {
      setDestination("");
      setDestinationType(null);
      setBip321Data(null);
    }
    setComment("");
    setSelectedRail("onchain");
    setSelectedPaymentMethod("onchain");
    setSelectedOnchainSource(null);
    setRailConfirmed(true);
    setSourceConfirmed(false);
    setRecipientConfirmed(preserveRecipient);
    setAmountError(null);
    setRecipientError(null);
    setShowConfirmation(false);
    setStageDirection("forward");
    setStageHistory(["source"]);
  };

  const handleSelectRail = (rail: SendRail) => {
    setSelectedRail(rail);
    setRailConfirmed(false);
    setSourceConfirmed(false);

    if (destinationType === "bip321" && bip321Data) {
      const method = getBip321MethodForRail(rail, bip321Data);
      if (method) {
        setSelectedPaymentMethod(method);
      }
    } else if (rail === "lightning") {
      setSelectedPaymentMethod(destinationType === "offer" ? "offer" : "lightning");
    } else {
      setSelectedPaymentMethod(rail);
    }

    if (rail !== "onchain") {
      setSelectedOnchainSource(null);
    }
  };

  const handleSelectOnchainSource = (source: OnchainSendSource) => {
    setSelectedOnchainSource(source);
    setSourceConfirmed(false);
  };

  useEffect(() => {
    if (!feeEstimateQuery.error) {
      return;
    }

    log.w("Failed to estimate send fee", [feeEstimateQuery.error]);
  }, [feeEstimateQuery.error]);

  useEffect(() => {
    if (!lightningAddressPaymentRouteQuery.error) {
      return;
    }

    log.w("Failed to resolve lightning address payment route", [
      lightningAddressPaymentRouteQuery.error,
    ]);
  }, [lightningAddressPaymentRouteQuery.error]);

  const toggleCurrency = () => {
    if (currency === "SATS") {
      if (btcPrice && amount) {
        setAmount(satsToFiat(parseInt(amount, 10), btcPrice, fiatCurrency));
      }
      setCurrency("FIAT");
    } else {
      if (btcPrice && amount) {
        setAmount(fiatToSats(parseFloat(amount), btcPrice).toString());
      }
      setCurrency("SATS");
    }
  };

  useEffect(() => {
    if (!result) {
      return;
    }

    let displayResult: DisplayResult | null = null;

    const processResult = (res: PaymentResult): DisplayResult => {
      // Check for onchain payment (has txid and destination_address)
      if ("txid" in res && "destination_address" in res) {
        return {
          success: true,
          amount_sat: res.amount_sat,
          destination: res.destination_address,
          txid: res.txid,
          type: res.source === "offchain" ? "On-chain from Ark balance" : "On-chain wallet",
        };
      }

      // Check for arkoor payment (has destination_pubkey)
      if ("destination_pubkey" in res) {
        return {
          success: true,
          amount_sat: res.amount_sat,
          destination: res.destination_pubkey,
          type: "Ark",
        };
      }

      // Check for lightning payment
      if ("payment_hash" in res) {
        if (!res.preimage) {
          log.e("Lightning payment result missing preimage", [res]);
          showAlert({
            title: "Send Failed",
            description: "Lightning payment did not complete. No preimage was returned.",
          });
          return {
            success: false,
            amount_sat: 0,
            destination: "",
            type: "error",
          };
        }

        return {
          success: true,
          amount_sat: res.amount ?? amountSat,
          destination: res.invoice ?? res.payment_hash,
          preimage: res.preimage,
          type: "Lightning",
        };
      }

      // Unknown type
      log.e("Could not process the transaction result. Unknown result type:", [result]);
      showAlert({
        title: "Error",
        description: "Could not process the transaction result. Unknown result type.",
      });
      return {
        success: false,
        amount_sat: 0,
        destination: "",
        type: "error",
      };
    };

    displayResult = processResult(result);

    if (displayResult) {
      if (displayResult.success) {
        setShowConfirmation(false);
        setShowSuccess(true);
      }
      setParsedResult(displayResult);
    }
  }, [result, amountSat, showAlert]);

  const handleSend = () => {
    // Validation
    if (!isValidDestination(destination)) {
      setRecipientError(INVALID_DESTINATION_MESSAGE);
      showStage("recipient");
      return;
    }
    if (!isMaxSend && (isNaN(amountSat) || amountSat <= 0)) {
      setAmountError("Enter an amount greater than zero.");
      showStage("amount");
      return;
    }
    if (isOnchainSend) {
      if (!balance) {
        showAlert({
          title: "Balance Unavailable",
          description: "Unable to check wallet balances. Please try again.",
        });
        return;
      }
      if (onchainSourceOptions.length === 0) {
        showAlert({
          title: "Insufficient Funds",
          description: isMaxSend
            ? "Neither your Ark balance nor onchain wallet has confirmed funds to send."
            : "Neither your Ark balance nor onchain wallet can cover this payment.",
        });
        return;
      }
    }

    // Show confirmation instead of sending immediately
    setShowConfirmation(true);
  };

  const continueToStage = (nextStage: SendStage) => {
    if (nextStage === "review") {
      handleSend();
      return;
    }

    showStage(nextStage);
  };

  const getNextStage = ({
    amountConfirmed,
    nextRecipientConfirmed = recipientConfirmed,
    nextRailConfirmed = railConfirmed,
    nextSourceConfirmed = sourceConfirmed,
  }: {
    amountConfirmed: boolean;
    nextRecipientConfirmed?: boolean;
    nextRailConfirmed?: boolean;
    nextSourceConfirmed?: boolean;
  }) =>
    getNextSendStage({
      entry,
      amountConfirmed,
      recipientConfirmed: nextRecipientConfirmed,
      railConfirmed: nextRailConfirmed,
      sourceConfirmed: nextSourceConfirmed,
      rails: paymentRailOptions,
      selectedRail,
      selectedRailAvailable: railAvailability[selectedRail],
      sourceOptions: onchainSourceOptions,
    });

  const handleImportedDestination = (value: string) => {
    const normalizedDestination = normalizeLightningAddressDestination(value);
    if (!normalizedDestination.trim()) {
      return;
    }

    const parsed = parseDestination(normalizedDestination);
    const isValidImport = parsed.destinationType !== null && !parsed.error;
    const nextAmountSat = parsed.amount ?? (parsedAmount !== null ? 0 : amountSat);
    const importedRails = getDestinationRails(parsed.destinationType, parsed.bip321 ?? null);
    const importedRailAvailability: Record<SendRail, boolean> = {
      ark: nextAmountSat > 0 && offchainWalletBalance >= nextAmountSat,
      lightning: nextAmountSat > 0 && offchainWalletBalance >= nextAmountSat,
      onchain:
        nextAmountSat > 0 &&
        (offchainWalletBalance >= nextAmountSat || onchainWalletBalance >= nextAmountSat),
    };
    const importedRail = getRecommendedRail(importedRails, importedRailAvailability);
    const importedSourceOptions: OnchainSendSource[] = [];

    if (importedRail === "onchain" && nextAmountSat > 0) {
      if (offchainWalletBalance >= nextAmountSat) {
        importedSourceOptions.push("offchain");
      }
      if (onchainWalletBalance >= nextAmountSat) {
        importedSourceOptions.push("onchain");
      }
    }

    setDestination(normalizedDestination);
    setRecipientError(
      parsed.error ?? (parsed.destinationType === null ? INVALID_DESTINATION_MESSAGE : null),
    );
    setDestinationType(parsed.destinationType);
    setIsAmountEditable(parsed.isAmountEditable);
    setBip321Data(parsed.bip321 ?? null);
    setRailConfirmed(false);
    setSourceConfirmed(false);
    setSelectedOnchainSource(null);

    if (parsed.amount) {
      setCurrency("SATS");
      setAmount(parsed.amount.toString());
      setParsedAmount(parsed.amount);
    } else if (parsedAmount !== null) {
      setAmount("");
      setParsedAmount(null);
    }

    if (importedRail) {
      setSelectedRail(importedRail);
      if (parsed.destinationType === "bip321" && parsed.bip321) {
        const importedMethod = getBip321MethodForRail(importedRail, parsed.bip321);
        if (importedMethod) {
          setSelectedPaymentMethod(importedMethod);
        }
      } else if (importedRail === "lightning") {
        setSelectedPaymentMethod(parsed.destinationType === "offer" ? "offer" : "lightning");
      } else {
        setSelectedPaymentMethod(importedRail);
      }
    }

    if (stage !== "amount") {
      setRecipientConfirmed(false);
      setIsEditingRecipient(false);
      return;
    }

    if (!isValidImport) {
      setRecipientConfirmed(false);
      setEntry(amountSat > 0 ? "amount-first" : "recipient-first");
      showStage("recipient");
      return;
    }

    if (parsed.destinationType === "lnurl") {
      setRecipientConfirmed(false);
      if (nextAmountSat <= 0) {
        setEntry("recipient-first");
        setStageHistory(["amount"]);
      } else {
        showStage("recipient");
      }
      return;
    }

    setRecipientConfirmed(true);
    if (nextAmountSat <= 0) {
      setEntry("recipient-first");
      setStageHistory(["amount"]);
      return;
    }

    const nextStage = getNextSendStage({
      entry: amount.trim() ? "amount-first" : "recipient-first",
      amountConfirmed: true,
      recipientConfirmed: true,
      railConfirmed: false,
      sourceConfirmed: false,
      rails: importedRails,
      selectedRail: importedRail,
      selectedRailAvailable: importedRail ? importedRailAvailability[importedRail] : false,
      sourceOptions: importedSourceOptions,
    });

    if (nextStage === "review") {
      setShowConfirmation(true);
    } else {
      showStage(nextStage);
    }
  };

  const handleAmountContinue = () => {
    if (isNaN(amountSat) || amountSat <= 0) {
      setAmountError("Enter an amount greater than zero.");
      return;
    }

    setAmountError(null);
    continueToStage(
      getNextStage({
        amountConfirmed: true,
      }),
    );
  };

  const handleRecipientContinue = () => {
    if (!isValidDestination(destination)) {
      setRecipientError(INVALID_DESTINATION_MESSAGE);
      return;
    }

    if (isMaxSend && !isMaxCompatibleDestination(destinationType, bip321Data)) {
      setRecipientError("MAX can only be sent to an on-chain Bitcoin address.");
      return;
    }
    if (isMaxSend && parsedAmount !== null) {
      setRecipientError("MAX cannot be used with a fixed-amount payment request.");
      return;
    }

    setRecipientError(null);
    if (isResolvingRecipient) {
      return;
    }

    Keyboard.dismiss();
    setRecipientConfirmed(true);
    setIsEditingRecipient(false);
    continueToStage(
      getNextStage({
        amountConfirmed: isMaxSend || amountSat > 0,
        nextRecipientConfirmed: true,
      }),
    );
  };

  const handleRailContinue = () => {
    if (!railAvailability[selectedRail]) {
      return;
    }

    setRailConfirmed(true);
    continueToStage(
      getNextStage({
        amountConfirmed: true,
        nextRecipientConfirmed: true,
        nextRailConfirmed: true,
      }),
    );
  };

  const handleSourceContinue = () => {
    if (!resolvedOnchainSource) {
      return;
    }

    setSelectedOnchainSource(resolvedOnchainSource);
    setSourceConfirmed(true);
    continueToStage(
      getNextStage({
        amountConfirmed: true,
        nextRecipientConfirmed: recipientConfirmed,
        nextRailConfirmed: true,
        nextSourceConfirmed: true,
      }),
    );
  };

  const handleConfirmSend = () => {
    if (!isMaxSend && amountSat <= 0) {
      showAlert({ title: "Invalid Amount", description: "Please enter a valid amount." });
      return;
    }

    if (isMaxSend && resolvedOnchainSource === "offchain") {
      if (ownOnchainAddressQuery.isFetching) {
        return;
      }

      if (ownOnchainAddressQuery.data) {
        showAlert({
          title: "Cannot Send to Own Wallet",
          description:
            "Your Ark balance cannot be swept to this wallet's own onchain address. Use an external Bitcoin address.",
        });
        return;
      }
    }

    reset();
    setParsedResult(null);
    setShowSuccess(false);

    if (destinationType === "bip321" && bip321Data) {
      let destinationToSend = null;
      let newDestinationType: DestinationTypes = "onchain";

      if (selectedPaymentMethod === "ark" && bip321Data.arkAddress) {
        destinationToSend = bip321Data.arkAddress;
        newDestinationType = "ark";
      } else if (selectedPaymentMethod === "lightning" && bip321Data.lightningInvoice) {
        destinationToSend = bip321Data.lightningInvoice;
        newDestinationType = "lightning";
      } else if (selectedPaymentMethod === "offer" && bip321Data.offer) {
        destinationToSend = bip321Data.offer;
        newDestinationType = "offer";
      } else if (selectedPaymentMethod === "onchain" && bip321Data.onchainAddress) {
        destinationToSend = bip321Data.onchainAddress;
        newDestinationType = "onchain";
      }

      if (!destinationToSend) {
        showAlert({
          title: "Invalid Destination",
          description: "Please select a valid destination method.",
        });
        return;
      }
      if (newDestinationType === "onchain" && resolvedOnchainSource === null) {
        showAlert({
          title: "Choose Send Source",
          description: "Choose whether to send from your Ark balance or onchain wallet.",
        });
        return;
      }

      send({
        destination: destinationToSend,
        amountSat:
          isMaxSend && newDestinationType === "onchain"
            ? undefined
            : (newDestinationType === "lightning" || newDestinationType === "offer") &&
                !isAmountEditable
              ? undefined
              : amountSat,
        resolvedAmountSat: confirmationAmountSat,
        isMaxAmount: isMaxSend && newDestinationType === "onchain",
        comment: comment || null,
        onchainSource:
          newDestinationType === "onchain" ? (resolvedOnchainSource ?? undefined) : undefined,
        btcPrice,
      });
    } else {
      const destinationToSend =
        finalDestinationType === "lnurl"
          ? normalizeLightningAddress(cleanedDestination)
          : cleanedDestination;
      if (finalDestinationType === "onchain" && resolvedOnchainSource === null) {
        showAlert({
          title: "Choose Send Source",
          description: "Choose whether to send from your Ark balance or onchain wallet.",
        });
        return;
      }

      send({
        destination: destinationToSend,
        amountSat:
          isMaxSend && finalDestinationType === "onchain"
            ? undefined
            : finalDestinationType === "lightning" && !isAmountEditable
              ? undefined
              : amountSat,
        resolvedAmountSat: confirmationAmountSat,
        isMaxAmount: isMaxSend && finalDestinationType === "onchain",
        comment: comment || null,
        onchainSource:
          finalDestinationType === "onchain" ? (resolvedOnchainSource ?? undefined) : undefined,
        lightningAddressPaymentRoute:
          finalDestinationType === "lnurl" && lightningAddressPaymentRouteDestination
            ? selectedLightningAddressPaymentRoute
            : undefined,
        btcPrice,
        repeatPayment:
          finalDestinationType === "lnurl"
            ? {
                destination: destinationToSend,
                comment,
                amountMode: currency,
                amountInput: amount,
                amountSat,
                ...(currency === "FIAT" ? { fiatCurrency } : {}),
              }
            : undefined,
      });
    }
  };

  const handleCancelConfirmation = () => {
    if (isSending) {
      return;
    }

    reset();
    setShowConfirmation(false);
  };

  const handleDone = () => {
    reset();
    resetSendDraftState();
  };

  const handleClear = () => {
    reset();
    resetSendDraftState();
  };

  const handleEditRecipient = () => {
    setRecipientError(null);
    setIsEditingRecipient(true);
    showStage("recipient");
  };

  const handleSelectLightningAddressSuggestion = (suggestion: string) => {
    setEnteredDestination(suggestion);
  };

  const { showCamera, setShowCamera, handleScanPress, codeScanner } = useQRCodeScanner({
    onScan: handleImportedDestination,
  });

  const errorMessage = useMemo(() => {
    if (!error) return "The transaction failed. Please try again.";
    return error instanceof Error ? error.message : String(error);
  }, [error]);

  return {
    destination,
    setDestination: setEnteredDestination,
    lightningAddressSuggestions,
    handleSelectLightningAddressSuggestion,
    stage,
    stageDirection,
    canGoBack: stageHistory.length > 1,
    handleImportedDestination,
    handleEditRecipient,
    handleClear,
    handleStageBack,
    handleAmountContinue,
    handleRecipientContinue,
    handleRailContinue,
    handleSourceContinue,
    amountError,
    recipientError,
    amount,
    setAmount: setEnteredAmount,
    isMaxSend,
    canSendMax:
      isAmountEditable &&
      (offchainWalletBalance > 0 || onchainWalletBalance > 0) &&
      (!destination.trim() ||
        (recipientConfirmed && isMaxCompatibleDestination(destinationType, bip321Data))),
    canClear:
      amount.trim().length > 0 ||
      destination.trim().length > 0 ||
      comment.trim().length > 0 ||
      isMaxSend,
    handleMaxSend,
    maxSendAmountSat: maxSendBalanceSat,
    isAmountEditable,
    comment,
    setComment: setEnteredComment,
    commentAllowed: lightningAddressPaymentRouteQuery.data?.commentAllowed ?? 0,
    noteUsesLightning:
      lightningAddressPaymentRouteQuery.data?.method === "ark" && comment.trim().length > 0,
    isResolvingRecipient,
    parsedResult,
    handleConfirmSend,
    handleCancelConfirmation,
    handleDone,
    isSending,
    confirmationError: showConfirmation && error ? errorMessage : null,
    showCamera,
    setShowCamera,
    handleScanPress,
    codeScanner,
    currency,
    fiatCurrency,
    toggleCurrency,
    amountSat,
    btcPrice,
    bip321Data,
    paymentRailOptions,
    railAvailability,
    selectedRail,
    setSelectedRail: handleSelectRail,
    selectedPaymentMethod,
    onchainSourceOptions,
    selectedOnchainSource: resolvedOnchainSource,
    setSelectedOnchainSource: handleSelectOnchainSource,
    confirmationAmountSat,
    isOnchainSourceSelectionRequired,
    isConfirmationAmountInvalid: !isMaxSend && amountSat <= 0,
    isCheckingOwnOnchainAddress: ownOnchainAddressQuery.isFetching,
    isOwnOnchainAddress: ownOnchainAddressQuery.data ?? false,
    isLightningAddressPaymentRouteResolutionRequired:
      lightningAddressPaymentRouteDestination !== null &&
      !lightningAddressPaymentRouteQuery.data &&
      !lightningAddressPaymentRouteQuery.error,
    onchainWalletBalance,
    offchainWalletBalance,
    showConfirmation,
    destinationType,
    showSuccess,
    feeEstimate: feeEstimateQuery.data,
    isEstimatingFee:
      lightningAddressPaymentRouteQuery.isFetching ||
      feeEstimateQuery.isFetching ||
      isWaitingForFeeEstimate,
    feeEstimateError: lightningAddressPaymentRouteQuery.error ?? feeEstimateQuery.error,
    feeEstimateUnavailableText: lightningAddressPaymentRouteQuery.error
      ? "Unable to determine whether this payment will use Ark or Lightning."
      : null,
    feeEstimateNote,
    feeEstimateWarning,
  };
};
