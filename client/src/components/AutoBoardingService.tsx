import { memo, useEffect, useRef, useState } from "react";
import { View } from "react-native";
import { ConfirmationDialog } from "~/components/ConfirmationDialog";
import { Text } from "~/components/ui/text";
import { useAlert } from "~/contexts/AlertProvider";
import { useBoardArk } from "~/hooks/usePayments";
import { useArkInfo, useBalance } from "~/hooks/useWallet";
import {
  AUTO_BOARD_FLOOR_AMOUNT,
  type AutoBoardPlan,
  buildAutoBoardPlan,
} from "~/lib/autoBoarding";
import logger from "~/lib/log";
import { cn } from "~/lib/utils";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useTransactionStore } from "~/store/transactionStore";

const log = logger("AutoBoardingService");

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

export const AutoBoardingService = memo(({ isReady }: AutoBoardingServiceProps) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const [hasReportedAutoBoardPlanError, setHasReportedAutoBoardPlanError] = useState(false);
  const [autoBoardPlan, setAutoBoardPlan] = useState<AutoBoardPlan | null>(null);
  const [isAutoBoardDialogOpen, setIsAutoBoardDialogOpen] = useState(false);
  const [isPlanningAutoBoard, setIsPlanningAutoBoard] = useState(false);
  const isPlanningAutoBoardRef = useRef(false);

  const {
    isAutoBoardingEnabled,
    hasAttemptedAutoBoarding,
    setAutoBoardingEnabled,
    setHasAttemptedAutoBoarding,
    setAutoBoardSuccessBanner,
  } = useTransactionStore();
  const { data: balance } = useBalance();
  const { mutate: boardArk, isPending: isBoarding } = useBoardArk();
  const { showAlert } = useAlert();

  const confirmedOnchainBalance = balance?.onchain.confirmed ?? null;
  const shouldLoadArkInfoForAutoBoard =
    isReady &&
    isAutoBoardingEnabled &&
    confirmedOnchainBalance !== null &&
    confirmedOnchainBalance >= AUTO_BOARD_FLOOR_AMOUNT &&
    !isBoarding &&
    !hasAttemptedAutoBoarding;
  const {
    data: arkInfo,
    error: arkInfoError,
    isError: isArkInfoError,
  } = useArkInfo(shouldLoadArkInfoForAutoBoard);

  useEffect(() => {
    if (!isReady) {
      return;
    }

    log.d("Auto-boarding check", [
      {
        isAutoBoardingEnabled,
        hasAttemptedAutoBoarding,
        confirmedOnchainBalance,
        shouldLoadArkInfoForAutoBoard,
        isBoarding,
        isPlanningAutoBoard,
        hasPlan: autoBoardPlan !== null,
        isDialogOpen: isAutoBoardDialogOpen,
      },
    ]);
  }, [
    isReady,
    isAutoBoardingEnabled,
    hasAttemptedAutoBoarding,
    confirmedOnchainBalance,
    shouldLoadArkInfoForAutoBoard,
    isBoarding,
    isPlanningAutoBoard,
    autoBoardPlan,
    isAutoBoardDialogOpen,
  ]);

  useEffect(() => {
    if (
      !isReady ||
      !isAutoBoardingEnabled ||
      confirmedOnchainBalance === null ||
      isBoarding ||
      hasAttemptedAutoBoarding ||
      autoBoardPlan ||
      isAutoBoardDialogOpen ||
      isPlanningAutoBoardRef.current
    ) {
      return;
    }

    if (confirmedOnchainBalance < AUTO_BOARD_FLOOR_AMOUNT) {
      log.d("Auto-boarding skipped", [
        `Confirmed onchain balance below floor: ${confirmedOnchainBalance} sats`,
      ]);
      return;
    }

    if (isArkInfoError) {
      if (!hasReportedAutoBoardPlanError) {
        setHasReportedAutoBoardPlanError(true);
        log.e("Auto-boarding failed to load Ark info", [arkInfoError]);
        showAlert({
          title: "Auto-Boarding Failed",
          description: "Unable to load Ark server info. Please try again later.",
        });
      }
      return;
    }

    if (!arkInfo) {
      log.d("Auto-boarding waiting for Ark info");
      return;
    }

    let isActive = true;
    isPlanningAutoBoardRef.current = true;
    setIsPlanningAutoBoard(true);
    log.d("Auto-boarding estimating plan", [
      `Confirmed onchain balance: ${confirmedOnchainBalance} sats`,
    ]);

    buildAutoBoardPlan({
      arkInfo,
      confirmedOnchainBalanceSat: confirmedOnchainBalance,
    })
      .then((planResult) => {
        if (!isActive) {
          return;
        }

        if (planResult.isErr()) {
          if (!hasReportedAutoBoardPlanError) {
            setHasReportedAutoBoardPlanError(true);
            log.e("Auto-boarding failed to estimate fees", [planResult.error]);
            showAlert({
              title: "Auto-Boarding Failed",
              description: "Unable to estimate boarding fees. Please try again later.",
            });
          }
          return;
        }

        if (hasReportedAutoBoardPlanError) {
          setHasReportedAutoBoardPlanError(false);
        }

        if (!planResult.value) {
          log.d("Auto-boarding not eligible after fee estimates", [
            `Confirmed onchain balance: ${confirmedOnchainBalance} sats`,
          ]);
          return;
        }

        log.d("Auto-boarding confirmation ready", [
          `Gross board amount: ${planResult.value.grossBoardAmountSat} sats`,
          `Estimated Ark fee: ${planResult.value.arkFeeSat} sats`,
          `Estimated onchain fee: ${planResult.value.estimatedOnchainFeeSat} sats`,
        ]);
        setAutoBoardPlan(planResult.value);
        setIsAutoBoardDialogOpen(true);
      })
      .finally(() => {
        if (isActive) {
          isPlanningAutoBoardRef.current = false;
          setIsPlanningAutoBoard(false);
        }
      });

    return () => {
      isActive = false;
      isPlanningAutoBoardRef.current = false;
    };
  }, [
    isReady,
    isAutoBoardingEnabled,
    hasAttemptedAutoBoarding,
    confirmedOnchainBalance,
    arkInfo,
    arkInfoError,
    autoBoardPlan,
    hasReportedAutoBoardPlanError,
    isArkInfoError,
    isAutoBoardDialogOpen,
    isBoarding,
    showAlert,
  ]);

  const handleConfirmAutoBoard = () => {
    if (!autoBoardPlan) {
      return;
    }

    setHasAttemptedAutoBoarding(true);
    setIsAutoBoardDialogOpen(false);
    log.d("Auto-boarding triggered", [
      `Balance: ${autoBoardPlan.confirmedOnchainBalanceSat} sats`,
      `Gross board amount: ${autoBoardPlan.grossBoardAmountSat} sats`,
      `Estimated Ark fee: ${autoBoardPlan.arkFeeSat} sats`,
      `Estimated onchain fee: ${autoBoardPlan.estimatedOnchainFeeSat} sats`,
    ]);

    boardArk(autoBoardPlan.grossBoardAmountSat, {
      onSuccess: () => {
        log.d("Auto-boarding successful");
        const completedPlan = autoBoardPlan;
        setAutoBoardPlan(null);
        setAutoBoardSuccessBanner(completedPlan.netBoardAmountSat);
      },
      onError: (error) => {
        setAutoBoardPlan(null);
        log.e("Auto-boarding failed", [error]);
      },
    });
  };

  const handleDisableAutoBoarding = () => {
    setAutoBoardingEnabled(false);
    setAutoBoardPlan(null);
    setIsAutoBoardDialogOpen(false);
  };

  return (
    <ConfirmationDialog
      title="Board to Ark?"
      description="Move available onchain funds into Ark while leaving a fee reserve in your onchain wallet."
      confirmText="Yes, board"
      cancelText="No, turn off"
      confirmVariant="default"
      open={isAutoBoardDialogOpen}
      onOpenChange={(open) => {
        setIsAutoBoardDialogOpen(open);
        if (!open) {
          if (autoBoardPlan) {
            setHasAttemptedAutoBoarding(true);
          }
          setAutoBoardPlan(null);
        }
      }}
      onConfirm={handleConfirmAutoBoard}
      onCancel={handleDisableAutoBoarding}
      isConfirmDisabled={isBoarding}
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
            <Text className="text-sm font-medium text-muted-foreground">Amount to board</Text>
            <Text className="mt-1 text-3xl font-bold text-foreground">
              {formatBitcoinAmount(autoBoardPlan.grossBoardAmountSat)}
            </Text>
            <Text className="mt-1 text-xs leading-5 text-muted-foreground">
              {formatBitcoinAmount(autoBoardPlan.netBoardAmountSat)} becomes available in Ark after
              the boarding fee.
            </Text>
          </View>

          <View className="rounded-xl border border-border/70 bg-card/60 px-3 py-1">
            <AutoBoardPlanRow
              label="Onchain balance"
              value={formatBitcoinAmount(autoBoardPlan.confirmedOnchainBalanceSat)}
            />
            <View className="h-px bg-border/70" />
            <AutoBoardPlanRow
              label="Ark boarding fee"
              value={formatBitcoinAmount(autoBoardPlan.arkFeeSat)}
              valueClassName="text-red-500"
            />
            <View className="h-px bg-border/70" />
            <AutoBoardPlanRow
              label="Estimated onchain fee"
              value={formatBitcoinAmount(autoBoardPlan.estimatedOnchainFeeSat)}
              valueClassName="text-red-500"
            />
            <View className="h-px bg-border/70" />
            <AutoBoardPlanRow
              label="Stays in onchain wallet"
              value={formatBitcoinAmount(autoBoardPlan.estimatedRemainingOnchainSat)}
            />
            <View className="h-px bg-border/70" />
            <AutoBoardPlanRow
              label="Ark amount after fee"
              value={formatBitcoinAmount(autoBoardPlan.netBoardAmountSat)}
              valueClassName="text-green-500"
            />
          </View>

          <Text className="text-xs leading-5 text-muted-foreground">
            Onchain fee uses the regular fee rate and a {autoBoardPlan.estimatedVbytes} vB
            2-in/2-out SegWit estimate.
          </Text>
        </View>
      ) : null}
    </ConfirmationDialog>
  );
});

AutoBoardingService.displayName = "AutoBoardingService";
