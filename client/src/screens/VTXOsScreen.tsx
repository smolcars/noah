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
import { useGetVtxos, useGetExpiringVtxos } from "~/hooks/useWallet";
import { BarkVtxo } from "react-native-nitro-ark";
import { useGetBlockHeight } from "~/hooks/useMarketData";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

const EXPIRED_COLOR = "#ef4444";
const EXPIRING_COLOR = "#f97316";

export type VTXOWithStatus = BarkVtxo & {
  isExpiring: boolean;
  isExpired: boolean;
};

type VtxoFilter = "all" | "active" | "expiring" | "expired" | "locked";

const filters: VtxoFilter[] = ["all", "active", "expiring", "expired", "locked"];

const VTXOsScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const [filter, setFilter] = useState<VtxoFilter>("all");

  const { data: allVtxos = [], isLoading: isLoadingAll } = useGetVtxos();
  const { data: expiringVtxos = [], isLoading: isLoadingExpiring } = useGetExpiringVtxos();
  const { data: blockHeight, isLoading: isLoadingBlockHeight } = useGetBlockHeight();

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

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <NoahSafeAreaView className="flex-1 bg-background">
        <View className="p-4 flex-1">
          <View className="flex-row items-center justify-between mb-8">
            <View className="flex-row items-center">
              <Pressable onPress={() => navigation.goBack()} className="mr-4">
                <Icon name="arrow-back-outline" size={24} color={iconColor} />
              </Pressable>
              <Text className="text-2xl font-bold text-foreground">VTXOs</Text>
            </View>
            <View className="flex-row items-center">
              <Text className="text-muted-foreground text-sm mr-2">
                {vtxosWithStatus.length} total
              </Text>
              <Icon name="cube-outline" size={24} color={iconColor} />
            </View>
          </View>

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
            <FlashList
              data={filteredVtxos}
              renderItem={({ item }: { item: VTXOWithStatus }) => (
                <View style={{ marginBottom: 8 }}>
                  <Pressable onPress={() => navigation.navigate("VTXODetail", { vtxo: item })}>
                    <View className="flex-row items-center p-4 bg-card rounded-lg">
                      <View className="mr-4">
                        <Icon name={getVtxoIcon(item)} size={24} color={getVtxoColor(item)} />
                      </View>
                      <View className="flex-1">
                        <View className="flex-row justify-between items-center">
                          <Label className="text-foreground text-base">
                            {formatBitcoinAmount(item.amount)}
                          </Label>
                        </View>
                        <Text className="text-muted-foreground text-sm mt-1" numberOfLines={1}>
                          Expiry: Block {item.expiry_height}
                        </Text>
                      </View>
                      <Icon name="chevron-forward-outline" size={24} color={iconColor} />
                    </View>
                  </Pressable>
                </View>
              )}
              keyExtractor={(item: VTXOWithStatus) => item.point}
              showsVerticalScrollIndicator={false}
              contentContainerStyle={{ paddingBottom: 50 }}
            />
          )}
        </View>
      </NoahSafeAreaView>
    </GestureHandlerRootView>
  );
};

export default VTXOsScreen;
