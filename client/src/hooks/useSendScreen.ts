import { useState, useEffect, useMemo, useCallback } from "react";
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
import { getMaxSendBalanceSat } from "~/lib/onchainSend";
import { useProfileStore } from "~/store/profileStore";
import logger from "~/lib/log";

const log = logger("useSendScreen");

type DisplayResult = {
  amount_sat: number;
  destination: string;
  txid?: string;
  preimage?: string;
  success: boolean;
  type: string;
};

type SendScreenRouteProp = RouteProp<
  { params: { destination?: string; requestId?: number } },
  "params"
>;

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
  const [isDestinationFocused, setIsDestinationFocused] = useState(false);
  const [destinationRequestRevision, setDestinationRequestRevision] = useState(0);
  const [isMaxSend, setIsMaxSend] = useState(false);

  useEffect(() => {
    if (destination) {
      const {
        destinationType: newDestinationType,
        amount: newAmount,
        isAmountEditable: newIsAmountEditable,
        error: parseError,
        bip321,
      } = parseDestination(destination);

      if (parseError) {
        showAlert({ title: "Invalid Destination", description: parseError });
      }

      setDestinationType(newDestinationType);
      if (newAmount) {
        setIsMaxSend(false);
        setCurrency("SATS");
        setAmount(newAmount.toString());
        setParsedAmount(newAmount);
      } else if (parsedAmount) {
        setAmount("");
        setParsedAmount(null);
      }
      setIsAmountEditable(newIsAmountEditable);

      if (newDestinationType === "bip321" && bip321) {
        setBip321Data(bip321);
        if (bip321.arkAddress) {
          setSelectedPaymentMethod("ark");
        } else if (bip321.lightningInvoice) {
          setSelectedPaymentMethod("lightning");
        } else if (bip321.offer) {
          setSelectedPaymentMethod("offer");
        } else {
          setSelectedPaymentMethod("onchain");
        }
      } else {
        setBip321Data(null);
      }
    } else {
      setDestinationType(null);
      setAmount("");
      setIsAmountEditable(true);
      setParsedAmount(null);
      setBip321Data(null);
      setIsMaxSend(false);
    }
  }, [destination, destinationRequestRevision, showAlert]);

  const finalDestinationType =
    destinationType === "bip321" ? selectedPaymentMethod : destinationType;
  const cleanedDestination = destination.trim().replace(/^(bitcoin:|lightning:)/i, "");
  const normalizedLnurlDestination = normalizeLightningAddress(cleanedDestination);
  const lightningAddressPaymentRouteDestination =
    showConfirmation && finalDestinationType === "lnurl" ? normalizedLnurlDestination : null;
  const lightningAddressPaymentRouteQuery = useLightningAddressPaymentRoute(
    lightningAddressPaymentRouteDestination,
  );

  const {
    mutate: send,
    isPending: isSending,
    data: result,
    error,
    reset,
  } = useSend(finalDestinationType);

  useEffect(() => {
    if (!route.params?.destination) {
      return;
    }

    reset();
    setAmount("");
    setIsAmountEditable(true);
    setComment("");
    setParsedResult(null);
    setDestinationType(null);
    setCurrency("SATS");
    setParsedAmount(null);
    setBip321Data(null);
    setSelectedPaymentMethod("onchain");
    setSelectedOnchainSource(null);
    setIsMaxSend(false);
    setShowConfirmation(false);
    setShowSuccess(false);
    setIsDestinationFocused(false);
    setDestination(normalizeLightningAddressDestination(route.params.destination));
    setDestinationRequestRevision((revision) => revision + 1);
  }, [reset, route.params]);

  const { suggestions: lightningAddressSuggestions } = useLightningAddressSuggestions({
    destination,
    isDestinationFocused,
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

  const onchainSourceOptions = useMemo<OnchainSendSource[]>(() => {
    if (!isOnchainSend || !balance) {
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
    if (!isOnchainSend || (!isMaxSend && amountSat <= 0)) {
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

  useEffect(() => {
    if (!isOnchainSend || !isAmountEditable) {
      setIsMaxSend(false);
    }
  }, [isAmountEditable, isOnchainSend]);

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
      case "lnurl":
        return lightningAddressPaymentRouteQuery.data
          ? { method: lightningAddressPaymentRouteQuery.data.method, amountSat }
          : null;
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
    destinationType,
    finalDestinationType,
    isMaxSend,
    lightningAddressPaymentRouteQuery.data,
    offchainWalletBalance,
    resolvedOnchainSource,
    selectedPaymentMethod,
    showConfirmation,
  ]);

  const feeEstimateQuery = useSendFeeEstimate(feeEstimateParams);

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
    setAmount(nextAmount);
  };

  const handleMaxSend = () => {
    setAmount("");
    setCurrency("SATS");
    setParsedAmount(null);
    setIsMaxSend(true);
  };

  const handleSelectPaymentMethod = (method: "ark" | "lightning" | "onchain" | "offer") => {
    setSelectedPaymentMethod(method);
    if (method !== "onchain") {
      setIsMaxSend(false);
    }
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

  const toggleCurrency = useCallback(() => {
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
  }, [currency, btcPrice, amount, fiatCurrency]);

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
          type: res.source === "offchain" ? "Onchain from Ark" : "Onchain",
        };
      }

      // Check for arkoor payment (has destination_pubkey)
      if ("destination_pubkey" in res) {
        return {
          success: true,
          amount_sat: res.amount_sat,
          destination: res.destination_pubkey,
          type: "Arkoor",
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
      showAlert({
        title: "Invalid Destination",
        description:
          "Please enter a valid Bitcoin address, BOLT11 invoice, Lightning Address, or Ark public key.",
      });
      return;
    }
    if (!isMaxSend && (isNaN(amountSat) || amountSat <= 0)) {
      showAlert({ title: "Invalid Amount", description: "Please enter a valid amount." });
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
    setIsDestinationFocused(false);
    setShowConfirmation(true);
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

    setIsDestinationFocused(false);
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
            ? lightningAddressPaymentRouteQuery.data
            : undefined,
        btcPrice,
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

  const handleCloseSuccess = () => {
    setShowSuccess(false);
  };

  const handleDone = () => {
    reset();
    setParsedResult(null);
    setDestination("");
    setAmount("");
    setIsMaxSend(false);
    setComment("");
    setShowConfirmation(false);
    setShowSuccess(false);
    setIsDestinationFocused(false);
    handleCloseSuccess();
  };

  const handleClear = () => {
    reset();
    setDestination("");
    setComment("");
    setAmount("");
    setIsMaxSend(false);
    setShowConfirmation(false);
    setShowSuccess(false);
    setIsDestinationFocused(false);
  };

  const handleSelectLightningAddressSuggestion = useCallback((suggestion: string) => {
    setDestination(suggestion);
    setIsDestinationFocused(false);
  }, []);

  const { showCamera, setShowCamera, handleScanPress, codeScanner } = useQRCodeScanner({
    onScan: (value) => {
      setDestination(normalizeLightningAddressDestination(value));
    },
  });

  const errorMessage = useMemo(() => {
    if (!error) return "The transaction failed. Please try again.";
    return error instanceof Error ? error.message : String(error);
  }, [error]);

  return {
    destination,
    setDestination,
    isDestinationFocused,
    setIsDestinationFocused,
    lightningAddressSuggestions,
    handleSelectLightningAddressSuggestion,
    amount,
    setAmount: setEnteredAmount,
    isMaxSend,
    canSendMax: isOnchainSend && isAmountEditable,
    handleMaxSend,
    maxSendAmountSat: maxSendBalanceSat,
    isAmountEditable,
    comment,
    setComment,
    parsedResult,
    handleSend,
    handleConfirmSend,
    handleCancelConfirmation,
    handleDone,
    handleClear,
    isSending,
    error,
    errorMessage,
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
    parsedAmount,
    bip321Data,
    selectedPaymentMethod,
    setSelectedPaymentMethod: handleSelectPaymentMethod,
    onchainSourceOptions,
    selectedOnchainSource: resolvedOnchainSource,
    setSelectedOnchainSource,
    resolvedOnchainSource,
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
    handleCloseSuccess,
    feeEstimate: feeEstimateQuery.data,
    isEstimatingFee: lightningAddressPaymentRouteQuery.isFetching || feeEstimateQuery.isFetching,
    feeEstimateError: lightningAddressPaymentRouteQuery.error ?? feeEstimateQuery.error,
    feeEstimateUnavailableText: lightningAddressPaymentRouteQuery.error
      ? "Unable to determine whether this payment will use Ark or Lightning."
      : null,
    feeEstimateNote,
    feeEstimateWarning,
  };
};
