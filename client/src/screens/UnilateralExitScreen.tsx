import React, { useEffect, useState } from "react";
import {
  Keyboard,
  KeyboardAvoidingView,
  Linking,
  Pressable,
  ScrollView,
  TouchableWithoutFeedback,
  View,
} from "react-native";
import { useIsFocused, useNavigation, useRoute, type RouteProp } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { AlertTriangle } from "lucide-react-native";
import { validateBitcoinAddress } from "bip-321";
import { Text } from "~/components/ui/text";
import { Input } from "~/components/ui/input";
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahBackButton, NativeNoahIconButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahPicker, type NativeNoahPickerOption } from "~/components/ui/NativeNoahPicker";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { ConfirmationDialog } from "~/components/ConfirmationDialog";
import { ExitDepositBottomSheet } from "~/components/ExitDepositBottomSheet";
import {
  useCancelExit,
  useClaimExits,
  useExitOverview,
  useExitFeeEstimate,
  useProgressExits,
  useStartVtxoExit,
} from "~/hooks/useUnilateralExit";
import { useBalance } from "~/hooks/useWallet";
import { useWalletStore } from "~/store/walletStore";
import { useAlert } from "~/contexts/AlertProvider";
import { exitReceiveAmount, exitFeeWarning, type ExitFeeReview } from "~/lib/exitFeeEstimate";
import { APP_VARIANT } from "~/config";
import { getMempoolTxUrl } from "~/constants";
import {
  buildExitTimelineItems,
  EXIT_STATE_LABELS,
  EXIT_STATE_ORDER,
  formatBlocksRemaining,
  getExitBlockRows,
  getExitStatusText,
  isCancelableExit,
  isClaimableExit,
  truncateMiddle,
} from "~/lib/exitTimeline";
import { COLORS } from "~/lib/styleConstants";
import { cn, isNetworkMatch } from "~/lib/utils";
import type { SettingsStackParamList } from "~/Navigators";
import type {
  BarkVtxo,
  ExitProgressState,
  ExitStateDetails,
  ExitStatusResult,
  ExitVtxoResult,
} from "react-native-nitro-ark";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

type UnilateralExitRouteProp = RouteProp<SettingsStackParamList, "UnilateralExit">;
type IconName = React.ComponentProps<typeof Icon>["name"];
type ExitStartMode = "wallet" | "selected";

const stateTone = (state: ExitProgressState) => {
  switch (state) {
    case "Claimable":
    case "Claimed":
      return {
        icon: "checkmark-circle-outline" as IconName,
        color: "#22c55e",
        className: "text-green-500",
        bgClassName: "bg-green-500/10 border-green-500/30",
      };
    case "VtxoAlreadySpent":
      return {
        icon: "alert-circle-outline" as IconName,
        color: "#8e8e93",
        className: "text-muted-foreground",
        bgClassName: "bg-muted border-border",
      };
    case "Canceled":
      return {
        icon: "close-circle-outline" as IconName,
        color: "#dc2626",
        className: "text-red-600 dark:text-red-300",
        bgClassName: "bg-red-500/10 border-red-500/30",
      };
    case "ClaimInProgress":
    case "AwaitingDelta":
      return {
        icon: "time-outline" as IconName,
        color: "#d97706",
        className: "text-amber-600 dark:text-amber-300",
        bgClassName: "bg-amber-500/10 border-amber-500/30",
      };
    case "Processing":
      return {
        icon: "radio-outline" as IconName,
        color: "#c98a3c",
        className: "text-primary",
        bgClassName: "bg-primary/10 border-primary/30",
      };
    default:
      return {
        icon: "ellipse-outline" as IconName,
        color: "#8e8e93",
        className: "text-muted-foreground",
        bgClassName: "bg-muted border-border",
      };
  }
};

const ExplorerValue = ({ value, explorerUrl }: { value: string; explorerUrl?: string | null }) => {
  if (!explorerUrl) {
    return (
      <Text className="ml-3 flex-1 text-right text-sm font-medium text-foreground">{value}</Text>
    );
  }

  return (
    <Pressable
      onPress={() => Linking.openURL(explorerUrl)}
      hitSlop={10}
      className="ml-3 flex-1 flex-row items-center justify-end gap-x-1"
    >
      <Text className="text-right text-sm font-medium text-foreground">{value}</Text>
      <Icon name="open-outline" size={15} color={COLORS.BITCOIN_ORANGE} />
    </Pressable>
  );
};

const ExitSummaryItem = ({ label, value }: { label: string; value: string }) => (
  <View className="flex-1">
    <Text className="text-xs uppercase text-muted-foreground">{label}</Text>
    <Text className="mt-1 text-base font-semibold text-foreground">{value}</Text>
  </View>
);

const ExitStep = ({
  state,
  isActive,
  count,
}: {
  state: ExitProgressState;
  isActive: boolean;
  count: number;
}) => {
  const tone = stateTone(state);
  return (
    <View className="flex-row items-center">
      <View
        className={cn(
          "h-9 w-9 items-center justify-center rounded-full border",
          isActive ? tone.bgClassName : "border-border bg-muted/40",
        )}
      >
        <Icon name={tone.icon} size={18} color={isActive ? tone.color : "#8e8e93"} />
      </View>
      <View className="ml-3 flex-1 border-b border-border/40 py-3">
        <View className="flex-row items-center justify-between">
          <Text className={cn("font-semibold", isActive ? tone.className : "text-foreground")}>
            {EXIT_STATE_LABELS[state]}
          </Text>
          {count > 0 ? (
            <Text className="text-xs text-muted-foreground">
              {count} {count === 1 ? "VTXO" : "VTXOs"}
            </Text>
          ) : null}
        </View>
      </View>
    </View>
  );
};

const PHASE_LABELS: Record<ExitProgressState, string> = {
  Start: "Start",
  Processing: "Process",
  AwaitingDelta: "Wait",
  Claimable: "Ready",
  ClaimInProgress: "Claim",
  Claimed: "Done",
  VtxoAlreadySpent: "Spent",
  Canceled: "Canceled",
};

const EXIT_MODE_OPTIONS = [
  { label: "Entire wallet", value: "wallet" },
  { label: "Selected VTXOs", value: "selected" },
] as const satisfies readonly NativeNoahPickerOption<ExitStartMode>[];

const ExitPhaseRail = ({
  currentState,
  history,
  historyDetails,
  currentDetails,
  currentBlockHeight,
}: {
  currentState: ExitProgressState;
  history?: ExitProgressState[];
  historyDetails?: ExitStateDetails[];
  currentDetails?: ExitStateDetails;
  currentBlockHeight?: number;
}) => {
  const items = buildExitTimelineItems({
    history,
    historyDetails,
    currentState,
    currentDetails,
    currentBlockHeight,
  });
  const activeIndex = EXIT_STATE_ORDER.indexOf(currentState);
  const countByState = items.reduce<Partial<Record<ExitProgressState, number>>>((acc, item) => {
    acc[item.state] = (acc[item.state] ?? 0) + item.count;
    return acc;
  }, {});

  return (
    <View className="mt-3 flex-row items-start">
      {EXIT_STATE_ORDER.map((state, index) => {
        const count = countByState[state] ?? 0;
        const isActive = state === currentState;
        const isComplete = count > 0 && index < activeIndex;
        const tone = stateTone(state);
        return (
          <View key={state} className="flex-1 items-center">
            <View className="mb-1 h-5 w-full flex-row items-center">
              {index > 0 ? (
                <View
                  className={cn(
                    "h-px flex-1",
                    isComplete || isActive ? "bg-primary/50" : "bg-border",
                  )}
                />
              ) : (
                <View className="flex-1" />
              )}
              <View
                className={cn(
                  "h-5 w-5 items-center justify-center rounded-full border",
                  isActive
                    ? tone.bgClassName
                    : isComplete
                      ? "border-green-500/40 bg-green-500/10"
                      : count > 0
                        ? "border-primary/40 bg-primary/10"
                        : "border-border bg-background",
                )}
              >
                {count > 0 ? (
                  <Icon
                    name={isComplete || state === "Claimed" ? "checkmark" : "ellipse"}
                    size={11}
                    color={isActive ? tone.color : isComplete ? "#22c55e" : "#c98a3c"}
                  />
                ) : null}
              </View>
              {index < EXIT_STATE_ORDER.length - 1 ? (
                <View className={cn("h-px flex-1", isComplete ? "bg-primary/50" : "bg-border")} />
              ) : (
                <View className="flex-1" />
              )}
            </View>
            <Text
              className={cn(
                "text-center text-[10px]",
                count > 0 ? "text-foreground" : "text-muted-foreground",
              )}
              numberOfLines={1}
            >
              {PHASE_LABELS[state]}
            </Text>
            {count > 1 ? <Text className="text-[10px] text-muted-foreground">x{count}</Text> : null}
          </View>
        );
      })}
    </View>
  );
};

const ExitVtxoRow = ({
  exit,
  status,
  history,
  currentBlockHeight,
  onPress,
  onCancel,
  isBusy,
  isCanceling,
}: {
  exit: ExitVtxoResult;
  status?: ExitStatusResult;
  history?: ExitProgressState[];
  currentBlockHeight?: number;
  onPress: () => void;
  onCancel: () => void;
  isBusy: boolean;
  isCanceling: boolean;
}) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const state = status?.state ?? exit.state;
  const details = status?.state_details ?? exit.state_details;
  const historyDetails =
    status?.history_details && status.history_details.length > 0
      ? status.history_details
      : exit.history_details;
  const tone = stateTone(state);
  const latestTxid = exit.txids.at(-1);
  const latestTxExplorerUrl = latestTxid ? getMempoolTxUrl(latestTxid) : null;
  const blockRows = getExitBlockRows({ state, details, currentBlockHeight });
  const statusText = getExitStatusText({ state, details, currentBlockHeight });
  const canCancel = isCancelableExit(state, details);

  return (
    <View className="mb-3 overflow-hidden rounded-lg border border-border bg-card">
      <Pressable onPress={onPress} className="p-4">
        <View className="flex-row items-center justify-between">
          <Text className="text-xl font-semibold text-foreground">
            {formatBitcoinAmount(exit.amount_sat)}
          </Text>
          <View className={cn("rounded-full border px-3 py-1.5", tone.bgClassName)}>
            <Text className={cn("text-sm font-semibold", tone.className)}>
              {EXIT_STATE_LABELS[state]}
            </Text>
          </View>
        </View>
        <Text className="mt-3 text-base font-medium text-foreground">{statusText}</Text>
        <Text className="mt-2 text-base text-muted-foreground">
          {truncateMiddle(exit.vtxo_id, 12, 10)}
        </Text>
        {latestTxid ? (
          <Pressable
            onPress={() => {
              if (latestTxExplorerUrl) {
                Linking.openURL(latestTxExplorerUrl);
              }
            }}
            disabled={!latestTxExplorerUrl}
            hitSlop={8}
            className="mt-1 flex-row items-center gap-x-1"
          >
            <Text className="text-sm text-muted-foreground">
              Latest tx: {truncateMiddle(latestTxid, 10, 10)}
            </Text>
            {latestTxExplorerUrl ? (
              <Icon name="open-outline" size={15} color={COLORS.BITCOIN_ORANGE} />
            ) : null}
          </Pressable>
        ) : null}
        {blockRows.length > 0 ? (
          <View className="mt-3 rounded-md border border-border/60 bg-background/60 px-3 py-2">
            {blockRows.map((row) => (
              <View key={row.label} className="flex-row items-center justify-between py-1.5">
                <Text className="text-sm text-muted-foreground">{row.label}</Text>
                <ExplorerValue value={row.value} explorerUrl={row.explorerUrl} />
              </View>
            ))}
          </View>
        ) : null}
        <ExitPhaseRail
          currentState={state}
          history={history}
          historyDetails={historyDetails}
          currentDetails={details}
          currentBlockHeight={currentBlockHeight}
        />
      </Pressable>
      {canCancel ? (
        <View className="border-t border-border px-4 py-3">
          <NativeNoahSecondaryButton
            label={isCanceling ? "Canceling..." : "Cancel Exit"}
            onPress={onCancel}
            disabled={isBusy}
            tone="destructive"
            fullWidth
            testID={`cancel-exit-${exit.vtxo_id}`}
          />
        </View>
      ) : null}
    </View>
  );
};

const ExitCandidateVtxoRow = ({
  vtxo,
  isSelected,
  onPress,
}: {
  vtxo: BarkVtxo;
  isSelected: boolean;
  onPress: () => void;
}) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();

  return (
    <Pressable
      onPress={onPress}
      className={cn(
        "mb-2 flex-row items-center rounded-lg border p-4",
        isSelected ? "border-primary bg-primary/10" : "border-border bg-background",
      )}
    >
      <View className="mr-4">
        <Icon
          name="cube-outline"
          size={22}
          color={isSelected ? COLORS.BITCOIN_ORANGE : "#22c55e"}
        />
      </View>
      <View className="flex-1">
        <Text className="text-base font-semibold text-foreground">
          {formatBitcoinAmount(vtxo.amount)}
        </Text>
        <Text className="mt-1 text-sm text-muted-foreground" numberOfLines={1}>
          Expires at block {vtxo.expiry_height}
        </Text>
        <Text className="mt-1 text-xs text-muted-foreground" numberOfLines={1}>
          {truncateMiddle(vtxo.id, 12, 10)}
        </Text>
      </View>
      <Icon
        name={isSelected ? "checkmark-circle" : "ellipse-outline"}
        size={26}
        color={isSelected ? "#22c55e" : "#8e8e93"}
      />
    </Pressable>
  );
};

const ExitModePicker = ({
  value,
  onChange,
  disabled,
}: {
  value: ExitStartMode;
  onChange: (value: ExitStartMode) => void;
  disabled: boolean;
}) => (
  <NativeNoahPicker
    value={value}
    options={EXIT_MODE_OPTIONS}
    onValueChange={onChange}
    disabled={disabled}
  />
);

type ExitQuote = ReturnType<typeof useExitFeeEstimate>;

function exitReviewDescription(review: ExitFeeReview, formatAmount: (amount: number) => string) {
  const { inputs, estimate } = review;
  const claimOnly = inputs.scope === "claim";
  const scope =
    inputs.scope === "wallet"
      ? "Entire wallet (available VTXOs)"
      : claimOnly
        ? "Claimable exits"
        : "Selected VTXOs";
  const count = inputs.vtxoIds.length;
  const lines = [
    `${scope}: ${count} ${count === 1 ? "VTXO" : "VTXOs"} · ${formatAmount(inputs.amountSat)}`,
  ];
  if (inputs.destinationAddress) lines.push(`Destination: ${inputs.destinationAddress}`);
  if (estimate) {
    if (!claimOnly) {
      lines.push(`Estimated total fees: ${formatAmount(estimate.total_fee_sat)}`);
      lines.push(
        `Broadcast: ${formatAmount(estimate.exit_broadcast_fee_sat)} (paid separately from confirmed onchain funds)`,
      );
    }
    lines.push(
      `Claim fee: ${formatAmount(estimate.claim_fee_sat)} (deducted from recovered funds)`,
    );
    lines.push(
      `Estimated amount to receive: ${formatAmount(exitReceiveAmount(inputs.amountSat, estimate.claim_fee_sat))}`,
    );
    if (!claimOnly) {
      lines.push(
        estimate.fundable
          ? "Broadcast funding: sufficient confirmed onchain funds at this estimate."
          : "Additional confirmed onchain funds required. You are starting tracking anyway; fund the wallet before progressing.",
      );
    }
    const warning = exitFeeWarning(inputs.amountSat, estimate, claimOnly);
    if (warning) lines.push(warning);
  } else {
    lines.push(
      "Fee estimate unavailable. Fees and amount to receive are unknown. Continue without an estimate only if you accept this uncertainty.",
    );
    if (!claimOnly)
      lines.push("Broadcast funding status is unknown; confirmed onchain funds are required.");
  }
  lines.push(
    claimOnly
      ? "This broadcasts the claim transaction. Earlier broadcast fees are not deducted again. Fees may change."
      : "This starts tracking. Use Progress to broadcast exit transactions. Fees may change; the claim estimate updates when you enter a destination.",
  );
  return lines.join("\n\n");
}

const ExitFeePreview = ({
  quote,
  amountSat,
  claimOnly = false,
}: {
  quote: ExitQuote;
  amountSat: number;
  claimOnly?: boolean;
}) => {
  const formatAmount = useBitcoinAmountFormatter();
  const estimate = quote.estimate;
  if (quote.isLoading) {
    return (
      <View className="mt-4 flex-row items-center gap-3">
        <NoahActivityIndicator />
        <Text className="text-sm text-muted-foreground">Estimating fees…</Text>
      </View>
    );
  }
  if (quote.isError) {
    return (
      <View className="mt-4 gap-2">
        <Text className="font-semibold text-foreground">Fee estimate unavailable</Text>
        <Text className="text-sm text-muted-foreground">
          Fees and amount to receive are unknown. You can retry or continue without an estimate.
        </Text>
        <NativeNoahSecondaryButton
          label="Retry estimate"
          onPress={() => void quote.retry()}
          fullWidth
        />
      </View>
    );
  }
  if (!estimate) return null;
  const warning = exitFeeWarning(amountSat, estimate, claimOnly);
  return (
    <View className="mt-4 gap-3 rounded-lg border border-border bg-background p-3">
      <View>
        <Text className="text-sm text-muted-foreground">
          {claimOnly ? "Estimated claim fee" : "Estimated total fees"}
        </Text>
        <Text className="mt-1 text-xl font-semibold text-foreground">
          {formatAmount(claimOnly ? estimate.claim_fee_sat : estimate.total_fee_sat)}
        </Text>
      </View>
      {!claimOnly ? (
        <>
          <ExitFeeRow label="Broadcast fees" amount={estimate.exit_broadcast_fee_sat} />
          <Text className="text-xs leading-4 text-muted-foreground">
            Paid separately from confirmed onchain funds.
          </Text>
          <ExitFeeRow label="Claim fee" amount={estimate.claim_fee_sat} />
        </>
      ) : null}
      <Text className="text-xs leading-4 text-muted-foreground">
        Claim fee is deducted from recovered funds.
        {claimOnly ? " Earlier broadcast fees are not deducted again." : ""}
      </Text>
      <ExitFeeRow
        label="Estimated amount to receive"
        amount={exitReceiveAmount(amountSat, estimate.claim_fee_sat)}
      />
      {warning ? (
        <Text accessibilityRole="alert" className="font-semibold text-destructive">
          {warning}
        </Text>
      ) : null}
      {!claimOnly && !estimate.fundable ? (
        <Text className="text-sm font-semibold text-amber-700 dark:text-amber-300">
          Additional confirmed onchain funds required. Deposit funds and wait for confirmation
          before progressing.
        </Text>
      ) : null}
      <Text className="text-xs leading-4 text-muted-foreground">
        Fees may change.
        {!claimOnly ? " Claim fee updates when you enter a destination at the claim stage." : ""}
      </Text>
    </View>
  );
};

const ExitFeeRow = ({ label, amount }: { label: string; amount: number }) => {
  const formatAmount = useBitcoinAmountFormatter();
  return (
    <View className="flex-row justify-between gap-3">
      <Text className="flex-1 text-sm text-muted-foreground">{label}</Text>
      <Text className="text-sm font-semibold text-foreground">{formatAmount(amount)}</Text>
    </View>
  );
};

const StartExitPanel = ({
  title = "Start Emergency Exit",
  description = "Choose whether to exit all available VTXOs or only specific ones.",
  mode,
  onModeChange,
  spendableVtxos,
  selectedVtxoIds,
  selectedCount,
  selectedAmount,
  isBusy,
  onToggleVtxo,
  onSelectAll,
  onClear,
  onStart,
  onCollapse,
  quote,
  onDeposit,
}: {
  title?: string;
  description?: string;
  mode: ExitStartMode;
  onModeChange: (mode: ExitStartMode) => void;
  spendableVtxos: BarkVtxo[];
  selectedVtxoIds: Set<string>;
  selectedCount: number;
  selectedAmount: number;
  isBusy: boolean;
  onToggleVtxo: (vtxoId: string) => void;
  onSelectAll: () => void;
  onClear: () => void;
  onStart: () => void;
  onCollapse?: () => void;
  quote: ExitQuote;
  onDeposit: () => void;
}) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const hasSelection = selectedCount > 0;
  const startDisabled =
    isBusy ||
    quote.isLoading ||
    spendableVtxos.length === 0 ||
    (mode === "selected" && !hasSelection);

  return (
    <View className="rounded-lg border border-border bg-card p-4">
      <View className="mb-4 flex-row items-start justify-between gap-3">
        <View className="flex-1">
          <Text className="text-lg font-semibold text-foreground">{title}</Text>
          <Text className="mt-1 text-sm leading-5 text-muted-foreground">{description}</Text>
        </View>
        {onCollapse ? (
          <Pressable onPress={onCollapse} hitSlop={10} disabled={isBusy}>
            <Text className="text-sm font-semibold text-primary">Hide</Text>
          </Pressable>
        ) : null}
      </View>

      <ExitModePicker value={mode} onChange={onModeChange} disabled={isBusy} />

      {mode === "wallet" ? (
        <View className="mt-4 rounded-lg border border-border bg-background px-3 py-2">
          <View className="flex-row items-center justify-between py-2">
            <Text className="text-sm text-muted-foreground">Available VTXOs</Text>
            <Text className="text-sm font-semibold text-foreground">
              {spendableVtxos.length.toLocaleString()}
            </Text>
          </View>
          <View className="h-px bg-border/70" />
          <View className="flex-row items-center justify-between py-2">
            <Text className="text-sm text-muted-foreground">Available value</Text>
            <Text className="text-sm font-semibold text-foreground">
              {formatBitcoinAmount(spendableVtxos.reduce((total, vtxo) => total + vtxo.amount, 0))}
            </Text>
          </View>
        </View>
      ) : (
        <View className="mt-4">
          <View className="mb-3 flex-row items-center justify-between">
            <View>
              <Text className="text-sm text-muted-foreground">Selected</Text>
              <Text className="mt-1 text-base font-semibold text-foreground">
                {selectedCount} {selectedCount === 1 ? "VTXO" : "VTXOs"}
              </Text>
            </View>
            <Text className="text-base font-semibold text-foreground">
              {formatBitcoinAmount(selectedAmount)}
            </Text>
          </View>
          <View className="mb-3 flex-row gap-2">
            <Pressable
              onPress={onSelectAll}
              disabled={isBusy || spendableVtxos.length === 0}
              className="h-9 flex-1 items-center justify-center rounded-full bg-background px-3"
            >
              <Text className="text-sm font-medium text-foreground">Select all</Text>
            </Pressable>
            <Pressable
              onPress={onClear}
              disabled={isBusy || !hasSelection}
              className="h-9 flex-1 items-center justify-center rounded-full bg-background px-3"
            >
              <Text className="text-sm font-medium text-muted-foreground">Clear</Text>
            </Pressable>
          </View>
          {spendableVtxos.map((vtxo) => (
            <ExitCandidateVtxoRow
              key={vtxo.id}
              vtxo={vtxo}
              isSelected={selectedVtxoIds.has(vtxo.id)}
              onPress={() => onToggleVtxo(vtxo.id)}
            />
          ))}
        </View>
      )}

      {mode === "selected" && !hasSelection ? (
        <Text className="mt-4 text-sm text-muted-foreground">
          Select VTXOs to estimate exit fees.
        </Text>
      ) : (
        <ExitFeePreview
          quote={quote}
          amountSat={
            mode === "wallet"
              ? spendableVtxos.reduce((total, vtxo) => total + vtxo.amount, 0)
              : selectedAmount
          }
        />
      )}
      {quote.estimate && !quote.estimate.fundable ? (
        <>
          <NativeNoahButton
            label="Deposit Onchain Funds"
            className="mt-4"
            onPress={onDeposit}
            disabled={isBusy}
            fullWidth
          />
          <NativeNoahSecondaryButton
            label={quote.isReviewing ? "Refreshing estimate..." : "Start tracking anyway"}
            className="mt-3"
            onPress={onStart}
            disabled={startDisabled}
            fullWidth
          />
        </>
      ) : (
        <NativeNoahButton
          label={quote.isError ? "Continue without estimate" : "Review Exit"}
          className="mt-4"
          onPress={onStart}
          disabled={startDisabled}
          isLoading={isBusy}
          loadingLabel={quote.isReviewing ? "Refreshing estimate..." : "Starting..."}
          fullWidth
        />
      )}
    </View>
  );
};

const StartAnotherExitCard = ({
  spendableVtxos,
  isBusy,
  onPress,
}: {
  spendableVtxos: BarkVtxo[];
  isBusy: boolean;
  onPress: () => void;
}) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const spendableTotal = spendableVtxos.reduce((total, vtxo) => total + vtxo.amount, 0);

  return (
    <View className="rounded-lg border border-border bg-card p-4">
      <Text className="text-lg font-semibold text-foreground">Start another emergency exit</Text>
      <Text className="mt-1 text-sm leading-5 text-muted-foreground">
        Exit remaining available VTXOs only if you need another emergency exit.
      </Text>
      <View className="my-4 rounded-lg border border-border bg-background px-3 py-2">
        <View className="flex-row items-center justify-between py-2">
          <Text className="text-sm text-muted-foreground">Remaining available</Text>
          <Text className="text-sm font-semibold text-foreground">
            {spendableVtxos.length} {spendableVtxos.length === 1 ? "VTXO" : "VTXOs"}
          </Text>
        </View>
        <View className="h-px bg-border/70" />
        <View className="flex-row items-center justify-between py-2">
          <Text className="text-sm text-muted-foreground">Value</Text>
          <Text className="text-sm font-semibold text-foreground">
            {formatBitcoinAmount(spendableTotal)}
          </Text>
        </View>
      </View>
      <NativeNoahSecondaryButton
        label="Choose VTXOs"
        onPress={onPress}
        disabled={isBusy}
        fullWidth
      />
    </View>
  );
};

const EmptyExitState = ({ children }: { children: React.ReactNode }) => (
  <View className="gap-5">
    <View className="items-center rounded-lg border border-border bg-card px-4 py-8">
      <Icon name="shield-outline" size={40} color="#8e8e93" />
      <Text className="mt-4 text-center text-lg font-semibold text-foreground">
        No emergency exits
      </Text>
      <Text className="mt-2 text-center text-sm leading-5 text-muted-foreground">
        Start only if the Ark server is unavailable and normal offboarding cannot be used.
      </Text>
    </View>
    {children}
  </View>
);

const UnilateralExitScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const route = useRoute<UnilateralExitRouteProp>();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const routeSelectedVtxoIds = route.params?.vtxoIds;

  const [destinationAddress, setDestinationAddress] = useState("");
  const [exitStartMode, setExitStartMode] = useState<ExitStartMode>(
    routeSelectedVtxoIds?.length ? "selected" : "wallet",
  );
  const [selectedExitVtxoIds, setSelectedExitVtxoIds] = useState<Set<string>>(
    () => new Set(routeSelectedVtxoIds ?? []),
  );
  const [isNewExitExpanded, setIsNewExitExpanded] = useState(!!routeSelectedVtxoIds?.length);
  const [deposit, setDeposit] = useState<{ walletId: string | null; broadcastFeeSat: number }>();
  const [startReview, setStartReview] = useState<ExitFeeReview>();
  const [showProgressConfirm, setShowProgressConfirm] = useState(false);
  const [claimReview, setClaimReview] = useState<ExitFeeReview>();
  const [cancelExitVtxoId, setCancelExitVtxoId] = useState<string | null>(null);

  const overviewQuery = useExitOverview();
  const { showAlert } = useAlert();
  const { staticVtxoPubkey, isWalletLoaded, isWalletSuspended, isBackgroundJobRunning } =
    useWalletStore();
  const balanceQuery = useBalance();
  const isFocused = useIsFocused();
  const { refetch: refetchOverview } = overviewQuery;
  useEffect(() => {
    if (isFocused) void refetchOverview();
  }, [isFocused, balanceQuery.dataUpdatedAt, refetchOverview]);
  const startVtxoExit = useStartVtxoExit();
  const cancelExit = useCancelExit();
  const progressExits = useProgressExits();
  const claimExits = useClaimExits();

  const overview = overviewQuery.data;
  const exits = overview?.exits ?? [];
  const spendableVtxos = overview?.spendableVtxos ?? [];
  const selectedExitVtxos = spendableVtxos.filter((vtxo) => selectedExitVtxoIds.has(vtxo.id));
  const selectedExitVtxoIdList = selectedExitVtxos.map((vtxo) => vtxo.id);
  const selectedExitAmount = selectedExitVtxos.reduce((total, vtxo) => total + vtxo.amount, 0);
  const statuses = overview?.statuses ?? {};
  const claimableById = new Map<string, ExitVtxoResult>();
  for (const exit of overview?.claimable ?? []) {
    claimableById.set(exit.vtxo_id, exit);
  }
  for (const exit of exits) {
    if (isClaimableExit(exit, statuses[exit.vtxo_id])) {
      claimableById.set(exit.vtxo_id, exit);
    }
  }
  const claimable = Array.from(claimableById.values());
  const claimableIds = claimable.map((exit) => exit.vtxo_id);
  const claimableTotal = claimable.reduce((total, exit) => total + exit.amount_sat, 0);
  const stateCounts = exits.reduce<Record<ExitProgressState, number>>(
    (acc, exit) => {
      const state = statuses[exit.vtxo_id]?.state ?? exit.state;
      acc[state] += 1;
      return acc;
    },
    {
      Start: 0,
      Processing: 0,
      AwaitingDelta: 0,
      Claimable: 0,
      ClaimInProgress: 0,
      Claimed: 0,
      VtxoAlreadySpent: 0,
      Canceled: 0,
    },
  );
  const claimInProgressCount = stateCounts.ClaimInProgress;
  const claimedCount = stateCounts.Claimed;
  const exitTipHeights = exits
    .map(
      (exit) => statuses[exit.vtxo_id]?.state_details.tip_height ?? exit.state_details.tip_height,
    )
    .filter((height): height is number => typeof height === "number");
  const latestExitTipHeight = exitTipHeights.length > 0 ? Math.max(...exitTipHeights) : undefined;
  const overviewBlockHeight = overview?.blockHeight;
  const staleExitCount =
    overviewBlockHeight === undefined
      ? 0
      : exits.filter((exit) => {
          const tip =
            statuses[exit.vtxo_id]?.state_details.tip_height ?? exit.state_details.tip_height;
          return typeof tip === "number" && tip < overviewBlockHeight;
        }).length;

  const trimmedDestination = destinationAddress.trim();
  const btcValidation = trimmedDestination ? validateBitcoinAddress(trimmedDestination) : null;
  const isValidDestination =
    !!btcValidation?.valid && isNetworkMatch(btcValidation.network, "onchain");
  const startVtxos = exitStartMode === "wallet" ? spendableVtxos : selectedExitVtxos;
  const startAmount = startVtxos.reduce((total, vtxo) => total + vtxo.amount, 0);
  const walletReady = isWalletLoaded && !isWalletSuspended && !isBackgroundJobRunning;
  const revision = `${overviewQuery.dataUpdatedAt}:${balanceQuery.dataUpdatedAt}:${walletReady}`;
  const startQuote = useExitFeeEstimate(
    {
      walletId: staticVtxoPubkey,
      vtxoIds: startVtxos.map((vtxo) => vtxo.id),
      amountSat: startAmount,
      scope: exitStartMode,
      revision,
    },
    walletReady && (exits.length === 0 || isNewExitExpanded),
  );
  const claimQuote = useExitFeeEstimate(
    {
      walletId: staticVtxoPubkey,
      vtxoIds: claimableIds,
      amountSat: claimableTotal,
      scope: "claim",
      destinationAddress: trimmedDestination,
      revision,
    },
    walletReady && isValidDestination,
  );
  useEffect(() => setStartReview(undefined), [startQuote.contextKey]);
  useEffect(() => setClaimReview(undefined), [claimQuote.contextKey]);
  const isBusy =
    !walletReady ||
    startQuote.isReviewing ||
    claimQuote.isReviewing ||
    startVtxoExit.isPending ||
    progressExits.isPending ||
    claimExits.isPending ||
    cancelExit.isPending;
  const canStartNewExit = spendableVtxos.length > 0;

  const allClaimableHeight = overview?.allClaimableAtHeight;
  const currentBlockHeight = overview?.blockHeight;
  const claimableBlockLabel =
    allClaimableHeight !== undefined && currentBlockHeight !== undefined
      ? allClaimableHeight <= currentBlockHeight
        ? "Now"
        : `${allClaimableHeight} (${allClaimableHeight - currentBlockHeight} blocks)`
      : allClaimableHeight !== undefined
        ? `${allClaimableHeight}`
        : "Unknown";
  const allClaimableRemainingLabel = formatBlocksRemaining(currentBlockHeight, allClaimableHeight);

  const staleReview = () =>
    showAlert({
      title: "Review Exit Again",
      description:
        "The estimate expired or your wallet changed. Review the current details before continuing.",
    });

  const handleStart = () => {
    if (!startReview || !startQuote.isCurrentReview(startReview)) {
      setStartReview(undefined);
      staleReview();
      return;
    }
    // Even wallet mode uses the reviewed IDs, so newly arriving funds are never added silently.
    startVtxoExit.mutate(startReview.inputs.vtxoIds);
    setStartReview(undefined);
  };

  const reviewStart = async () => {
    const snapshot = await startQuote.review();
    if (snapshot && startQuote.isCurrentReview(snapshot)) setStartReview(snapshot);
  };

  const reviewClaim = async () => {
    const snapshot = await claimQuote.review();
    if (snapshot && claimQuote.isCurrentReview(snapshot)) setClaimReview(snapshot);
  };

  const depositOnchain = () => {
    if (!startQuote.estimate || !walletReady) return;
    setDeposit({
      walletId: staticVtxoPubkey,
      broadcastFeeSat: startQuote.estimate.exit_broadcast_fee_sat,
    });
  };

  const toggleExitVtxoSelection = (vtxoId: string) => {
    if (isBusy) {
      return;
    }

    setSelectedExitVtxoIds((current) => {
      const next = new Set(current);
      if (next.has(vtxoId)) {
        next.delete(vtxoId);
      } else {
        next.add(vtxoId);
      }
      return next;
    });
  };

  const selectAllExitVtxos = () => {
    setSelectedExitVtxoIds(new Set(spendableVtxos.map((vtxo) => vtxo.id)));
  };

  const clearExitVtxoSelection = () => {
    setSelectedExitVtxoIds(new Set());
  };

  const handleExitModeChange = (mode: ExitStartMode) => {
    setExitStartMode(mode);
  };

  const handleClaim = () => {
    if (
      !claimReview ||
      !claimQuote.isCurrentReview(claimReview) ||
      !claimReview.inputs.destinationAddress
    ) {
      setClaimReview(undefined);
      staleReview();
      return;
    }
    claimExits.mutate({
      vtxoIds: claimReview.inputs.vtxoIds,
      destinationAddress: claimReview.inputs.destinationAddress,
    });
    setClaimReview(undefined);
  };

  const handleCancelExit = () => {
    if (!cancelExitVtxoId) {
      return;
    }

    cancelExit.mutate(cancelExitVtxoId);
    setCancelExitVtxoId(null);
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <KeyboardAvoidingView behavior="padding" className="flex-1">
        <TouchableWithoutFeedback onPress={Keyboard.dismiss} accessible={false}>
          <ScrollView
            className="p-4"
            contentContainerStyle={{ paddingBottom: 80 }}
            keyboardShouldPersistTaps="handled"
          >
            <View className="mb-6 flex-row items-center justify-between">
              <View className="flex-row items-center">
                <NativeNoahBackButton
                  onPress={() => navigation.goBack()}
                  className="mr-3"
                  testID="emergency-exit-back-button"
                />
                <Text className="text-2xl font-bold text-foreground">Emergency Exit</Text>
              </View>
              <NativeNoahIconButton
                icon="refresh"
                accessibilityLabel="Refresh emergency exits"
                onPress={() => void overviewQuery.refetch()}
                disabled={isBusy}
                isLoading={overviewQuery.isFetching}
                testID="emergency-exits-refresh-button"
              />
            </View>

            <Alert icon={AlertTriangle} className="mb-5 border-amber-500/40 bg-amber-500/10">
              <AlertTitle className="text-amber-700 dark:text-amber-300">
                Emergency use only
              </AlertTitle>
              <AlertDescription className="text-amber-700/90 dark:text-amber-200/90">
                Use this only if the Ark server is unresponsive or uncooperative. In normal
                circumstances, send to an onchain address from your Ark balance.
              </AlertDescription>
            </Alert>

            {overviewQuery.isLoading ? (
              <View className="items-center py-12">
                <NoahActivityIndicator />
                <Text className="mt-3 text-muted-foreground">Loading exit status...</Text>
              </View>
            ) : overviewQuery.error ? (
              <View className="rounded-lg border border-destructive bg-destructive/10 p-4">
                <Text className="font-semibold text-destructive">Unable to load exits</Text>
                <Text className="mt-2 text-sm text-destructive">{overviewQuery.error.message}</Text>
              </View>
            ) : exits.length === 0 ? (
              <EmptyExitState>
                {canStartNewExit ? (
                  <StartExitPanel
                    mode={exitStartMode}
                    onModeChange={handleExitModeChange}
                    spendableVtxos={spendableVtxos}
                    selectedVtxoIds={selectedExitVtxoIds}
                    selectedCount={selectedExitVtxoIdList.length}
                    selectedAmount={selectedExitAmount}
                    isBusy={isBusy}
                    onToggleVtxo={toggleExitVtxoSelection}
                    onSelectAll={selectAllExitVtxos}
                    onClear={clearExitVtxoSelection}
                    onStart={reviewStart}
                    quote={startQuote}
                    onDeposit={depositOnchain}
                  />
                ) : null}
              </EmptyExitState>
            ) : (
              <>
                <View className="mb-5 rounded-lg border border-border bg-card p-4">
                  <View className="flex-row gap-x-4">
                    <ExitSummaryItem label="Tracked" value={`${exits.length}`} />
                    <ExitSummaryItem
                      label="Pending"
                      value={formatBitcoinAmount(overview?.pendingTotal ?? 0)}
                    />
                  </View>
                  <View className="mt-4 flex-row gap-x-4">
                    <ExitSummaryItem
                      label="Claimable"
                      value={formatBitcoinAmount(claimableTotal)}
                    />
                    <ExitSummaryItem label="All Claimable" value={claimableBlockLabel} />
                  </View>
                  <View className="mt-4 flex-row gap-x-4">
                    <ExitSummaryItem label="Claiming" value={`${claimInProgressCount}`} />
                    <ExitSummaryItem label="Claimed" value={`${claimedCount}`} />
                  </View>
                  <View className="mt-4 flex-row gap-x-4">
                    <ExitSummaryItem
                      label="Available"
                      value={`${overview?.spendableVtxoCount ?? 0} ${
                        overview?.spendableVtxoCount === 1 ? "VTXO" : "VTXOs"
                      }`}
                    />
                    <ExitSummaryItem
                      label="Available Value"
                      value={formatBitcoinAmount(overview?.spendableVtxoTotal ?? 0)}
                    />
                  </View>
                </View>

                <View className="mb-5 rounded-lg border border-border bg-card p-4">
                  <Text className="mb-3 text-lg font-semibold text-foreground">Block Status</Text>
                  <View className="flex-row gap-x-4">
                    <ExitSummaryItem
                      label="Current Height"
                      value={
                        overview?.blockHeight !== undefined ? `${overview.blockHeight}` : "Unknown"
                      }
                    />
                    <ExitSummaryItem
                      label="Exit Synced Tip"
                      value={
                        latestExitTipHeight !== undefined ? `${latestExitTipHeight}` : "Unknown"
                      }
                    />
                  </View>
                  <View className="mt-4 flex-row gap-x-4">
                    <ExitSummaryItem label="All Claimable" value={claimableBlockLabel} />
                    <ExitSummaryItem
                      label="Remaining"
                      value={allClaimableRemainingLabel ?? "Unknown"}
                    />
                  </View>
                  {staleExitCount > 0 ? (
                    <Text className="mt-3 text-sm leading-5 text-muted-foreground">
                      {staleExitCount} {staleExitCount === 1 ? "exit is" : "exits are"} behind the
                      current chain height. Use Progress to check the chain; this can also broadcast
                      exit transactions.
                    </Text>
                  ) : null}
                </View>

                {claimable.length === 0 && claimInProgressCount > 0 ? (
                  <View className="mb-5 rounded-lg border border-amber-500/30 bg-amber-500/10 p-4">
                    <Text className="text-base font-semibold text-amber-700 dark:text-amber-300">
                      Claim broadcasted
                    </Text>
                    <Text className="mt-1 text-sm leading-5 text-amber-700/90 dark:text-amber-200/90">
                      No further claim action is available for VTXOs in Claiming. Wait for the claim
                      transaction to confirm, then use Progress to update tracked state.
                    </Text>
                  </View>
                ) : null}

                <View className="mb-5 rounded-lg border border-border bg-card p-4">
                  <Text className="mb-2 text-lg font-semibold text-foreground">Timeline</Text>
                  {EXIT_STATE_ORDER.map((state) => (
                    <ExitStep
                      key={state}
                      state={state}
                      count={stateCounts[state]}
                      isActive={stateCounts[state] > 0}
                    />
                  ))}
                </View>

                <View className="mb-5">
                  <View className="mb-3 flex-row items-center justify-between">
                    <Text className="text-lg font-semibold text-foreground">VTXOs</Text>
                    <Text className="text-sm text-muted-foreground">
                      {claimable.length} claimable
                    </Text>
                  </View>
                  {exits.map((exit) => (
                    <ExitVtxoRow
                      key={exit.vtxo_id}
                      exit={exit}
                      status={statuses[exit.vtxo_id]}
                      history={statuses[exit.vtxo_id]?.history}
                      currentBlockHeight={overview?.blockHeight}
                      onPress={() =>
                        navigation.navigate("ExitVtxoDetail", { vtxoId: exit.vtxo_id })
                      }
                      onCancel={() => setCancelExitVtxoId(exit.vtxo_id)}
                      isBusy={isBusy}
                      isCanceling={cancelExit.isPending && cancelExit.variables === exit.vtxo_id}
                    />
                  ))}
                </View>

                <View className="mb-5 flex-row gap-x-3">
                  <NativeNoahSecondaryButton
                    label={overviewQuery.isFetching ? "Refreshing..." : "Refresh Status"}
                    className="flex-1"
                    onPress={() => void overviewQuery.refetch()}
                    disabled={isBusy}
                    fullWidth
                  />
                  {overview?.hasPending || claimable.length > 0 || claimInProgressCount > 0 ? (
                    <NativeNoahButton
                      label="Progress"
                      className="flex-1"
                      onPress={() => setShowProgressConfirm(true)}
                      disabled={isBusy}
                      isLoading={progressExits.isPending}
                      loadingLabel="Progressing..."
                      fullWidth
                    />
                  ) : null}
                </View>

                <Text className="mb-5 text-sm leading-5 text-muted-foreground">
                  Refresh Status reloads saved wallet state. Progress checks the chain and can
                  broadcast or fee-bump exit transactions.
                </Text>

                {claimable.length > 0 ? (
                  <View className="mb-5 rounded-lg border border-border bg-card p-4">
                    <Text className="text-lg font-semibold text-foreground">Claim Exits</Text>
                    <Text className="mt-1 text-sm leading-5 text-muted-foreground">
                      Sweep claimable exit outputs to an on-chain Bitcoin address.
                    </Text>
                    <View className="mt-4 rounded-lg border border-border bg-background px-3 py-2">
                      <Input
                        value={destinationAddress}
                        onChangeText={setDestinationAddress}
                        placeholder="Bitcoin address"
                        autoCapitalize="none"
                        autoCorrect={false}
                        className="border-0 bg-transparent p-0 text-foreground"
                      />
                    </View>
                    {trimmedDestination && !isValidDestination ? (
                      <Text className="mt-2 text-sm text-destructive">
                        Enter a valid {APP_VARIANT} on-chain address.
                      </Text>
                    ) : null}
                    {isValidDestination ? (
                      <ExitFeePreview quote={claimQuote} amountSat={claimableTotal} claimOnly />
                    ) : null}
                    <NativeNoahButton
                      label={claimQuote.isError ? "Continue without estimate" : "Review Claim"}
                      className="mt-4"
                      disabled={!isValidDestination || isBusy || claimQuote.isLoading}
                      isLoading={claimExits.isPending || claimQuote.isReviewing}
                      loadingLabel={
                        claimQuote.isReviewing ? "Refreshing estimate..." : "Claiming..."
                      }
                      onPress={reviewClaim}
                      fullWidth
                    />
                  </View>
                ) : null}

                {canStartNewExit ? (
                  <View>
                    {isNewExitExpanded ? (
                      <StartExitPanel
                        title="New Exit"
                        description="You already have an exit in progress. Start another one only for remaining VTXOs."
                        mode={exitStartMode}
                        onModeChange={handleExitModeChange}
                        spendableVtxos={spendableVtxos}
                        selectedVtxoIds={selectedExitVtxoIds}
                        selectedCount={selectedExitVtxoIdList.length}
                        selectedAmount={selectedExitAmount}
                        isBusy={isBusy}
                        onToggleVtxo={toggleExitVtxoSelection}
                        onSelectAll={selectAllExitVtxos}
                        onClear={clearExitVtxoSelection}
                        onStart={reviewStart}
                        quote={startQuote}
                        onDeposit={depositOnchain}
                        onCollapse={() => setIsNewExitExpanded(false)}
                      />
                    ) : (
                      <StartAnotherExitCard
                        spendableVtxos={spendableVtxos}
                        isBusy={isBusy}
                        onPress={() => setIsNewExitExpanded(true)}
                      />
                    )}
                  </View>
                ) : null}
              </>
            )}

            <ConfirmationDialog
              open={!!startReview && startReview.contextKey === startQuote.contextKey}
              onOpenChange={(open) => {
                if (!open) setStartReview(undefined);
              }}
              title={startReview?.estimate ? "Start Emergency Exit" : "Continue without estimate?"}
              description={
                startReview ? exitReviewDescription(startReview, formatBitcoinAmount) : ""
              }
              confirmText={
                startReview?.inputs.scope === "wallet" ? "Start Wallet Exit" : "Start Selected Exit"
              }
              onConfirm={handleStart}
              isConfirmDisabled={isBusy}
            />
            <ConfirmationDialog
              open={cancelExitVtxoId !== null}
              onOpenChange={(open) => {
                if (!open) {
                  setCancelExitVtxoId(null);
                }
              }}
              title="Cancel Emergency Exit"
              description="Stop the emergency exit for this VTXO. Already-broadcast shared transactions are not reversed. The VTXO remains spendable and can be exited again. Cancellation only succeeds before the final exit transaction is broadcast."
              confirmText="Cancel Exit"
              onConfirm={handleCancelExit}
            />
            <ConfirmationDialog
              open={showProgressConfirm}
              onOpenChange={setShowProgressConfirm}
              title="Progress Exits"
              description="This may broadcast or fee-bump Bitcoin transactions required by the emergency exit process."
              confirmText="Progress Exits"
              onConfirm={() => {
                progressExits.mutate(undefined);
                setShowProgressConfirm(false);
              }}
            />
            <ConfirmationDialog
              open={!!claimReview && claimReview.contextKey === claimQuote.contextKey}
              onOpenChange={(open) => {
                if (!open) setClaimReview(undefined);
              }}
              title={claimReview?.estimate ? "Claim Exits" : "Continue without estimate?"}
              description={
                claimReview ? exitReviewDescription(claimReview, formatBitcoinAmount) : ""
              }
              confirmText={claimReview?.estimate ? "Broadcast Claim" : "Broadcast without estimate"}
              onConfirm={handleClaim}
              isConfirmDisabled={isBusy}
            />
          </ScrollView>
        </TouchableWithoutFeedback>
      </KeyboardAvoidingView>
      <ExitDepositBottomSheet
        isOpen={!!deposit && deposit.walletId === staticVtxoPubkey && walletReady && isFocused}
        onClose={() => setDeposit(undefined)}
        walletId={staticVtxoPubkey}
        broadcastFeeSat={deposit?.broadcastFeeSat ?? 0}
      />
    </NoahSafeAreaView>
  );
};

export default UnilateralExitScreen;
