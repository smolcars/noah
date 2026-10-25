import { T, Var, useGT } from "gt-react-native";
import { useEffect, useRef, useState } from "react";
import { AppState, View } from "react-native";
import { err, type Result } from "neverthrow";
import { ConfirmationDialog } from "~/components/ConfirmationDialog";
import { Text } from "~/components/ui/text";
import { useAlert } from "~/contexts/AlertProvider";
import { useBoardArk } from "~/hooks/usePayments";
import { useBalance } from "~/hooks/useWallet";
import {
  AUTO_BOARD_FLOOR_AMOUNT,
  type AutoBoardPlan,
  buildAutoBoardPlan,
} from "~/lib/autoBoarding";
import logger from "~/lib/log";
import { getBoardingStatus } from "~/lib/paymentsApi";
import { getArkInfo } from "~/lib/walletApi";
import { cn } from "~/lib/utils";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useTransactionStore } from "~/store/transactionStore";

const log = logger("AutoBoardingService");
const PLAN_RETRY_BACKOFF_MS = 30_000;

// newFundingTxids: deposits this review claims, released again if confirmation submits nothing.
type AutoBoardReview = { plan: AutoBoardPlan; fundingTxids: string[]; newFundingTxids: string[] };

const unhandledFunding = (txids: string[]) => {
  const handled = useTransactionStore.getState().handledAutoBoardFundingTxids;
  return txids.filter((txid) => !handled.includes(txid));
};

const planForBalance = async (balance: number): Promise<Result<AutoBoardPlan | null, Error>> => {
  const arkInfo = await getArkInfo();
  if (arkInfo.isErr()) return err(arkInfo.error);
  return buildAutoBoardPlan({ arkInfo: arkInfo.value, confirmedOnchainBalanceSat: balance });
};

type AutoBoardingServiceProps = {
  isReady: boolean;
};

const AutoBoardPlanRow = ({
  label,
  value,
  valueClassName,
}: {
  label: string;
  value: string;
  valueClassName?: string;
}) => (
  <View className="flex-row items-center justify-between py-2.5">
    <Text className="text-sm text-muted-foreground" numberOfLines={1}>
      {label}
    </Text>
    <Text className={cn("ml-3 text-sm font-semibold text-foreground", valueClassName)}>
      {value}
    </Text>
  </View>
);

export const AutoBoardingService = ({ isReady }: AutoBoardingServiceProps) => {
  const gt = useGT();
  // Depend on stable copy rather than the SDK's changing development callback.
  const autoBoardFailedTitle = gt("Auto-Boarding Failed");
  const planFailedDescription = gt("Unable to prepare Auto-Board. Please try again later.");
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const [appState, setAppState] = useState(AppState.currentState);
  const [autoBoardReview, setAutoBoardReview] = useState<AutoBoardReview | null>(null);
  const [isCheckingPlan, setIsCheckingPlan] = useState(false);
  const isConfirmingRef = useRef(false);
  const isAvailableRef = useRef(false);
  const planErrorRef = useRef({ retryAfter: 0, reported: false });
  const {
    isAutoBoardingEnabled,
    handledAutoBoardFundingTxids,
    setAutoBoardingEnabled,
    markAutoBoardFundingHandled,
    unmarkAutoBoardFundingHandled,
    setAutoBoardSuccessBanner,
  } = useTransactionStore();
  const { data: balance, dataUpdatedAt: balanceUpdatedAt } = useBalance();
  const { mutate: boardArk, isPending: isBoarding } = useBoardArk();
  const { showAlert } = useAlert();
  const autoBoardPlan = autoBoardReview?.plan;
  const confirmedOnchainBalance = balance?.onchain.confirmed ?? 0;
  const isActive = appState === "active";

  useEffect(() => {
    const subscription = AppState.addEventListener("change", setAppState);
    return () => subscription.remove();
  }, []);

  useEffect(() => {
    isAvailableRef.current = isReady && isAutoBoardingEnabled;
    if (!isReady || !isAutoBoardingEnabled) setAutoBoardReview(null);
    return () => {
      isAvailableRef.current = false;
    };
  }, [isReady, isAutoBoardingEnabled]);

  // Claim only after the open foreground dialog has committed, never during planning.
  useEffect(() => {
    if (
      autoBoardReview &&
      isReady &&
      isAutoBoardingEnabled &&
      isActive &&
      AppState.currentState === "active" &&
      autoBoardReview.fundingTxids.some((txid) => !handledAutoBoardFundingTxids.includes(txid))
    ) {
      markAutoBoardFundingHandled(autoBoardReview.fundingTxids);
    }
  }, [
    autoBoardReview,
    isReady,
    isAutoBoardingEnabled,
    isActive,
    handledAutoBoardFundingTxids,
    markAutoBoardFundingHandled,
  ]);

  useEffect(() => {
    if (
      !isReady ||
      !isAutoBoardingEnabled ||
      !isActive ||
      AppState.currentState !== "active" ||
      confirmedOnchainBalance < AUTO_BOARD_FLOOR_AMOUNT ||
      autoBoardReview ||
      isBoarding ||
      isCheckingPlan ||
      Date.now() < planErrorRef.current.retryAfter
    ) {
      return;
    }

    let cancelled = false;
    const canContinue = () =>
      !cancelled &&
      AppState.currentState === "active" &&
      useTransactionStore.getState().isAutoBoardingEnabled;
    const reportError = (error: Error) => {
      if (!canContinue()) return;
      planErrorRef.current.retryAfter = Date.now() + PLAN_RETRY_BACKOFF_MS;
      if (!planErrorRef.current.reported) {
        planErrorRef.current.reported = true;
        log.e("Auto-boarding planning failed", [error]);
        showAlert({ title: autoBoardFailedTitle, description: planFailedDescription });
      }
    };

    const evaluate = async () => {
      const status = await getBoardingStatus();
      if (!canContinue()) return;
      if (status.isErr()) return reportError(status.error);
      if (
        status.value.blocked ||
        !status.value.fundingTxids.some((txid) => !handledAutoBoardFundingTxids.includes(txid))
      )
        return;

      const plan = await planForBalance(status.value.confirmedOnchainBalanceSat);
      if (!canContinue()) return;
      if (plan.isErr()) return reportError(plan.error);
      planErrorRef.current = { retryAfter: 0, reported: false };
      if (plan.value) {
        setAutoBoardReview({
          plan: plan.value,
          fundingTxids: status.value.fundingTxids,
          newFundingTxids: unhandledFunding(status.value.fundingTxids),
        });
      }
    };
    void evaluate();
    return () => {
      cancelled = true;
    };
  }, [
    isReady,
    isAutoBoardingEnabled,
    isActive,
    confirmedOnchainBalance,
    balanceUpdatedAt,
    handledAutoBoardFundingTxids,
    autoBoardReview,
    isBoarding,
    isCheckingPlan,
    showAlert,
    autoBoardFailedTitle,
    planFailedDescription,
  ]);

  const handleConfirmAutoBoard = () => {
    if (
      !autoBoardReview ||
      !isAvailableRef.current ||
      AppState.currentState !== "active" ||
      !useTransactionStore.getState().isAutoBoardingEnabled ||
      isConfirmingRef.current ||
      isBoarding
    )
      return;

    isConfirmingRef.current = true;
    setIsCheckingPlan(true);
    const claimedFunding = autoBoardReview.newFundingTxids;
    markAutoBoardFundingHandled(autoBoardReview.fundingTxids);
    setAutoBoardReview(null);
    const canContinue = () =>
      isAvailableRef.current &&
      AppState.currentState === "active" &&
      useTransactionStore.getState().isAutoBoardingEnabled;
    // Nothing was submitted: release this review's deposits so a later review can offer them.
    const retryLater = () => {
      unmarkAutoBoardFundingHandled(claimedFunding);
      planErrorRef.current = { retryAfter: Date.now() + PLAN_RETRY_BACKOFF_MS, reported: true };
      showAlert({ title: autoBoardFailedTitle, description: planFailedDescription });
    };
    const confirm = async () => {
      const status = await getBoardingStatus();
      if (!canContinue()) return;
      if (status.isErr()) {
        retryLater();
        return;
      }
      if (status.value.blocked) {
        showAlert({
          title: autoBoardFailedTitle,
          description: gt("A board is already in progress. Please wait for it to finish."),
        });
        return;
      }
      const updatedPlan = await planForBalance(status.value.confirmedOnchainBalanceSat);
      if (!canContinue()) return;
      if (updatedPlan.isErr()) {
        log.e("Auto-boarding confirmation planning failed", [updatedPlan.error]);
        retryLater();
        return;
      }
      if (!updatedPlan.value) {
        showAlert({
          title: gt("Auto-Board amount changed"),
          description: gt(
            "Available funds no longer cover the boarding minimum, fees and reserve.",
          ),
        });
        return;
      }
      if (
        JSON.stringify(updatedPlan.value) !== JSON.stringify(autoBoardReview.plan) ||
        JSON.stringify(status.value.fundingTxids) !== JSON.stringify(autoBoardReview.fundingTxids)
      ) {
        setAutoBoardReview({
          plan: updatedPlan.value,
          fundingTxids: status.value.fundingTxids,
          newFundingTxids: [
            ...new Set([...claimedFunding, ...unhandledFunding(status.value.fundingTxids)]),
          ],
        });
        return;
      }

      const confirmedPlan = updatedPlan.value;
      const latestStatus = await getBoardingStatus();
      if (!canContinue()) return;
      if (latestStatus.isErr() || latestStatus.value.blocked) {
        retryLater();
        return;
      }
      if (
        latestStatus.value.confirmedOnchainBalanceSat !== status.value.confirmedOnchainBalanceSat ||
        JSON.stringify(latestStatus.value.fundingTxids) !==
          JSON.stringify(status.value.fundingTxids)
      ) {
        // Funds moved during planning: offer a fresh review rather than the stale plan.
        unmarkAutoBoardFundingHandled(claimedFunding);
        return;
      }
      // A spend can still race this final read; strict exclusion needs native atomicity.
      boardArk(confirmedPlan.grossBoardAmountSat, {
        onSuccess: () => {
          setAutoBoardSuccessBanner(confirmedPlan.netBoardAmountSat);
        },
        onError: (error) => {
          log.e("Auto-boarding failed", [error]);
        },
      });
    };
    void confirm()
      .catch((error: unknown) => {
        log.e("Auto-boarding confirmation failed", [error]);
        if (canContinue()) {
          showAlert({ title: autoBoardFailedTitle, description: planFailedDescription });
        }
      })
      .finally(() => {
        isConfirmingRef.current = false;
        setIsCheckingPlan(false);
      });
  };

  const handleDisableAutoBoarding = () => {
    if (autoBoardReview) markAutoBoardFundingHandled(autoBoardReview.fundingTxids);
    setAutoBoardingEnabled(false);
    setAutoBoardReview(null);
  };

  return (
    <ConfirmationDialog
      title={gt("Board to Ark?")}
      description={gt(
        "Move available onchain funds into Ark while leaving a fee reserve in your onchain wallet.",
      )}
      confirmText={gt("Yes, board")}
      cancelText={gt("No, turn off")}
      confirmVariant="default"
      open={!!autoBoardReview && isReady && isActive && isAutoBoardingEnabled}
      onOpenChange={(open) => {
        if (!open) {
          if (autoBoardReview) markAutoBoardFundingHandled(autoBoardReview.fundingTxids);
          setAutoBoardReview(null);
        }
      }}
      onConfirm={handleConfirmAutoBoard}
      onCancel={handleDisableAutoBoarding}
      isConfirmDisabled={isBoarding || isCheckingPlan}
      contentClassName="w-[92%] rounded-2xl border-border bg-background p-5"
      headerClassName="gap-2"
      titleClassName="text-2xl font-bold text-foreground"
      descriptionClassName="text-base leading-6 text-muted-foreground"
      footerClassName="mt-1 gap-3 space-x-0"
      cancelClassName="h-12 rounded-xl border-border bg-background"
      actionClassName="h-12 rounded-xl"
    >
      {autoBoardPlan ? (
        <View className="gap-3">
          <View className="rounded-xl border border-border/70 bg-card/80 p-4">
            <T>
              <Text className="text-sm font-medium text-muted-foreground">Amount to board</Text>
            </T>
            <Text className="mt-1 text-3xl font-bold text-foreground">
              {formatBitcoinAmount(autoBoardPlan.grossBoardAmountSat)}
            </Text>
            <T>
              <Text className="mt-1 text-xs leading-5 text-muted-foreground">
                <Var>{formatBitcoinAmount(autoBoardPlan.netBoardAmountSat)}</Var> becomes available
                in Ark after the boarding fee.
              </Text>
            </T>
          </View>

          <View className="rounded-xl border border-border/70 bg-card/60 px-3 py-1">
            <AutoBoardPlanRow
              label={gt("Onchain balance")}
              value={formatBitcoinAmount(autoBoardPlan.confirmedOnchainBalanceSat)}
            />
            <View className="h-px bg-border/70" />
            <AutoBoardPlanRow
              label={gt("Ark boarding fee")}
              value={formatBitcoinAmount(autoBoardPlan.arkFeeSat)}
              valueClassName="text-red-500"
            />
            <View className="h-px bg-border/70" />
            <AutoBoardPlanRow
              label={gt("Estimated onchain fee")}
              value={formatBitcoinAmount(autoBoardPlan.estimatedOnchainFeeSat)}
              valueClassName="text-red-500"
            />
            <View className="h-px bg-border/70" />
            <AutoBoardPlanRow
              label={gt("Stays in onchain wallet")}
              value={formatBitcoinAmount(autoBoardPlan.estimatedRemainingOnchainSat)}
            />
            <View className="h-px bg-border/70" />
            <AutoBoardPlanRow
              label={gt("Ark amount after fee")}
              value={formatBitcoinAmount(autoBoardPlan.netBoardAmountSat)}
              valueClassName="text-green-500"
            />
          </View>

          <T>
            <Text className="text-xs leading-5 text-muted-foreground">
              Onchain fee uses the regular fee rate and a <Var>{autoBoardPlan.estimatedVbytes}</Var>{" "}
              vB 2-in/2-out SegWit estimate.
            </Text>
          </T>
        </View>
      ) : null}
    </ConfirmationDialog>
  );
};
