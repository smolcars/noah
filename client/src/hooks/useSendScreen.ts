import { useState, useEffect, useMemo, useCallback } from "react";
import { useRoute } from "@react-navigation/native";
import type { RouteProp } from "@react-navigation/native";
import { useAlert } from "~/contexts/AlertProvider";
import {
  parseDestination,
  isValidDestination,
  type DestinationTypes,
  ParsedBip321,
} from "../lib/sendUtils";
import { useSend, useSendFeeEstimate, type SendFeeEstimateParams } from "./usePayments";
import {
  ONCHAIN_WALLET_ESTIMATE_VBYTES,
  type OnchainSendSource,
  type PaymentResult,
} from "../lib/paymentsApi";
import { useQRCodeScanner } from "~/hooks/useQRCodeScanner";
import { useBtcToUsdRate } from "./useMarketData";
import { useBalance } from "./useWallet";
import { useLightningAddressSuggestions } from "./useLightningAddressSuggestions";
import { satsToUsd, usdToSats } from "../lib/utils";
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

type SendScreenRouteProp = RouteProp<{ params: { destination?: string } }, "params">;

export const useSendScreen = () => {
  const route = useRoute<SendScreenRouteProp>();
  const { showAlert } = useAlert();
  const { data: btcPrice } = useBtcToUsdRate();
  const { data: balance } = useBalance();
  const [destination, setDestination] = useState("");
  const [amount, setAmount] = useState("");
  const [isAmountEditable, setIsAmountEditable] = useState(true);
  const [comment, setComment] = useState("");
  const [parsedResult, setParsedResult] = useState<DisplayResult | null>(null);
  const [destinationType, setDestinationType] = useState<DestinationTypes | null>(null);
  const [currency, setCurrency] = useState<"USD" | "SATS">("SATS");
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

  useEffect(() => {
    if (route.params?.destination) {
      setDestination(route.params.destination);
    }
  }, [route.params]);

  useEffect(() => {
    if (destination) {
      const {
        destinationType: newDestinationType,
        amount: newAmount,
        isAmountEditable: newIsAmountEditable,
        error: parseError,
        bip321,
      } = parseDestination(destination.toLowerCase());

      if (parseError) {
        showAlert({ title: "Invalid Destination", description: parseError });
      }

      setDestinationType(newDestinationType);
      if (newAmount) {
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
    }
  }, [destination, showAlert]);

  const finalDestinationType =
    destinationType === "bip321" ? selectedPaymentMethod : destinationType;

  const {
    mutate: send,
    isPending: isSending,
    data: result,
    error,
    reset,
  } = useSend(finalDestinationType);

  const { suggestions: lightningAddressSuggestions } = useLightningAddressSuggestions({
    destination,
    isDestinationFocused,
  });

  const amountSat = useMemo(() => {
    if (currency === "SATS") {
      return parseInt(amount, 10) || 0;
    }
    if (btcPrice) {
      return usdToSats(parseFloat(amount), btcPrice);
    }
    return 0;
  }, [amount, currency, btcPrice]);

  const isOnchainSend = finalDestinationType === "onchain";
  const onchainWalletBalance = balance?.onchain.confirmed ?? 0;
  const offchainWalletBalance = balance?.offchain.spendable ?? 0;

  const onchainSourceOptions = useMemo<OnchainSendSource[]>(() => {
    if (!isOnchainSend || amountSat <= 0 || !balance) {
      return [];
    }

    const options: OnchainSendSource[] = [];
    if (offchainWalletBalance >= amountSat) {
      options.push("offchain");
    }
    if (onchainWalletBalance >= amountSat) {
      options.push("onchain");
    }
    return options;
  }, [amountSat, balance, isOnchainSend, offchainWalletBalance, onchainWalletBalance]);

  useEffect(() => {
    if (!isOnchainSend || amountSat <= 0) {
      setSelectedOnchainSource(null);
      return;
    }

    if (
      selectedOnchainSource !== null &&
      !onchainSourceOptions.includes(selectedOnchainSource)
    ) {
      setSelectedOnchainSource(null);
    }
  }, [amountSat, isOnchainSend, onchainSourceOptions, selectedOnchainSource]);

  const resolvedOnchainSource =
    selectedOnchainSource ?? (onchainSourceOptions.length === 1 ? onchainSourceOptions[0] : null);

  const isOnchainSourceSelectionRequired =
    isOnchainSend && onchainSourceOptions.length > 1 && resolvedOnchainSource === null;

  const feeEstimateNote =
    isOnchainSend && resolvedOnchainSource === "onchain"
      ? `Regular fee rate, estimated as a ${ONCHAIN_WALLET_ESTIMATE_VBYTES} vB 2-in/2-out SegWit transaction.`
      : null;

  const feeEstimateParams = useMemo<SendFeeEstimateParams | null>(() => {
    if (!showConfirmation) {
      return null;
    }

    if (!amountSat || amountSat <= 0) {
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
          return bip321Data.onchainAddress && resolvedOnchainSource !== null
            ? {
                method: "onchain",
                source: resolvedOnchainSource,
                destination: bip321Data.onchainAddress,
                amountSat,
              }
            : null;
      }
    }

    const cleanedDestination = destination.replace(/^(bitcoin:|lightning:)/i, "");

    switch (finalDestinationType) {
      case "ark":
        return { method: "ark", amountSat };
      case "lightning":
      case "lnurl":
      case "offer":
        return { method: "lightning", amountSat };
      case "onchain":
        return cleanedDestination && resolvedOnchainSource !== null
          ? {
              method: "onchain",
              source: resolvedOnchainSource,
              destination: cleanedDestination,
              amountSat,
            }
          : null;
      default:
        return null;
    }
  }, [
    amountSat,
    bip321Data,
    destination,
    destinationType,
    finalDestinationType,
    resolvedOnchainSource,
    selectedPaymentMethod,
    showConfirmation,
  ]);

  const [debouncedFeeEstimateParams, setDebouncedFeeEstimateParams] =
    useState<SendFeeEstimateParams | null>(null);

  useEffect(() => {
    if (!feeEstimateParams) {
      setDebouncedFeeEstimateParams(null);
      return;
    }

    const timeout = setTimeout(() => {
      setDebouncedFeeEstimateParams(feeEstimateParams);
    }, 350);

    return () => {
      clearTimeout(timeout);
    };
  }, [feeEstimateParams]);

  const feeEstimateQuery = useSendFeeEstimate(debouncedFeeEstimateParams);

  useEffect(() => {
    if (!feeEstimateQuery.error) {
      return;
    }

    log.w("Failed to estimate send fee", [feeEstimateQuery.error]);
  }, [feeEstimateQuery.error]);

  const toggleCurrency = useCallback(() => {
    if (currency === "SATS") {
      if (btcPrice && amount) {
        setAmount(satsToUsd(parseInt(amount, 10), btcPrice));
      }
      setCurrency("USD");
    } else {
      if (btcPrice && amount) {
        setAmount(usdToSats(parseFloat(amount), btcPrice).toString());
      }
      setCurrency("SATS");
    }
  }, [currency, btcPrice, amount]);

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
    if (isNaN(amountSat) || amountSat <= 0) {
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
          description: "Neither your Ark balance nor onchain wallet can cover this payment.",
        });
        return;
      }
    }

    // Show confirmation instead of sending immediately
    setIsDestinationFocused(false);
    setShowConfirmation(true);
  };

  const handleConfirmSend = () => {
    setIsDestinationFocused(false);
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
          (newDestinationType === "lightning" || newDestinationType === "offer") &&
          !isAmountEditable
            ? undefined
            : amountSat,
        resolvedAmountSat: amountSat,
        comment: comment || null,
        onchainSource:
          newDestinationType === "onchain" ? resolvedOnchainSource ?? undefined : undefined,
        btcPrice,
      });
    } else {
      const cleanedDestination = destination.replace(/^(bitcoin:|lightning:)/i, "");
      if (finalDestinationType === "onchain" && resolvedOnchainSource === null) {
        showAlert({
          title: "Choose Send Source",
          description: "Choose whether to send from your Ark balance or onchain wallet.",
        });
        return;
      }

      send({
        destination: cleanedDestination,
        amountSat:
          finalDestinationType === "lightning" && !isAmountEditable ? undefined : amountSat,
        resolvedAmountSat: amountSat,
        comment: comment || null,
        onchainSource:
          finalDestinationType === "onchain" ? resolvedOnchainSource ?? undefined : undefined,
        btcPrice,
      });
    }

    setShowConfirmation(false);
  };

  const handleCancelConfirmation = () => {
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
    setComment("");
    setShowConfirmation(false);
    setShowSuccess(false);
    setIsDestinationFocused(false);
    handleCloseSuccess();
  };

  const handleClear = () => {
    setDestination("");
    setComment("");
    setAmount("");
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
      setDestination(value);
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
    setAmount,
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
    showCamera,
    setShowCamera,
    handleScanPress,
    codeScanner,
    currency,
    toggleCurrency,
    amountSat,
    btcPrice,
    parsedAmount,
    bip321Data,
    selectedPaymentMethod,
    setSelectedPaymentMethod,
    onchainSourceOptions,
    selectedOnchainSource: resolvedOnchainSource,
    setSelectedOnchainSource,
    resolvedOnchainSource,
    isOnchainSourceSelectionRequired,
    onchainWalletBalance,
    offchainWalletBalance,
    showConfirmation,
    destinationType,
    showSuccess,
    handleCloseSuccess,
    feeEstimate: feeEstimateQuery.data,
    isEstimatingFee: feeEstimateQuery.isFetching,
    feeEstimateError: feeEstimateQuery.error,
    feeEstimateUnavailableText: null,
    feeEstimateNote,
  };
};
