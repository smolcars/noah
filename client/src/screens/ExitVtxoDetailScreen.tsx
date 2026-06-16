import React from "react";
import { Linking, Pressable, ScrollView, View } from "react-native";
import { useNavigation, useRoute, type RouteProp } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Button } from "~/components/ui/button";
import { Text } from "~/components/ui/text";
import { useIconColor } from "~/hooks/useTheme";
import { useExitOverview, useSyncExits } from "~/hooks/useUnilateralExit";
import {
  buildExitTimelineItems,
  EXIT_STATE_LABELS,
  formatBlocksRemaining,
  getExitBlockRows,
  getExitStatusText,
  truncateMiddle,
  type ExitDetailRow,
  type ExitTimelineItem,
} from "~/lib/exitTimeline";
import { COLORS } from "~/lib/styleConstants";
import { cn } from "~/lib/utils";
import type { SettingsStackParamList } from "~/Navigators";
import type { ExitProgressState } from "react-native-nitro-ark";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

type ExitVtxoDetailRouteProp = RouteProp<SettingsStackParamList, "ExitVtxoDetail">;
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
    return <Text className="text-right text-sm font-medium text-foreground">{value}</Text>;
  }

  return (
    <Pressable
      onPress={() => Linking.openURL(explorerUrl)}
      hitSlop={10}
      className="flex-row items-center justify-end gap-x-1"
    >
      <Text className="text-right text-sm font-medium text-foreground">{value}</Text>
      <Icon name="open-outline" size={15} color={COLORS.BITCOIN_ORANGE} />
    </Pressable>
  );
};

const DetailRow = ({ row }: { row: ExitDetailRow }) => (
  <View className="flex-row items-center justify-between border-b border-border/30 py-3 last:border-b-0">
    <Text className="mr-3 text-sm text-muted-foreground">{row.label}</Text>
    <View className="flex-1">
      <ExplorerValue value={row.value} explorerUrl={row.explorerUrl} />
    </View>
  </View>
);

const TimelineRow = ({ item, isLast }: { item: ExitTimelineItem; isLast: boolean }) => {
  const tone = stateTone(item.state);
  const heightLabel =
    item.startHeight && item.endHeight && item.startHeight !== item.endHeight
      ? `${item.startHeight} - ${item.endHeight}`
      : item.endHeight
        ? `${item.endHeight}`
        : undefined;

  return (
    <View className="flex-row">
      <View className="w-9 items-center">
        <View
          className={cn(
            "h-9 w-9 items-center justify-center rounded-full border",
            tone.bgClassName,
          )}
        >
          <Icon name={tone.icon} size={18} color={tone.color} />
        </View>
        {!isLast ? <View className="w-px flex-1 bg-border" /> : null}
      </View>
      <View className="ml-3 flex-1 pb-5">
        <View className="rounded-lg border border-border bg-card p-4">
          <View className="flex-row items-start justify-between">
            <View className="flex-1">
              <Text className="text-base font-semibold text-foreground">
                {item.label}
                {item.count > 1 ? ` x${item.count}` : ""}
              </Text>
              {heightLabel ? (
                <Text className="mt-1 text-xs text-muted-foreground">Block/tip {heightLabel}</Text>
              ) : null}
            </View>
            {item.isCurrent ? (
              <View className={cn("rounded-full border px-2 py-1", tone.bgClassName)}>
                <Text className={cn("text-xs font-semibold", tone.className)}>Current</Text>
              </View>
            ) : null}
          </View>
          <Text className="mt-3 text-sm leading-5 text-muted-foreground">{item.description}</Text>
          {item.details.length > 0 ? (
            <View className="mt-3 rounded-md border border-border/60 bg-background/60 px-3">
              {item.details.map((row) => (
                <DetailRow key={`${item.state}-${row.label}-${row.value}`} row={row} />
              ))}
            </View>
          ) : null}
        </View>
      </View>
    </View>
  );
};

const ExitVtxoDetailScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const route = useRoute<ExitVtxoDetailRouteProp>();
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const overviewQuery = useExitOverview();
  const syncExits = useSyncExits();
  const overview = overviewQuery.data;
  const exit = overview?.exits.find((item) => item.vtxo_id === route.params.vtxoId);
  const status = exit ? overview?.statuses[exit.vtxo_id] : undefined;
  const state = status?.state ?? exit?.state;
  const details = status?.state_details ?? exit?.state_details;
  const tone = state ? stateTone(state) : stateTone("Start");
  const timelineItems =
    exit && state
      ? buildExitTimelineItems({
          history: status?.history ?? exit.history,
          historyDetails:
            status?.history_details && status.history_details.length > 0
              ? status.history_details
              : exit.history_details,
          currentState: state,
          currentDetails: details,
          currentBlockHeight: overview?.blockHeight,
        })
      : [];
  const blockRows =
    state && details
      ? getExitBlockRows({ state, details, currentBlockHeight: overview?.blockHeight })
      : [];
  const claimableHeight = details?.claimable_height ?? details?.claimable_since?.height;
  const remaining = formatBlocksRemaining(overview?.blockHeight, claimableHeight);

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <ScrollView
        className="flex-1"
        contentContainerStyle={{ padding: 16, paddingBottom: 56 }}
        showsVerticalScrollIndicator={false}
      >
        <View className="mb-8 flex-row items-center justify-between">
          <View className="flex-row items-center">
            <Pressable onPress={() => navigation.goBack()} className="mr-4">
              <Icon name="arrow-back-outline" size={24} color={iconColor} />
            </Pressable>
            <Text className="text-2xl font-bold text-foreground">Exit Timeline</Text>
          </View>
          <Button
            variant="ghost"
            size="icon"
            onPress={() => syncExits.mutate()}
            disabled={syncExits.isPending}
          >
            {syncExits.isPending ? (
              <NoahActivityIndicator />
            ) : (
              <Icon name="refresh-outline" size={22} color={iconColor} />
            )}
          </Button>
        </View>

        {overviewQuery.isLoading ? (
          <View className="items-center py-12">
            <NoahActivityIndicator />
            <Text className="mt-3 text-muted-foreground">Loading exit timeline...</Text>
          </View>
        ) : overviewQuery.error ? (
          <View className="rounded-lg border border-destructive bg-destructive/10 p-4">
            <Text className="font-semibold text-destructive">Unable to load exit</Text>
            <Text className="mt-2 text-sm text-destructive">{overviewQuery.error.message}</Text>
          </View>
        ) : !exit || !state || !details ? (
          <View className="rounded-lg border border-border bg-card p-4">
            <Text className="text-base font-semibold text-foreground">Exit not found</Text>
            <Text className="mt-2 text-sm leading-5 text-muted-foreground">
              This VTXO is not currently tracked as an emergency exit.
            </Text>
          </View>
        ) : (
          <>
            <View className="mb-5 rounded-lg border border-border bg-card p-4">
              <View className="flex-row items-start justify-between">
                <View className="flex-1">
                  <Text className="text-3xl font-bold text-foreground">
                    {formatBitcoinAmount(exit.amount_sat)}
                  </Text>
                  <Text className="mt-2 text-base font-medium text-foreground">
                    {getExitStatusText({
                      state,
                      details,
                      currentBlockHeight: overview?.blockHeight,
                    })}
                  </Text>
                  <Text className="mt-2 text-sm text-muted-foreground">
                    {truncateMiddle(exit.vtxo_id, 14, 12)}
                  </Text>
                </View>
                <View className={cn("rounded-full border px-3 py-2", tone.bgClassName)}>
                  <Text className={cn("text-sm font-semibold", tone.className)}>
                    {EXIT_STATE_LABELS[state]}
                  </Text>
                </View>
              </View>
            </View>

            <View className="mb-5 rounded-lg border border-border bg-card p-4">
              <Text className="mb-3 text-lg font-semibold text-foreground">Block Status</Text>
              <View className="flex-row gap-x-4">
                <View className="flex-1">
                  <Text className="text-xs uppercase text-muted-foreground">Current Height</Text>
                  <Text className="mt-1 text-base font-semibold text-foreground">
                    {overview?.blockHeight !== undefined ? overview.blockHeight : "Unknown"}
                  </Text>
                </View>
                <View className="flex-1">
                  <Text className="text-xs uppercase text-muted-foreground">Exit Tip</Text>
                  <Text className="mt-1 text-base font-semibold text-foreground">
                    {details.tip_height}
                  </Text>
                </View>
              </View>
              <View className="mt-4 flex-row gap-x-4">
                <View className="flex-1">
                  <Text className="text-xs uppercase text-muted-foreground">Claimable Height</Text>
                  <Text className="mt-1 text-base font-semibold text-foreground">
                    {claimableHeight ?? "Unknown"}
                  </Text>
                </View>
                <View className="flex-1">
                  <Text className="text-xs uppercase text-muted-foreground">Remaining</Text>
                  <Text className="mt-1 text-base font-semibold text-foreground">
                    {remaining ?? "Unknown"}
                  </Text>
                </View>
              </View>
            </View>

            {blockRows.length > 0 ? (
              <View className="mb-5 rounded-lg border border-border bg-card p-4">
                <Text className="mb-1 text-lg font-semibold text-foreground">Current State</Text>
                <View className="mt-2 rounded-md border border-border/60 bg-background/60 px-3">
                  {blockRows.map((row) => (
                    <DetailRow key={`${row.label}-${row.value}`} row={row} />
                  ))}
                </View>
              </View>
            ) : null}

            <View className="mb-2">
              <Text className="mb-4 text-lg font-semibold text-foreground">Timeline</Text>
              {timelineItems.map((item, index) => (
                <TimelineRow
                  key={`${item.state}-${index}`}
                  item={item}
                  isLast={index === timelineItems.length - 1}
                />
              ))}
            </View>
          </>
        )}
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default ExitVtxoDetailScreen;
