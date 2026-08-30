import { useCallback, useEffect, useRef, useState } from "react";
import { useFocusEffect } from "@react-navigation/native";
import type { Bolt11Invoice } from "react-native-nitro-ark";

import { useAlert } from "~/contexts/AlertProvider";
import {
  isArkReceiveMovement,
  isFailedOrCanceledMovement,
  isLightningReceiveMovement,
} from "~/lib/barkMovement";
import logger from "~/lib/log";
import {
  subscribeArkoorAddressMovements,
  subscribeLightningPaymentMovements,
  type BarkNotificationEvent,
  type BarkNotificationSubscription,
} from "~/lib/paymentsApi";
import { buildReceiveRequestUri } from "~/lib/receiveRequest";
import { queryClient } from "~/queryClient";
import { useGenerateLightningInvoice, useGenerateReceiveAddresses } from "~/hooks/usePayments";

const SUBSCRIPTION_RETRY_DELAY_MS = 1000;
const log = logger("useReceiveRequest");

type ActiveReceiveSession = {
  sessionId: number;
  amountSat: number | null;
  arkAddress?: string;
  paymentHash?: string;
};

export type GeneratedReceiveRequest = {
  amountSat: number | null;
  arkAddress: string;
  bip321Uri: string;
  description: string;
  lightningInvoice?: Bolt11Invoice;
  onchainAddress: string;
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
      callback({ didTimeout: false, timeRemaining: () => 0 });
    }, 0),
  };
};

const toError = (error: unknown) => (error instanceof Error ? error : new Error(String(error)));

export function useReceiveRequest(onReceiveComplete: (amountSat: number) => void) {
  const { showAlert } = useAlert();
  const [request, setRequest] = useState<GeneratedReceiveRequest | null>(null);
  const [baseError, setBaseError] = useState<Error | null>(null);
  const [arkSubscriptionRetryTick, setArkSubscriptionRetryTick] = useState(0);
  const [lightningSubscriptionRetryTick, setLightningSubscriptionRetryTick] = useState(0);
  const receiveSessionIdRef = useRef(0);
  const lightningGenerationIdRef = useRef(0);
  const activeReceiveSessionRef = useRef<ActiveReceiveSession | null>(null);
  const arkSubscriptionRef = useRef<BarkNotificationSubscription | null>(null);
  const lightningSubscriptionRef = useRef<BarkNotificationSubscription | null>(null);
  const arkSubscriptionRetryTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lightningSubscriptionRetryTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isCompletingReceiveRef = useRef(false);

  const { mutateAsync: generateReceiveAddresses, isPending: isGeneratingBase } =
    useGenerateReceiveAddresses();
  const { mutateAsync: generateLightningInvoice, isPending: isGeneratingLightning } =
    useGenerateLightningInvoice();

  const stopSubscription = useCallback(
    (subscription: BarkNotificationSubscription | null, label: string) => {
      if (!subscription) {
        return;
      }

      try {
        subscription.stop();
      } catch (error) {
        log.w(`Failed to stop ${label} receive subscription`, [toError(error).message]);
      }
    },
    [],
  );

  const releaseArkSubscription = useCallback(() => {
    const subscription = arkSubscriptionRef.current;
    arkSubscriptionRef.current = null;

    if (subscription) {
      scheduleIdleTask(() => stopSubscription(subscription, "Ark"));
    }
  }, [stopSubscription]);

  const releaseLightningSubscription = useCallback(() => {
    const subscription = lightningSubscriptionRef.current;
    lightningSubscriptionRef.current = null;

    if (subscription) {
      scheduleIdleTask(() => stopSubscription(subscription, "Lightning"));
    }
  }, [stopSubscription]);

  const clearArkSubscriptionRetry = useCallback(() => {
    if (arkSubscriptionRetryTimeoutRef.current) {
      clearTimeout(arkSubscriptionRetryTimeoutRef.current);
      arkSubscriptionRetryTimeoutRef.current = null;
    }
  }, []);

  const clearLightningSubscriptionRetry = useCallback(() => {
    if (lightningSubscriptionRetryTimeoutRef.current) {
      clearTimeout(lightningSubscriptionRetryTimeoutRef.current);
      lightningSubscriptionRetryTimeoutRef.current = null;
    }
  }, []);

  const cancelReceiveSession = useCallback(
    ({ clearRequest }: { clearRequest: boolean }) => {
      receiveSessionIdRef.current += 1;
      lightningGenerationIdRef.current += 1;
      activeReceiveSessionRef.current = null;
      isCompletingReceiveRef.current = false;
      clearArkSubscriptionRetry();
      clearLightningSubscriptionRetry();
      releaseArkSubscription();
      releaseLightningSubscription();
      if (clearRequest) {
        setRequest(null);
      }
    },
    [
      clearArkSubscriptionRetry,
      clearLightningSubscriptionRetry,
      releaseArkSubscription,
      releaseLightningSubscription,
    ],
  );

  const handleReceiveComplete = useCallback(
    (amountSat: number) => {
      if (!activeReceiveSessionRef.current || isCompletingReceiveRef.current) {
        return;
      }

      isCompletingReceiveRef.current = true;
      cancelReceiveSession({ clearRequest: true });
      void queryClient.invalidateQueries({ queryKey: ["balance"] });
      void queryClient.invalidateQueries({ queryKey: ["transactions"] });
      onReceiveComplete(amountSat);
    },
    [cancelReceiveSession, onReceiveComplete],
  );

  const handleArkoorReceiveEvent = useCallback(
    (event: BarkNotificationEvent, sessionId: number) => {
      if (event.kind === "channelLagging") {
        return;
      }

      const activeSession = activeReceiveSessionRef.current;
      const movement = event.movement;
      if (
        !activeSession ||
        activeSession.sessionId !== sessionId ||
        !movement ||
        movement.status !== "successful" ||
        !isArkReceiveMovement(movement)
      ) {
        return;
      }

      const matchingDestinations =
        movement.received_on?.filter(
          (destination) => destination.destination === activeSession.arkAddress,
        ) ?? [];
      if (matchingDestinations.length === 0) {
        return;
      }

      const receivedAmountSat = matchingDestinations.reduce(
        (total, destination) => total + destination.amount_sat,
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
    (event: BarkNotificationEvent, sessionId: number, paymentHash: string) => {
      if (event.kind === "channelLagging") {
        return;
      }

      const activeSession = activeReceiveSessionRef.current;
      const movement = event.movement;
      if (
        !activeSession ||
        activeSession.sessionId !== sessionId ||
        activeSession.paymentHash !== paymentHash ||
        !movement ||
        !isLightningReceiveMovement(movement)
      ) {
        return;
      }

      if (isFailedOrCanceledMovement(movement)) {
        const error = new Error(
          "The Lightning payment could not be received. Generate a new payment request and try again.",
        );
        log.e("Lightning receive ended without completing", [
          { movementId: movement.id, status: movement.status },
        ]);
        cancelReceiveSession({ clearRequest: true });
        setBaseError(error);
        void queryClient.invalidateQueries({ queryKey: ["balance"] });
        void queryClient.invalidateQueries({ queryKey: ["transactions"] });
        showAlert({
          title:
            movement.status === "canceled"
              ? "Lightning Receive Canceled"
              : "Lightning Receive Failed",
          description: error.message,
        });
        return;
      }

      if (movement.status === "successful" && activeSession.amountSat !== null) {
        handleReceiveComplete(activeSession.amountSat);
      }
    },
    [cancelReceiveSession, handleReceiveComplete, showAlert],
  );

  const scheduleArkSubscriptionRetry = useCallback((sessionId: number) => {
    if (arkSubscriptionRetryTimeoutRef.current) {
      return;
    }

    arkSubscriptionRetryTimeoutRef.current = setTimeout(() => {
      arkSubscriptionRetryTimeoutRef.current = null;
      if (activeReceiveSessionRef.current?.sessionId === sessionId) {
        setArkSubscriptionRetryTick((tick) => tick + 1);
      }
    }, SUBSCRIPTION_RETRY_DELAY_MS);
  }, []);

  const scheduleLightningSubscriptionRetry = useCallback((sessionId: number) => {
    if (lightningSubscriptionRetryTimeoutRef.current) {
      return;
    }

    lightningSubscriptionRetryTimeoutRef.current = setTimeout(() => {
      lightningSubscriptionRetryTimeoutRef.current = null;
      if (activeReceiveSessionRef.current?.sessionId === sessionId) {
        setLightningSubscriptionRetryTick((tick) => tick + 1);
      }
    }, SUBSCRIPTION_RETRY_DELAY_MS);
  }, []);

  useEffect(() => {
    const arkAddress = request?.arkAddress;
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
    arkSubscriptionRetryTick,
    clearArkSubscriptionRetry,
    handleArkoorReceiveEvent,
    releaseArkSubscription,
    request?.arkAddress,
    scheduleArkSubscriptionRetry,
  ]);

  useEffect(() => {
    const paymentHash = request?.lightningInvoice?.payment_hash;
    if (!paymentHash) {
      return;
    }

    const activeSession = activeReceiveSessionRef.current;
    if (!activeSession || activeSession.paymentHash === paymentHash) {
      return;
    }

    releaseLightningSubscription();
    activeSession.paymentHash = paymentHash;
    const subscriptionResult = subscribeLightningPaymentMovements(paymentHash, (event) => {
      handleLightningReceiveEvent(event, activeSession.sessionId, paymentHash);
    });

    if (subscriptionResult.isErr()) {
      if (
        activeReceiveSessionRef.current?.sessionId === activeSession.sessionId &&
        activeReceiveSessionRef.current.paymentHash === paymentHash
      ) {
        activeReceiveSessionRef.current.paymentHash = undefined;
      }
      log.w("Failed to subscribe to Lightning receive updates", [subscriptionResult.error.message]);
      scheduleLightningSubscriptionRetry(activeSession.sessionId);
      return;
    }

    clearLightningSubscriptionRetry();
    lightningSubscriptionRef.current = subscriptionResult.value;
  }, [
    clearLightningSubscriptionRetry,
    handleLightningReceiveEvent,
    lightningSubscriptionRetryTick,
    releaseLightningSubscription,
    request?.lightningInvoice,
    scheduleLightningSubscriptionRetry,
  ]);

  const generateBaseRequest = useCallback(async () => {
    cancelReceiveSession({ clearRequest: true });
    setBaseError(null);
    const sessionId = receiveSessionIdRef.current;
    activeReceiveSessionRef.current = { sessionId, amountSat: null };

    let addresses: { arkAddress: string; onchainAddress: string };
    try {
      addresses = await generateReceiveAddresses();
    } catch (error) {
      if (activeReceiveSessionRef.current?.sessionId === sessionId) {
        activeReceiveSessionRef.current = null;
        setBaseError(toError(error));
      }
      return;
    }

    if (activeReceiveSessionRef.current?.sessionId !== sessionId) {
      return;
    }

    try {
      const bip321Uri = buildReceiveRequestUri({
        amountSat: null,
        arkAddress: addresses.arkAddress,
        onchainAddress: addresses.onchainAddress,
      });
      setRequest({
        ...addresses,
        amountSat: null,
        bip321Uri,
        description: "",
      });
    } catch (error) {
      const requestError = toError(error);
      activeReceiveSessionRef.current = null;
      setBaseError(requestError);
      showAlert({ title: "Receive Request Failed", description: requestError.message });
    }
  }, [cancelReceiveSession, generateReceiveAddresses, showAlert]);

  useFocusEffect(
    useCallback(() => {
      void generateBaseRequest();

      return () => {
        cancelReceiveSession({ clearRequest: true });
        setBaseError(null);
      };
    }, [cancelReceiveSession, generateBaseRequest]),
  );

  const saveAmount = async ({
    amountSat,
    description,
  }: {
    amountSat: number;
    description: string;
  }) => {
    const currentRequest = request;
    const activeSession = activeReceiveSessionRef.current;
    if (!currentRequest || !activeSession) {
      throw new Error("A receive request must be ready before adding an amount");
    }

    const generationId = lightningGenerationIdRef.current + 1;
    lightningGenerationIdRef.current = generationId;
    const sessionId = activeSession.sessionId;
    const lightningInvoice = await generateLightningInvoice({ amountSat, description });

    if (
      lightningGenerationIdRef.current !== generationId ||
      activeReceiveSessionRef.current?.sessionId !== sessionId
    ) {
      return false;
    }

    try {
      const bip321Uri = buildReceiveRequestUri({
        amountSat,
        arkAddress: currentRequest.arkAddress,
        lightningInvoice: lightningInvoice.payment_request,
        onchainAddress: currentRequest.onchainAddress,
      });
      activeReceiveSessionRef.current.amountSat = amountSat;
      activeReceiveSessionRef.current.paymentHash = undefined;
      setRequest({
        ...currentRequest,
        amountSat,
        bip321Uri,
        description,
        lightningInvoice,
      });
      return true;
    } catch (error) {
      const requestError = toError(error);
      showAlert({ title: "Receive Request Failed", description: requestError.message });
      throw requestError;
    }
  };

  const removeAmount = () => {
    if (!request || !activeReceiveSessionRef.current) {
      return;
    }

    lightningGenerationIdRef.current += 1;
    clearLightningSubscriptionRetry();
    releaseLightningSubscription();
    activeReceiveSessionRef.current.amountSat = null;
    activeReceiveSessionRef.current.paymentHash = undefined;
    const bip321Uri = buildReceiveRequestUri({
      amountSat: null,
      arkAddress: request.arkAddress,
      onchainAddress: request.onchainAddress,
    });
    setRequest({
      amountSat: null,
      arkAddress: request.arkAddress,
      bip321Uri,
      description: "",
      onchainAddress: request.onchainAddress,
    });
  };

  return {
    baseError,
    generateBaseRequest,
    isGeneratingBase,
    isGeneratingLightning,
    removeAmount,
    request,
    saveAmount,
  };
}
