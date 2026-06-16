import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  View,
  Pressable,
  TouchableWithoutFeedback,
  Keyboard,
  InteractionManager,
  TextInput,
  ScrollView,
} from "react-native";
import { Text } from "../components/ui/text";
import { useAlert } from "~/contexts/AlertProvider";
import { NoahButton } from "../components/ui/NoahButton";
import { Button } from "~/components/ui/button";

import {
  useGenerateLightningInvoice,
  useGenerateOnchainAddress,
  useGenerateOffchainAddress,
} from "../hooks/usePayments";
import { useCopyToClipboard } from "../lib/clipboardUtils";
import QRCode from "react-native-qrcode-svg";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { useFocusEffect, useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { TabParamList } from "~/Navigators";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor, useThemeColors } from "../hooks/useTheme";
import { satsToBtc } from "~/lib/utils";
import { formatFiatAmount, getFiatCurrencyInfo, satsToFiat } from "~/lib/fiatCurrency";
import { useReceiveScreen } from "../hooks/useReceiveScreen";
import { COLORS } from "~/lib/styleConstants";
import { CurrencyToggle } from "~/components/CurrencyToggle";
import {
  subscribeArkoorAddressMovements,
  subscribeLightningPaymentMovements,
  type BarkNotificationEvent,
  type BarkNotificationSubscription,
} from "~/lib/paymentsApi";
import { isArkReceiveMovement, isLightningReceiveMovement } from "~/lib/barkMovement";
import logger from "~/lib/log";
import type { Bolt11Invoice } from "react-native-nitro-ark";
import { queryClient } from "~/queryClient";
import { BlinkingCaret } from "~/components/BlinkingCaret";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

const minAmount = 1;
const SUBSCRIPTION_RETRY_DELAY_MS = 1000;
const log = logger("ReceiveScreen");

type ActiveReceiveSession = {
  sessionId: number;
  amountSat: number | null;
  paymentHash?: string;
  arkAddress?: string;
};

type ReceiveRailGeneration = {
  arkAddress?: string;
  lightningInvoice?: Bolt11Invoice;
  onchainAddress?: string;
};

type GeneratedReceiveRequest = ReceiveRailGeneration & {
  amountSat: number | null;
};

type IdleDeadlineLike = {
  readonly didTimeout: boolean;
  timeRemaining: () => number;
};

type IdleCallbackLike = (deadline: IdleDeadlineLike) => void;

type IdleTaskHandle =
  | { kind: "idle"; id: number }
  | { kind: "timeout"; id: ReturnType<typeof setTimeout> };

const scheduleIdleTask = (callback: IdleCallbackLike): IdleTaskHandle => {
  const requestIdleCallback = (
    globalThis as typeof globalThis & {
      requestIdleCallback?: (cb: IdleCallbackLike) => number;
    }
  ).requestIdleCallback;

  if (requestIdleCallback) {
    return { kind: "idle", id: requestIdleCallback(callback) };
  }

  return {
    kind: "timeout",
    id: setTimeout(() => {
      callback({
        didTimeout: false,
        timeRemaining: () => 0,
      });
    }, 0),
  };
};

const truncateAddress = (addr: string) => {
  if (addr.length <= 40) {
    return addr;
  }
  return `${addr.slice(0, 15)}...${addr.slice(-15)}`;
};

const buildReceiveRequestUri = ({
  amountSat,
  arkAddress,
  lightningInvoice,
  onchainAddress,
}: ReceiveRailGeneration & { amountSat: number | null }) => {
  const params: string[] = [];

  if (amountSat !== null && amountSat >= minAmount) {
    params.push(`amount=${satsToBtc(amountSat)}`);
  }

  if (arkAddress) {
    params.push(`ark=${arkAddress.toUpperCase()}`);
  }

  if (amountSat !== null && amountSat >= minAmount && lightningInvoice?.payment_request) {
    params.push(`lightning=${lightningInvoice.payment_request.toUpperCase()}`);
  }

  if (!onchainAddress && params.length === 0) {
    return undefined;
  }

  let uri = `bitcoin:${onchainAddress ?? ""}`;

  if (params.length > 0) {
    uri += `?${params.join("&")}`;
  }

  return uri;
};

const PaymentRail = ({
  icon,
  label,
  value,
  onCopy,
  isCopied,
}: {
  icon: React.ComponentProps<typeof Icon>["name"];
  label: string;
  value: string;
  onCopy: () => void;
  isCopied: boolean;
}) => {
  const iconColor = useIconColor();
  return (
    <Pressable onPress={onCopy} className="flex-row items-center gap-4 py-4">
      <View
        className="h-11 w-11 items-center justify-center rounded-full border border-border"
        style={{ backgroundColor: "rgba(201, 138, 60, 0.10)" }}
      >
        <Icon name={icon} size={18} color={iconColor} />
      </View>
      <View className="flex-1">
        <Text className="text-sm font-semibold text-foreground">{label}</Text>
        <Text
          className="mt-1 text-sm text-muted-foreground"
          ellipsizeMode="middle"
          numberOfLines={1}
        >
          {truncateAddress(value)}
        </Text>
      </View>
      <Text
        className="text-xs font-semibold uppercase tracking-[2px]"
        style={{ color: isCopied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
      >
        {isCopied ? "Copied" : "Copy"}
      </Text>
    </Pressable>
  );
};

const ReceiveScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<TabParamList>>();
  const iconColor = useIconColor();
  const colors = useThemeColors();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const { amount, setAmount, currency, toggleCurrency, amountSat, btcPrice, fiatCurrency } =
    useReceiveScreen();
  const fiatCurrencyInfo = getFiatCurrencyInfo(fiatCurrency);
  const { copyWithState, isCopied } = useCopyToClipboard();
  const [generatedRequest, setGeneratedRequest] = useState<GeneratedReceiveRequest | null>(null);
  const { showAlert } = useAlert();
  const receiveSessionIdRef = useRef(0);
  const activeReceiveSessionRef = useRef<ActiveReceiveSession | null>(null);
  const arkSubscriptionRef = useRef<BarkNotificationSubscription | null>(null);
  const lightningSubscriptionRef = useRef<BarkNotificationSubscription | null>(null);
  const arkSubscriptionRetryTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lightningSubscriptionRetryTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingReceiveGenerationTaskRef = useRef<ReturnType<
    typeof InteractionManager.runAfterInteractions
  > | null>(null);
  const receiveGenerationRequestIdRef = useRef(0);
  const isCompletingReceiveRef = useRef(false);
  const amountInputRef = useRef<TextInput>(null);
  const [isSchedulingGeneration, setIsSchedulingGeneration] = useState(false);
  const [arkSubscriptionRetryTick, setArkSubscriptionRetryTick] = useState(0);
  const [lightningSubscriptionRetryTick, setLightningSubscriptionRetryTick] = useState(0);

  const { mutateAsync: generateOffchainAddress, isPending: isGeneratingVtxo } =
    useGenerateOffchainAddress();

  const { mutateAsync: generateOnchainAddress, isPending: isGeneratingOnchain } =
    useGenerateOnchainAddress();

  const { mutateAsync: generateLightningInvoice, isPending: isGeneratingLightning } =
    useGenerateLightningInvoice();

  const isLoading =
    isSchedulingGeneration || isGeneratingVtxo || isGeneratingOnchain || isGeneratingLightning;
  const generatedAmountSat = generatedRequest?.amountSat ?? null;
  const arkAddress = generatedRequest?.arkAddress;
  const generatedOnchainAddress = generatedRequest?.onchainAddress;
  const lightningInvoice = generatedRequest?.lightningInvoice;
  const bip321Uri = useMemo(
    () =>
      buildReceiveRequestUri({
        amountSat: generatedAmountSat,
        arkAddress,
        lightningInvoice,
        onchainAddress: generatedOnchainAddress,
      }),
    [arkAddress, generatedAmountSat, generatedOnchainAddress, lightningInvoice],
  );
  const isGenerated = Boolean(bip321Uri);
  const isClearDisabled = isLoading || (!isGenerated && amount === "");
  const isAmountLocked = isLoading || isGenerated;
  const hasEnteredAmount = amount.trim().length > 0;
  const isEnteredAmountInvalid = hasEnteredAmount && amountSat < minAmount;
  const displayAmount = amount === "" ? (currency === "FIAT" ? "0.00" : "0") : amount;
  const [isAmountFocused, setIsAmountFocused] = useState(false);

  const stopSubscription = useCallback(
    (subscription: BarkNotificationSubscription | null, label: string) => {
      if (!subscription) {
        return;
      }

      try {
        subscription.stop();
      } catch (error) {
        log.w(`Failed to stop ${label} receive subscription`, [
          error instanceof Error ? error.message : String(error),
        ]);
      }
    },
    [],
  );

  const releaseArkSubscription = useCallback(() => {
    const subscription = arkSubscriptionRef.current;
    arkSubscriptionRef.current = null;

    if (!subscription) {
      return;
    }

    scheduleIdleTask(() => {
      stopSubscription(subscription, "Ark");
    });
  }, [stopSubscription]);

  const releaseLightningSubscription = useCallback(() => {
    const subscription = lightningSubscriptionRef.current;
    lightningSubscriptionRef.current = null;

    if (!subscription) {
      return;
    }

    scheduleIdleTask(() => {
      stopSubscription(subscription, "Lightning");
    });
  }, [stopSubscription]);

  const clearArkSubscriptionRetry = useCallback(() => {
    if (!arkSubscriptionRetryTimeoutRef.current) {
      return;
    }

    clearTimeout(arkSubscriptionRetryTimeoutRef.current);
    arkSubscriptionRetryTimeoutRef.current = null;
  }, []);

  const clearLightningSubscriptionRetry = useCallback(() => {
    if (!lightningSubscriptionRetryTimeoutRef.current) {
      return;
    }

    clearTimeout(lightningSubscriptionRetryTimeoutRef.current);
    lightningSubscriptionRetryTimeoutRef.current = null;
  }, []);

  const cancelPendingReceiveGeneration = useCallback(() => {
    receiveGenerationRequestIdRef.current += 1;
    pendingReceiveGenerationTaskRef.current?.cancel();
    pendingReceiveGenerationTaskRef.current = null;
    setIsSchedulingGeneration(false);
  }, []);

  const scheduleArkSubscriptionRetry = useCallback((sessionId: number) => {
    if (arkSubscriptionRetryTimeoutRef.current) {
      return;
    }

    arkSubscriptionRetryTimeoutRef.current = setTimeout(() => {
      arkSubscriptionRetryTimeoutRef.current = null;

      if (activeReceiveSessionRef.current?.sessionId !== sessionId) {
        return;
      }

      setArkSubscriptionRetryTick((tick) => tick + 1);
    }, SUBSCRIPTION_RETRY_DELAY_MS);
  }, []);

  const scheduleLightningSubscriptionRetry = useCallback((sessionId: number) => {
    if (lightningSubscriptionRetryTimeoutRef.current) {
      return;
    }

    lightningSubscriptionRetryTimeoutRef.current = setTimeout(() => {
      lightningSubscriptionRetryTimeoutRef.current = null;

      if (activeReceiveSessionRef.current?.sessionId !== sessionId) {
        return;
      }

      setLightningSubscriptionRetryTick((tick) => tick + 1);
    }, SUBSCRIPTION_RETRY_DELAY_MS);
  }, []);

  const clearGeneratedReceiveData = useCallback(
    ({ resetAmount }: { resetAmount: boolean }) => {
      setGeneratedRequest(null);
      if (resetAmount) {
        setAmount("");
      }
    },
    [setAmount],
  );

  const cancelReceiveSession = useCallback(
    ({ resetAmount }: { resetAmount: boolean }) => {
      receiveSessionIdRef.current += 1;
      cancelPendingReceiveGeneration();
      activeReceiveSessionRef.current = null;
      isCompletingReceiveRef.current = false;
      clearArkSubscriptionRetry();
      clearLightningSubscriptionRetry();
      clearGeneratedReceiveData({ resetAmount });
      releaseArkSubscription();
      releaseLightningSubscription();
    },
    [
      clearArkSubscriptionRetry,
      clearGeneratedReceiveData,
      clearLightningSubscriptionRetry,
      cancelPendingReceiveGeneration,
      releaseArkSubscription,
      releaseLightningSubscription,
    ],
  );

  const handleReceiveComplete = useCallback(
    (receivedAmountSat: number) => {
      if (!activeReceiveSessionRef.current || isCompletingReceiveRef.current) {
        return;
      }

      isCompletingReceiveRef.current = true;
      cancelReceiveSession({ resetAmount: true });
      void queryClient.invalidateQueries({ queryKey: ["balance"] });
      void queryClient.invalidateQueries({ queryKey: ["transactions"] });

      navigation.navigate("Home", {
        screen: "ReceiveSuccess",
        params: { amountSat: receivedAmountSat },
      });
    },
    [cancelReceiveSession, navigation],
  );

  const handleArkoorReceiveEvent = useCallback(
    (event: BarkNotificationEvent, sessionId: number) => {
      if (event.kind === "channelLagging") {
        return;
      }

      const activeSession = activeReceiveSessionRef.current;
      if (!activeSession || activeSession.sessionId !== sessionId) {
        return;
      }

      const movement = event.movement;
      if (!movement || movement.status !== "successful" || !isArkReceiveMovement(movement)) {
        return;
      }

      const matchingReceivedOn =
        movement.received_on?.filter(
          (destination) => destination.destination === activeSession.arkAddress,
        ) ?? [];

      if (matchingReceivedOn.length === 0) {
        return;
      }

      const receivedAmountSat = matchingReceivedOn.reduce(
        (sum, destination) => sum + destination.amount_sat,
        0,
      );

      if (receivedAmountSat > 0) {
        handleReceiveComplete(receivedAmountSat);
      } else if (activeSession.amountSat !== null) {
        handleReceiveComplete(activeSession.amountSat);
      }
    },
    [handleReceiveComplete],
  );

  const handleLightningReceiveEvent = useCallback(
    (event: BarkNotificationEvent, sessionId: number) => {
      if (event.kind === "channelLagging") {
        return;
      }

      const activeSession = activeReceiveSessionRef.current;
      if (!activeSession || activeSession.sessionId !== sessionId) {
        return;
      }

      const movement = event.movement;
      if (!movement || movement.status !== "successful" || !isLightningReceiveMovement(movement)) {
        return;
      }

      if (activeSession.amountSat === null) {
        return;
      }

      handleReceiveComplete(activeSession.amountSat);
    },
    [handleReceiveComplete],
  );

  useEffect(() => {
    if (!lightningInvoice?.payment_hash) {
      return;
    }

    const activeSession = activeReceiveSessionRef.current;
    if (!activeSession || activeSession.paymentHash === lightningInvoice.payment_hash) {
      return;
    }

    releaseLightningSubscription();

    const subscriptionResult = subscribeLightningPaymentMovements(
      lightningInvoice.payment_hash,
      (event) => {
        handleLightningReceiveEvent(event, activeSession.sessionId);
      },
    );

    if (subscriptionResult.isErr()) {
      log.w("Failed to subscribe to Lightning receive updates", [subscriptionResult.error.message]);
      scheduleLightningSubscriptionRetry(activeSession.sessionId);
      return;
    }

    clearLightningSubscriptionRetry();
    activeSession.paymentHash = lightningInvoice.payment_hash;
    lightningSubscriptionRef.current = subscriptionResult.value;
  }, [
    clearLightningSubscriptionRetry,
    handleLightningReceiveEvent,
    lightningInvoice?.payment_hash,
    releaseLightningSubscription,
    lightningSubscriptionRetryTick,
    scheduleLightningSubscriptionRetry,
  ]);

  useEffect(() => {
    if (!arkAddress) {
      return;
    }

    const activeSession = activeReceiveSessionRef.current;
    if (!activeSession || activeSession.arkAddress === arkAddress) {
      return;
    }

    releaseArkSubscription();

    const subscriptionResult = subscribeArkoorAddressMovements(arkAddress, (event) => {
      handleArkoorReceiveEvent(event, activeSession.sessionId);
    });

    if (subscriptionResult.isErr()) {
      log.w("Failed to subscribe to Ark receive updates", [subscriptionResult.error.message]);
      scheduleArkSubscriptionRetry(activeSession.sessionId);
      return;
    }

    clearArkSubscriptionRetry();
    activeSession.arkAddress = arkAddress;
    arkSubscriptionRef.current = subscriptionResult.value;
  }, [
    arkAddress,
    arkSubscriptionRetryTick,
    clearArkSubscriptionRetry,
    handleArkoorReceiveEvent,
    releaseArkSubscription,
    scheduleArkSubscriptionRetry,
  ]);

  useFocusEffect(
    useCallback(() => {
      return () => {
        cancelReceiveSession({ resetAmount: false });
      };
    }, [cancelReceiveSession]),
  );

  const generateReceiveRequest = useCallback(() => {
    if (hasEnteredAmount && amountSat < minAmount) {
      showAlert({
        title: "Invalid Amount",
        description: `The minimum amount is ${minAmount} sats.`,
      });
      return;
    }

    const requestAmountSat = hasEnteredAmount ? amountSat : null;

    cancelReceiveSession({ resetAmount: false });
    setGeneratedRequest({ amountSat: requestAmountSat });
    activeReceiveSessionRef.current = {
      sessionId: receiveSessionIdRef.current,
      amountSat: requestAmountSat,
    };

    const sessionId = receiveSessionIdRef.current;
    const lightningInvoiceTask =
      requestAmountSat === null
        ? Promise.resolve<Bolt11Invoice | undefined>(undefined)
        : generateLightningInvoice(requestAmountSat);

    const generationTasks = [
      generateOnchainAddress(),
      generateOffchainAddress(),
      lightningInvoiceTask,
    ] as const;

    void Promise.allSettled(generationTasks).then(
      ([nextOnchainAddressResult, nextArkAddressResult, nextLightningInvoiceResult]) => {
        if (activeReceiveSessionRef.current?.sessionId !== sessionId) {
          return;
        }

        if (nextOnchainAddressResult.status === "rejected") {
          log.w("Receive rail generation failed", ["onchain", nextOnchainAddressResult.reason]);
        }

        if (nextArkAddressResult.status === "rejected") {
          log.w("Receive rail generation failed", ["ark", nextArkAddressResult.reason]);
        }

        if (nextLightningInvoiceResult.status === "rejected") {
          log.w("Receive rail generation failed", ["lightning", nextLightningInvoiceResult.reason]);
        }

        const nextOnchainAddress =
          nextOnchainAddressResult.status === "fulfilled"
            ? nextOnchainAddressResult.value
            : undefined;
        const nextArkAddress =
          nextArkAddressResult.status === "fulfilled" ? nextArkAddressResult.value : undefined;
        const nextLightningInvoice =
          nextLightningInvoiceResult.status === "fulfilled"
            ? nextLightningInvoiceResult.value
            : undefined;

        if (!nextOnchainAddress && !nextArkAddress && !nextLightningInvoice) {
          cancelReceiveSession({ resetAmount: false });
          return;
        }

        setGeneratedRequest({
          amountSat: requestAmountSat,
          onchainAddress: nextOnchainAddress,
          arkAddress: nextArkAddress,
          lightningInvoice: nextLightningInvoice,
        });
      },
    );
  }, [
    amountSat,
    cancelReceiveSession,
    generateLightningInvoice,
    generateOffchainAddress,
    generateOnchainAddress,
    hasEnteredAmount,
    showAlert,
  ]);

  const handleGenerate = () => {
    Keyboard.dismiss();
    cancelPendingReceiveGeneration();

    const requestId = receiveGenerationRequestIdRef.current + 1;
    receiveGenerationRequestIdRef.current = requestId;
    setIsSchedulingGeneration(true);

    pendingReceiveGenerationTaskRef.current = InteractionManager.runAfterInteractions(() => {
      if (receiveGenerationRequestIdRef.current !== requestId) {
        return;
      }

      pendingReceiveGenerationTaskRef.current = null;
      setIsSchedulingGeneration(false);
      generateReceiveRequest();
    });
  };

  const handleClear = () => {
    cancelReceiveSession({ resetAmount: true });
  };

  const handleCopyToClipboard = (value: string, type: string) => {
    copyWithState(value, type);
  };

  const focusAmountInput = useCallback(() => {
    if (isAmountLocked) {
      return;
    }

    requestAnimationFrame(() => {
      amountInputRef.current?.focus();
    });
  }, [isAmountLocked]);

  useEffect(() => {
    if (!isAmountLocked) {
      return;
    }

    amountInputRef.current?.blur();
    setIsAmountFocused(false);
  }, [isAmountLocked]);

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <TouchableWithoutFeedback onPress={Keyboard.dismiss} accessible={false}>
        <ScrollView
          className="flex-1"
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
          contentContainerStyle={{ paddingBottom: 32 }}
        >
          <View className="px-5 pb-8">
            <View className="mb-4 flex-row items-center pt-1">
              <Pressable onPress={() => navigation.goBack()} className="mr-4">
                <Icon name="arrow-back-outline" size={24} color={iconColor} />
              </Pressable>
              <Text className="text-2xl font-bold text-foreground">Receive</Text>
            </View>

            <View className="pt-1">
              <Text className="text-[11px] font-semibold uppercase tracking-[3px] text-muted-foreground">
                Receive Bitcoin
              </Text>
              <Text className="mt-2 max-w-[320px] text-base leading-6 text-muted-foreground">
                Generate a payment request with Ark, Lightning, and on-chain rails when available.
              </Text>
            </View>

            <View className="mt-4">
              <View className="flex-row items-start justify-end gap-4">
                <View className="flex-1">
                  {isGenerated ? (
                    <Text className="text-sm text-muted-foreground">
                      Listening for payment until you clear or leave this screen.
                    </Text>
                  ) : null}
                </View>
                <CurrencyToggle onPress={toggleCurrency} disabled={isAmountLocked} />
              </View>

              <View className="mt-4 items-center">
                <View className="mt-2 h-[64px] justify-center">
                  <View className="self-center">
                    <Pressable onPress={focusAmountInput} disabled={isAmountLocked}>
                      <View className="flex-row items-center justify-center">
                        <Text className="mr-3 text-[46px] font-bold leading-[52px] text-foreground">
                          {currency === "FIAT" ? fiatCurrencyInfo.symbol : "₿"}
                        </Text>
                        <Text className="text-[46px] font-bold leading-[52px] text-foreground">
                          {displayAmount}
                        </Text>
                        <BlinkingCaret
                          color={COLORS.BITCOIN_ORANGE}
                          height={40}
                          visible={isAmountFocused && !isAmountLocked}
                        />
                      </View>
                    </Pressable>
                  </View>

                  <TextInput
                    ref={amountInputRef}
                    placeholder=""
                    keyboardType="numeric"
                    value={amount}
                    onChangeText={setAmount}
                    autoFocus={false}
                    editable={!isAmountLocked}
                    onFocus={() => setIsAmountFocused(true)}
                    onBlur={() => setIsAmountFocused(false)}
                    maxLength={12}
                    selectionColor={COLORS.BITCOIN_ORANGE}
                    style={{
                      position: "absolute",
                      opacity: 0,
                      width: 1,
                      height: 1,
                    }}
                  />
                </View>

                <Text className="mt-3 text-lg font-medium text-muted-foreground">
                  {currency === "SATS"
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

                <View
                  className="mt-4 rounded-full border px-4 py-2"
                  style={{
                    borderColor: `${colors.mutedForeground}1F`,
                  }}
                >
                  <Text className="text-sm text-muted-foreground">
                    {isGenerated
                      ? "Payment request is live"
                      : "Amount optional for Ark and on-chain"}
                  </Text>
                </View>
              </View>

              {bip321Uri ? (
                <View className="mt-7 items-center">
                  <View className="items-center justify-center px-2 py-2">
                    <View className="rounded-[24px] bg-white p-4 shadow-sm shadow-foreground/5">
                      <QRCode value={bip321Uri} size={190} backgroundColor="white" color="black" />
                    </View>
                  </View>
                  <Pressable
                    onPress={() => handleCopyToClipboard(bip321Uri, "bip321")}
                    className="mt-5"
                  >
                    <Text className="text-sm font-semibold text-primary">
                      {isCopied("bip321") ? "Request copied" : "Tap to copy request"}
                    </Text>
                  </Pressable>
                  <Text className="mt-3 max-w-[270px] text-center text-sm leading-6 text-muted-foreground">
                    This QR includes every receive rail that generated successfully.
                  </Text>
                </View>
              ) : null}
            </View>

            {isGenerated && (
              <View
                className="mt-6 overflow-hidden border-t px-1"
                style={{
                  borderColor: `${colors.mutedForeground}22`,
                }}
              >
                <View className="flex-row items-center justify-between pt-5">
                  <Text className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
                    Available via
                  </Text>
                  <Text className="text-xs font-medium uppercase tracking-[2px] text-muted-foreground">
                    Tap any rail
                  </Text>
                </View>

                {arkAddress && (
                  <>
                    <PaymentRail
                      icon="boat-outline"
                      label="Ark"
                      value={arkAddress}
                      onCopy={() => handleCopyToClipboard(arkAddress, "ark")}
                      isCopied={isCopied("ark")}
                    />
                    <View className="h-px bg-border" />
                  </>
                )}

                {lightningInvoice && (
                  <>
                    <PaymentRail
                      icon="flash-outline"
                      label="Lightning"
                      value={lightningInvoice.payment_request}
                      onCopy={() =>
                        handleCopyToClipboard(lightningInvoice.payment_request, "lightning")
                      }
                      isCopied={isCopied("lightning")}
                    />
                    <View className="h-px bg-border" />
                  </>
                )}

                {generatedOnchainAddress && (
                  <PaymentRail
                    icon="link-outline"
                    label="On-chain"
                    value={generatedOnchainAddress}
                    onCopy={() => handleCopyToClipboard(generatedOnchainAddress, "onchain")}
                    isCopied={isCopied("onchain")}
                  />
                )}
              </View>
            )}

            <View className="mt-5 flex-row items-center gap-3">
              <Button
                onPress={handleClear}
                disabled={isClearDisabled}
                variant="outline"
                className="h-14 w-[120px] rounded-2xl"
              >
                <Text className="font-semibold">Clear</Text>
              </Button>
              <NoahButton
                onPress={handleGenerate}
                isLoading={isLoading}
                disabled={isLoading || isEnteredAmountInvalid}
                className="h-14 flex-1 rounded-2xl"
              >
                {isGenerated ? "New request" : "Generate request"}
              </NoahButton>
            </View>
          </View>
        </ScrollView>
      </TouchableWithoutFeedback>
    </NoahSafeAreaView>
  );
};

export default ReceiveScreen;
