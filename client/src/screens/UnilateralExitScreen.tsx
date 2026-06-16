import React, { useState } from "react";
import {
  Keyboard,
  KeyboardAvoidingView,
  Linking,
  Pressable,
  ScrollView,
  TouchableWithoutFeedback,
  View,
} from "react-native";
import { useNavigation, useRoute, type RouteProp } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { AlertTriangle } from "lucide-react-native";
import { validateBitcoinAddress } from "bip-321";
import { Text } from "~/components/ui/text";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { NoahButton } from "~/components/ui/NoahButton";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { ConfirmationDialog } from "~/components/ConfirmationDialog";
import { useIconColor } from "~/hooks/useTheme";
import {
  useClaimExits,
  useExitOverview,
  useProgressExits,
  useStartVtxoExit,
  useStartWalletExit,
  useSyncExits,
} from "~/hooks/useUnilateralExit";
import { APP_VARIANT } from "~/config";
import { getMempoolTxUrl } from "~/constants";
import {
  buildExitTimelineItems,
  EXIT_STATE_LABELS,
  EXIT_STATE_ORDER,
  formatBlocksRemaining,
  getExitBlockRows,
  getExitStatusText,
  isClaimableExit,
  truncateMiddle,
} from "~/lib/exitTimeline";
import { COLORS } from "~/lib/styleConstants";
import { cn, isNetworkMatch } from "~/lib/utils";
import type { SettingsStackParamList } from "~/Navigators";
import type {
  ExitProgressState,
  ExitStateDetails,
  ExitStatusResult,
  ExitVtxoResult,
} from "react-native-nitro-ark";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

type UnilateralExitRouteProp = RouteProp<SettingsStackParamList, "UnilateralExit">;
type IconName = React.ComponentProps<typeof Icon>["name"];

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
};

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
}: {
  exit: ExitVtxoResult;
  status?: ExitStatusResult;
  history?: ExitProgressState[];
  currentBlockHeight?: number;
  onPress: () => void;
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

  return (
    <Pressable onPress={onPress} className="mb-3 rounded-lg border border-border bg-card p-4">
      <View>
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
      </View>
    </Pressable>
  );
};

const EmptyExitState = ({ onStart }: { onStart: () => void }) => (
  <View className="items-center rounded-lg border border-border bg-card px-4 py-8">
    <Icon name="shield-outline" size={40} color="#8e8e93" />
    <Text className="mt-4 text-center text-lg font-semibold text-foreground">
      No emergency exits
    </Text>
    <Text className="mt-2 text-center text-sm leading-5 text-muted-foreground">
      Start only if the Ark server is unavailable and normal offboarding cannot be used.
    </Text>
    <NoahButton onPress={onStart} className="mt-5 w-full">
      Start Wallet Exit
    </NoahButton>
  </View>
);

const UnilateralExitScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const route = useRoute<UnilateralExitRouteProp>();
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const selectedVtxoIds = route.params?.vtxoIds;

  const [destinationAddress, setDestinationAddress] = useState("");
  const [showStartConfirm, setShowStartConfirm] = useState(false);
  const [showProgressConfirm, setShowProgressConfirm] = useState(false);
  const [showClaimConfirm, setShowClaimConfirm] = useState(false);

  const overviewQuery = useExitOverview();
  const startWalletExit = useStartWalletExit();
  const startVtxoExit = useStartVtxoExit();
  const progressExits = useProgressExits();
  const syncExits = useSyncExits();
  const claimExits = useClaimExits();

  const overview = overviewQuery.data;
  const exits = overview?.exits ?? [];
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
  const isBusy =
    startWalletExit.isPending ||
    startVtxoExit.isPending ||
    progressExits.isPending ||
    syncExits.isPending ||
    claimExits.isPending;
  const canStartNewExit = (overview?.spendableVtxoCount ?? 0) > 0 && !isBusy;

  const startLabel = selectedVtxoIds?.length ? "Start Selected Exit" : "Start Wallet Exit";
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

  const handleStart = () => {
    if (selectedVtxoIds?.length) {
      startVtxoExit.mutate(selectedVtxoIds);
    } else {
      startWalletExit.mutate();
    }
    setShowStartConfirm(false);
  };

  const handleClaim = () => {
    if (!isValidDestination) {
      return;
    }

    claimExits.mutate({
      vtxoIds: claimableIds,
      destinationAddress: trimmedDestination,
    });
    setShowClaimConfirm(false);
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
                <Pressable onPress={() => navigation.goBack()} className="mr-4">
                  <Icon name="arrow-back-outline" size={24} color={iconColor} />
                </Pressable>
                <Text className="text-2xl font-bold text-foreground">Emergency Exit</Text>
              </View>
              <Button
                variant="ghost"
                size="icon"
                onPress={() => syncExits.mutate()}
                disabled={isBusy}
              >
                {syncExits.isPending ? (
                  <NoahActivityIndicator />
                ) : (
                  <Icon name="refresh-outline" size={22} color={iconColor} />
                )}
              </Button>
            </View>

            <Alert icon={AlertTriangle} className="mb-5 border-amber-500/40 bg-amber-500/10">
              <AlertTitle className="text-amber-700 dark:text-amber-300">
                Emergency use only
              </AlertTitle>
              <AlertDescription className="text-amber-700/90 dark:text-amber-200/90">
                Use this only if the Ark server is unresponsive or uncooperative. In normal
                circumstances, use Offboard Ark.
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
              <EmptyExitState onStart={() => setShowStartConfirm(true)} />
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
                      current chain height. Sync status to refresh chain-derived state.
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
                      transaction to confirm, then sync status to mark them claimed.
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
                    />
                  ))}
                </View>

                <View className="mb-5 flex-row gap-x-3">
                  <NoahButton
                    className="flex-1"
                    style={{ backgroundColor: "#e5e7eb" }}
                    textClassName="font-bold text-base"
                    onPress={() => syncExits.mutate()}
                    disabled={isBusy}
                    isLoading={syncExits.isPending}
                  >
                    Sync Status
                  </NoahButton>
                  {canStartNewExit ? (
                    <NoahButton
                      className="flex-1"
                      textClassName="font-bold text-base"
                      onPress={() => setShowStartConfirm(true)}
                      disabled={isBusy}
                    >
                      Start New Exit
                    </NoahButton>
                  ) : null}
                  {overview?.hasPending ? (
                    <NoahButton
                      className="flex-1"
                      textClassName="font-bold text-base"
                      onPress={() => setShowProgressConfirm(true)}
                      disabled={isBusy}
                      isLoading={progressExits.isPending}
                    >
                      Progress
                    </NoahButton>
                  ) : null}
                </View>

                {claimable.length > 0 ? (
                  <View className="rounded-lg border border-border bg-card p-4">
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
                    <NoahButton
                      className="mt-4"
                      disabled={!isValidDestination || isBusy}
                      isLoading={claimExits.isPending}
                      onPress={() => setShowClaimConfirm(true)}
                    >
                      Claim Claimable Exits
                    </NoahButton>
                  </View>
                ) : null}
              </>
            )}

            <ConfirmationDialog
              open={showStartConfirm}
              onOpenChange={setShowStartConfirm}
              title="Start Emergency Exit"
              description="This starts unilateral exit tracking for your funds. Use this only if normal offboarding is unavailable."
              confirmText={startLabel}
              onConfirm={handleStart}
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
              open={showClaimConfirm}
              onOpenChange={setShowClaimConfirm}
              title="Claim Exits"
              description="This broadcasts the final claim transaction for all currently claimable exits."
              confirmText="Broadcast Claim"
              onConfirm={handleClaim}
            />
          </ScrollView>
        </TouchableWithoutFeedback>
      </KeyboardAvoidingView>
    </NoahSafeAreaView>
  );
};

export default UnilateralExitScreen;
