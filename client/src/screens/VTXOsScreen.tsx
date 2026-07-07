import { View, Pressable, ScrollView } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { useState } from "react";
import { FlashList } from "@shopify/flash-list";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { Label } from "~/components/ui/label";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { SettingsStackParamList } from "~/Navigators";
import {
  useEstimateRefreshFee,
  useGetVtxos,
  useGetExpiringVtxos,
  useRefreshSelectedVtxos,
  useWalletSync,
} from "~/hooks/useWallet";
import { type BarkFeeEstimate, type BarkVtxo } from "react-native-nitro-ark";
import { useBtcToFiatRate, useGetBlockHeight } from "~/hooks/useMarketData";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { ConfirmationDialog } from "~/components/ConfirmationDialog";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import { useProfileStore } from "~/store/profileStore";
import { cn } from "~/lib/utils";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { PLATFORM } from "~/constants";
import { useAlert } from "~/contexts/AlertProvider";
import { useSafeAreaInsets } from "react-native-safe-area-context";

const EXPIRED_COLOR = "#ef4444";
const EXPIRING_COLOR = "#f97316";

export type VTXOWithStatus = BarkVtxo & {
  isExpiring: boolean;
  isExpired: boolean;
};

type VtxoFilter = "all" | "active" | "expiring" | "expired" | "locked";

const filters: VtxoFilter[] = ["all", "active", "expiring", "expired", "locked"];

const RefreshPlanRow = ({
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
    <Text
      className={cn("ml-3 flex-shrink text-right text-sm font-semibold text-foreground", valueClassName)}
      numberOfLines={1}
      adjustsFontSizeToFit
      minimumFontScale={0.8}
    >
      {value}
    </Text>
  </View>
);

const VTXOsScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const bottomTabBarHeight = useBottomTabBarHeight();
  const { bottom: safeBottomInset } = useSafeAreaInsets();
  const { showAlert } = useAlert();
  const [filter, setFilter] = useState<VtxoFilter>("all");
  const [isSelecting, setIsSelecting] = useState(false);
  const [selectedVtxoIds, setSelectedVtxoIds] = useState<Set<string>>(new Set());
  const [refreshEstimate, setRefreshEstimate] = useState<BarkFeeEstimate | null>(null);
  const [isRefreshDialogOpen, setIsRefreshDialogOpen] = useState(false);

  const { data: allVtxos = [], isLoading: isLoadingAll } = useGetVtxos();
  const { data: expiringVtxos = [], isLoading: isLoadingExpiring } = useGetExpiringVtxos();
  const { data: blockHeight, isLoading: isLoadingBlockHeight } = useGetBlockHeight();
  const { data: btcToFiatRate } = useBtcToFiatRate();
  const estimateRefreshFee = useEstimateRefreshFee();
  const refreshSelectedVtxos = useRefreshSelectedVtxos();
  const walletSync = useWalletSync();

  // Combine and deduplicate VTXOs by point, marking expiring ones
  const expiringPoints = new Set(expiringVtxos.map((vtxo) => vtxo.point));

  const vtxosWithStatus: VTXOWithStatus[] = allVtxos.map((vtxo) => ({
    ...vtxo,
    isExpiring: expiringPoints.has(vtxo.point),
    isExpired:
      vtxo.state !== "Locked" && blockHeight !== undefined && vtxo.expiry_height <= blockHeight,
  }));

  const activeVtxos = vtxosWithStatus.filter(
    (vtxo) => !vtxo.isExpiring && !vtxo.isExpired && vtxo.state === "Spendable",
  );
  const expiringOnlyVtxos = vtxosWithStatus.filter((vtxo) => vtxo.isExpiring && !vtxo.isExpired);
  const expiredVtxos = vtxosWithStatus.filter((vtxo) => vtxo.isExpired);
  const lockedVtxos = vtxosWithStatus.filter((vtxo) => vtxo.state === "Locked");

  const isLoading = isLoadingAll || isLoadingExpiring || isLoadingBlockHeight;

  const filteredVtxos = (() => {
    switch (filter) {
      case "active":
        return activeVtxos;
      case "expiring":
        return expiringOnlyVtxos;
      case "expired":
        return expiredVtxos;
      case "locked":
        return lockedVtxos;
      default:
        return vtxosWithStatus;
    }
  })();

  const selectedVtxos = vtxosWithStatus.filter((vtxo) => selectedVtxoIds.has(vtxo.id));
  const selectedAmountSat = selectedVtxos.reduce((total, vtxo) => total + vtxo.amount, 0);
  const selectedVtxoIdList = selectedVtxos.map((vtxo) => vtxo.id);
  const selectedAfterFeeSat = Math.max(selectedAmountSat - (refreshEstimate?.fee_sat ?? 0), 0);
  const isBusy = estimateRefreshFee.isPending || refreshSelectedVtxos.isPending || walletSync.isPending;
  const hasSelectableVtxos = vtxosWithStatus.some((vtxo) => vtxo.state !== "Locked");

  const getVtxoIcon = (vtxo: VTXOWithStatus) => {
    if (vtxo.state === "Locked") return "lock-closed-outline";
    if (vtxo.isExpired) return "alert-circle-outline";
    if (vtxo.isExpiring) return "warning-outline";
    return "cube-outline";
  };

  const getVtxoColor = (vtxo: VTXOWithStatus) => {
    if (vtxo.state === "Locked") return "#6b7280"; // Gray for locked
    if (vtxo.isExpired) return EXPIRED_COLOR; // Red for expired
    if (vtxo.isExpiring) return EXPIRING_COLOR; // Orange for expiring
    return "#22c55e"; // Green for active
  };

  const getFilterLabel = (vtxoFilter: VtxoFilter) => {
    switch (vtxoFilter) {
      case "all":
        return "All";
      case "active":
        return "Active";
      case "expiring":
        return "Expiring";
      case "expired":
        return "Expired";
      case "locked":
        return "Locked";
    }
  };

  const getFilterCount = (vtxoFilter: VtxoFilter) => {
    switch (vtxoFilter) {
      case "active":
        return activeVtxos.length;
      case "expiring":
        return expiringOnlyVtxos.length;
      case "expired":
        return expiredVtxos.length;
      case "locked":
        return lockedVtxos.length;
      default:
        return null;
    }
  };

  const formatFiatValue = (amountSat: number) => {
    if (btcToFiatRate === undefined) {
      return "Unavailable";
    }

    return formatFiatAmount(satsToFiat(amountSat, btcToFiatRate, fiatCurrency), fiatCurrency);
  };

  const formatBitcoinWithFiat = (amountSat: number) => {
    const fiatValue = formatFiatValue(amountSat);
    if (fiatValue === "Unavailable") {
      return formatBitcoinAmount(amountSat);
    }

    return `${formatBitcoinAmount(amountSat)} (${fiatValue})`;
  };

  const clearSelection = () => {
    setSelectedVtxoIds(new Set());
    setRefreshEstimate(null);
  };

  const stopSelecting = () => {
    setIsSelecting(false);
    clearSelection();
  };

  const toggleSelectionMode = () => {
    if (isSelecting) {
      stopSelecting();
      return;
    }

    setIsSelecting(true);
  };

  const toggleVtxoSelection = (vtxo: VTXOWithStatus) => {
    if (vtxo.state === "Locked" || isBusy) {
      return;
    }

    setSelectedVtxoIds((current) => {
      const next = new Set(current);
      if (next.has(vtxo.id)) {
        next.delete(vtxo.id);
      } else {
        next.add(vtxo.id);
      }
      return next;
    });
  };

  const selectExpiringVtxos = () => {
    const ids = vtxosWithStatus
      .filter((vtxo) => vtxo.state !== "Locked" && (vtxo.isExpiring || vtxo.isExpired))
      .map((vtxo) => vtxo.id);
    setSelectedVtxoIds(new Set(ids));
  };

  const selectVisibleVtxos = () => {
    const ids = filteredVtxos.filter((vtxo) => vtxo.state !== "Locked").map((vtxo) => vtxo.id);
    setSelectedVtxoIds(new Set(ids));
  };

  const handleRefreshPress = () => {
    if (selectedVtxoIdList.length === 0) {
      return;
    }

    setRefreshEstimate(null);
    estimateRefreshFee.mutate(selectedVtxoIdList, {
      onSuccess: (estimate) => {
        setRefreshEstimate(estimate);
        setIsRefreshDialogOpen(true);
      },
    });
  };

  const handleConfirmRefresh = () => {
    if (selectedVtxoIdList.length === 0) {
      return;
    }

    walletSync.mutate(undefined, {
      onSuccess: () => {
        refreshSelectedVtxos.mutate(selectedVtxoIdList, {
          onSuccess: () => {
            setIsRefreshDialogOpen(false);
            stopSelecting();
          },
        });
      },
      onError: (error) => {
        showAlert({
          title: "Sync failed",
          description: error instanceof Error ? error.message : String(error),
        });
      },
    });
  };

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <NoahSafeAreaView
        className="flex-1 bg-background"
        style={{
          paddingBottom: PLATFORM === "ios" ? bottomTabBarHeight : safeBottomInset,
        }}
      >
        <View className="p-4 flex-1">
          <View className="flex-row items-center justify-between mb-8">
            <View className="flex-row items-center">
              <Pressable
                onPress={isSelecting ? stopSelecting : () => navigation.goBack()}
                className="mr-4"
                disabled={isBusy}
              >
                <Icon
                  name={isSelecting ? "close-outline" : "arrow-back-outline"}
                  size={24}
                  color={iconColor}
                />
              </Pressable>
              <Text className="text-2xl font-bold text-foreground">
                {isSelecting ? "Select VTXOs" : "VTXOs"}
              </Text>
            </View>
            <View className="flex-row items-center">
              {hasSelectableVtxos ? (
                <Pressable
                  onPress={toggleSelectionMode}
                  disabled={isLoading || isBusy}
                  className={cn(
                    "h-9 items-center justify-center rounded-full px-4",
                    isSelecting ? "bg-card" : "bg-primary",
                    (isLoading || isBusy) && "opacity-50",
                  )}
                >
                  <Text
                    className={cn(
                      "text-sm font-semibold",
                      isSelecting ? "text-foreground" : "text-primary-foreground",
                    )}
                  >
                    {isSelecting ? "Cancel" : "Select"}
                  </Text>
                </Pressable>
              ) : null}
            </View>
          </View>

          {isSelecting ? (
            <View className="mb-4 gap-3 rounded-lg border border-border bg-card p-4">
              <View className="flex-row items-center justify-between">
                <View>
                  <Text className="text-sm text-muted-foreground">Selected</Text>
                  <Text className="mt-1 text-lg font-semibold text-foreground">
                    {selectedVtxos.length} {selectedVtxos.length === 1 ? "VTXO" : "VTXOs"}
                  </Text>
                </View>
                <Text className="text-right text-base font-semibold text-foreground">
                  {formatBitcoinAmount(selectedAmountSat)}
                </Text>
              </View>
              <View className="flex-row gap-2">
                <Pressable
                  onPress={selectExpiringVtxos}
                  disabled={isBusy}
                  className="h-9 flex-1 items-center justify-center rounded-full bg-background px-3"
                >
                  <Text className="text-sm font-medium text-foreground">Select expiring</Text>
                </Pressable>
                <Pressable
                  onPress={selectVisibleVtxos}
                  disabled={isBusy}
                  className="h-9 flex-1 items-center justify-center rounded-full bg-background px-3"
                >
                  <Text className="text-sm font-medium text-foreground">Select visible</Text>
                </Pressable>
                <Pressable
                  onPress={clearSelection}
                  disabled={isBusy || selectedVtxos.length === 0}
                  className="h-9 items-center justify-center rounded-full bg-background px-3"
                >
                  <Text className="text-sm font-medium text-muted-foreground">Clear</Text>
                </Pressable>
              </View>
            </View>
          ) : null}

          <View className="mb-4 h-8">
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              style={{ flexGrow: 0 }}
              contentContainerStyle={{ gap: 8, paddingRight: 4 }}
            >
              {filters.map((f) => (
                <Pressable
                  key={f}
                  onPress={() => setFilter(f)}
                  className={`h-8 items-center justify-center rounded-full px-3 ${
                    filter === f ? "bg-primary" : "bg-card"
                  }`}
                >
                  <Text
                    className={`text-sm ${
                      filter === f ? "text-primary-foreground" : "text-foreground"
                    }`}
                  >
                    {getFilterLabel(f)}
                    {getFilterCount(f) !== null ? ` (${getFilterCount(f)})` : ""}
                  </Text>
                </Pressable>
              ))}
            </ScrollView>
          </View>

          {isLoading ? (
            <View className="flex-1 items-center justify-center">
              <Text className="text-muted-foreground">Loading VTXOs...</Text>
            </View>
          ) : filteredVtxos.length === 0 ? (
            <View className="flex-1 items-center justify-center">
              <Icon name="cube-outline" size={48} color="#666" />
              <Text className="text-muted-foreground mt-4 text-center">
                {filter === "all"
                  ? "No VTXOs found"
                  : filter === "active"
                    ? "No active VTXOs found"
                    : filter === "expiring"
                      ? "No expiring VTXOs found"
                      : filter === "expired"
                        ? "No expired VTXOs found"
                        : "No locked VTXOs found"}
              </Text>
              <Text className="text-muted-foreground text-sm mt-2 text-center">
                You have no VTXOs.
              </Text>
            </View>
          ) : (
            <>
              <FlashList
                data={filteredVtxos}
                renderItem={({ item }: { item: VTXOWithStatus }) => {
                  const isSelected = selectedVtxoIds.has(item.id);
                  const isLocked = item.state === "Locked";
                  return (
                    <View style={{ marginBottom: 8 }}>
                      <Pressable
                        disabled={isBusy && isSelecting}
                        onPress={() => {
                          if (isSelecting) {
                            toggleVtxoSelection(item);
                            return;
                          }

                          navigation.navigate("VTXODetail", { vtxo: item });
                        }}
                      >
                        <View
                          className={cn(
                            "flex-row items-center rounded-lg border p-4",
                            isSelected
                              ? "border-primary bg-primary/10"
                              : "border-transparent bg-card",
                            isSelecting && isLocked && "opacity-50",
                          )}
                        >
                          <View className="mr-4">
                            <Icon name={getVtxoIcon(item)} size={24} color={getVtxoColor(item)} />
                          </View>
                          <View className="flex-1">
                            <View className="flex-row justify-between items-center">
                              <Label className="text-foreground text-base">
                                {formatBitcoinAmount(item.amount)}
                              </Label>
                            </View>
                            <Text
                              className="text-muted-foreground text-sm mt-1"
                              numberOfLines={1}
                            >
                              Expiry: Block {item.expiry_height}
                            </Text>
                          </View>
                          {isSelecting ? (
                            <Icon
                              name={isSelected ? "checkmark-circle" : "ellipse-outline"}
                              size={26}
                              color={isSelected ? getVtxoColor(item) : iconColor}
                            />
                          ) : (
                            <Icon name="chevron-forward-outline" size={24} color={iconColor} />
                          )}
                        </View>
                      </Pressable>
                    </View>
                  );
                }}
                keyExtractor={(item: VTXOWithStatus) => item.point}
                showsVerticalScrollIndicator={false}
                contentContainerStyle={{ paddingBottom: isSelecting ? 132 : 50 }}
              />

              {isSelecting ? (
                <View className="absolute bottom-4 left-4 right-4 rounded-2xl border border-border bg-background p-4 shadow-lg">
                  <View className="mb-3 flex-row items-center justify-between">
                    <Text className="text-sm text-muted-foreground">
                      {selectedVtxos.length} selected
                    </Text>
                    <Text className="text-base font-semibold text-foreground">
                      {formatBitcoinAmount(selectedAmountSat)}
                    </Text>
                  </View>
                  <NativeNoahButton
                    label="Refresh"
                    loadingLabel="Estimating..."
                    onPress={handleRefreshPress}
                    disabled={selectedVtxos.length === 0 || refreshSelectedVtxos.isPending}
                    isLoading={estimateRefreshFee.isPending}
                    fullWidth
                  />
                </View>
              ) : null}
            </>
          )}
        </View>

        <ConfirmationDialog
          title="Refresh VTXOs?"
          description="This refreshes the selected VTXOs in a delegated Ark round."
          confirmText="Refresh"
          cancelText="Cancel"
          confirmVariant="default"
          open={isRefreshDialogOpen}
          onOpenChange={(open) => {
            if (walletSync.isPending || refreshSelectedVtxos.isPending) {
              return;
            }
            setIsRefreshDialogOpen(open);
            if (!open) {
              setRefreshEstimate(null);
            }
          }}
          onConfirm={handleConfirmRefresh}
          onCancel={() => {
            setIsRefreshDialogOpen(false);
            setRefreshEstimate(null);
          }}
          isConfirmDisabled={walletSync.isPending || refreshSelectedVtxos.isPending || !refreshEstimate}
          contentClassName="w-[92%] rounded-2xl border-border bg-background p-5"
          headerClassName="gap-2"
          titleClassName="text-2xl font-bold text-foreground"
          descriptionClassName="text-base leading-6 text-muted-foreground"
          footerClassName="mt-1 gap-3 space-x-0"
          cancelClassName="h-12 rounded-xl border-border bg-background"
          actionClassName="h-12 rounded-xl"
        >
          {refreshEstimate ? (
            <View className="gap-3">
              <View className="rounded-xl border border-border/70 bg-card/80 p-4">
                <Text className="text-sm font-medium text-muted-foreground">Amount selected</Text>
                <Text
                  className="mt-1 text-3xl font-bold text-foreground"
                  numberOfLines={1}
                  adjustsFontSizeToFit
                  minimumFontScale={0.72}
                >
                  {formatBitcoinWithFiat(selectedAmountSat)}
                </Text>
              </View>

              <View className="rounded-xl border border-border/70 bg-card/60 px-3 py-1">
                <RefreshPlanRow
                  label="VTXOs selected"
                  value={selectedVtxos.length.toLocaleString()}
                />
                <View className="h-px bg-border/70" />
                <RefreshPlanRow
                  label="Refresh fee"
                  value={formatBitcoinWithFiat(refreshEstimate.fee_sat)}
                  valueClassName="text-red-500"
                />
                <View className="h-px bg-border/70" />
                <RefreshPlanRow
                  label="Amount after fee"
                  value={formatBitcoinWithFiat(selectedAfterFeeSat)}
                  valueClassName="text-green-500"
                />
              </View>
            </View>
          ) : null}
        </ConfirmationDialog>
      </NoahSafeAreaView>
    </GestureHandlerRootView>
  );
};

export default VTXOsScreen;
