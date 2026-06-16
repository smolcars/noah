import { View, Pressable, ScrollView, Linking } from "react-native";
import { useRoute, useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { copyToClipboard } from "../lib/clipboardUtils";
import { useState } from "react";
import { COLORS } from "~/lib/styleConstants";
import type { BarkVtxo } from "react-native-nitro-ark";
import { useGetBlockHeight } from "~/hooks/useMarketData";
import { getMempoolTxUrl } from "~/constants";
import type { SettingsStackParamList } from "~/Navigators";
import { useGetExpiringVtxos, useGetVtxos, useRefreshExpiringVtxos } from "~/hooks/useWallet";
import { StatusBannerStrip } from "~/components/StatusBannerStrip";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

type VTXOWithStatus = BarkVtxo & {
  isExpiring: boolean;
  isExpired: boolean;
};

const EXPIRED_COLOR = "#ef4444";

const VTXODetailRow = ({
  label,
  value,
  copyable = false,
  explorerUrl,
}: {
  label: string;
  value: string;
  copyable?: boolean;
  explorerUrl?: string | null;
}) => {
  const [copied, setCopied] = useState(false);
  const iconColor = useIconColor();

  const onCopy = async () => {
    await copyToClipboard(value, {
      onCopy: () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1000);
      },
    });
  };

  return (
    <View className="flex-row justify-between items-center py-3 border-b border-border/10 last:border-b-0">
      <Text className="text-muted-foreground text-sm">{label}</Text>
      {copyable || explorerUrl ? (
        <View className="flex-row items-center gap-x-3 flex-shrink-0">
          {copyable ? (
            <Pressable onPress={onCopy} className="flex-row items-center gap-x-2 flex-shrink-0">
              <Text
                className="text-foreground text-sm text-right"
                ellipsizeMode="middle"
                numberOfLines={1}
                style={{ maxWidth: 150 }}
              >
                {value}
              </Text>
              {copied ? (
                <Icon name="checkmark-circle-outline" size={16} color={COLORS.SUCCESS} />
              ) : (
                <Icon name="copy-outline" size={16} color={iconColor} />
              )}
            </Pressable>
          ) : null}
          {explorerUrl ? (
            <Pressable
              onPress={() => Linking.openURL(explorerUrl)}
              hitSlop={10}
              className="h-8 w-8 items-center justify-center rounded-full bg-background"
            >
              <Icon name="open-outline" size={17} color={COLORS.BITCOIN_ORANGE} />
            </Pressable>
          ) : null}
        </View>
      ) : (
        <Text
          className="text-foreground text-sm text-right"
          ellipsizeMode="tail"
          numberOfLines={2}
          style={{ maxWidth: 200 }}
        >
          {value}
        </Text>
      )}
    </View>
  );
};

const VTXODetailScreen = () => {
  const route = useRoute();
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const iconColor = useIconColor();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const { data: blockHeight } = useGetBlockHeight();
  const { vtxo: routeVtxo } = route.params as { vtxo: VTXOWithStatus };
  const { data: allVtxos = [] } = useGetVtxos();
  const { data: expiringVtxos } = useGetExpiringVtxos();
  const refreshExpiringVtxos = useRefreshExpiringVtxos();
  const latestVtxo = allVtxos.find((item) => item.point === routeVtxo.point);
  const currentVtxo = latestVtxo ?? routeVtxo;
  const isLatestExpiring = expiringVtxos?.some((item) => item.point === currentVtxo.point);
  const vtxo: VTXOWithStatus = {
    ...currentVtxo,
    isExpiring: isLatestExpiring ?? routeVtxo.isExpiring,
    isExpired: routeVtxo.isExpired ?? false,
  };
  const anchorExplorerUrl = getMempoolTxUrl(vtxo.anchor_point);
  const isExpired =
    vtxo.state !== "Locked" &&
    (blockHeight !== undefined ? vtxo.expiry_height <= blockHeight : vtxo.isExpired);
  const canRefresh = vtxo.state !== "Locked" && (vtxo.isExpiring || isExpired);
  const statusLabel =
    vtxo.state === "Locked"
      ? "Locked"
      : isExpired
        ? "Expired"
        : vtxo.isExpiring
          ? "Expiring"
          : "Active";

  const getStatusColor = (vtxo: VTXOWithStatus) => {
    if (vtxo.state === "Locked") return "text-gray-500";
    if (isExpired) return "text-red-500";
    return vtxo.isExpiring ? "text-orange-500" : "text-green-500";
  };

  const getStatusIcon = (vtxo: VTXOWithStatus) => {
    if (vtxo.state === "Locked") return "lock-closed-outline";
    if (isExpired) return "alert-circle-outline";
    return vtxo.isExpiring ? "warning-outline" : "checkmark-circle-outline";
  };

  const getVtxoIcon = (vtxo: VTXOWithStatus) => {
    if (vtxo.state === "Locked") return "lock-closed-outline";
    if (isExpired) return "alert-circle-outline";
    return vtxo.isExpiring ? "warning-outline" : "cube-outline";
  };

  const getVtxoColor = (vtxo: VTXOWithStatus) => {
    if (vtxo.state === "Locked") return "#6b7280";
    if (isExpired) return EXPIRED_COLOR;
    return vtxo.isExpiring ? COLORS.BITCOIN_ORANGE : "#22c55e";
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="p-4 flex-1">
        <View className="flex-row items-center mb-8">
          <Pressable onPress={() => navigation.goBack()} className="mr-4">
            <Icon name="arrow-back-outline" size={24} color={iconColor} />
          </Pressable>
          <Text className="text-2xl font-bold text-foreground">VTXO Details</Text>
        </View>

        <ScrollView
          className="flex-1"
          showsVerticalScrollIndicator={false}
          contentContainerStyle={{ paddingBottom: 50 }}
        >
          {canRefresh ? (
            <StatusBannerStrip
              className="mb-4"
              title={isExpired ? "VTXO expired" : "VTXO expiring soon"}
              message="Refresh this VTXO to keep it available."
              icon={
                <Icon
                  name={isExpired ? "alert-circle-outline" : "warning-outline"}
                  size={16}
                  color={isExpired ? EXPIRED_COLOR : COLORS.BITCOIN_ORANGE}
                />
              }
              tone={isExpired ? "failed" : "info"}
              actionLabel="Refresh"
              actionBusyLabel="Refreshing"
              actionTextStyle={{ color: COLORS.BITCOIN_ORANGE }}
              isActionLoading={refreshExpiringVtxos.isPending}
              onActionPress={() => refreshExpiringVtxos.mutate()}
            />
          ) : null}

          <View className="items-center my-8">
            <View className="mb-4">
              <Icon name={getVtxoIcon(vtxo)} size={64} color={getVtxoColor(vtxo)} />
            </View>
            <Text className="text-3xl font-bold text-foreground mb-2">
              {formatBitcoinAmount(vtxo.amount)}
            </Text>
            <View className="flex-row items-center">
              <Icon name={getStatusIcon(vtxo)} size={20} color={getVtxoColor(vtxo)} />
              <Text className={`text-xl font-medium ml-2 ${getStatusColor(vtxo)}`}>
                {statusLabel}
              </Text>
            </View>
          </View>

          <View className="bg-card p-4 rounded-lg mb-4">
            <VTXODetailRow label="Amount" value={formatBitcoinAmount(vtxo.amount)} />
            <VTXODetailRow label="State" value={vtxo.state} />
            <VTXODetailRow label="Status" value={statusLabel} />
            <VTXODetailRow
              label="Current Block Height"
              value={blockHeight ? blockHeight.toLocaleString() : "Loading..."}
            />
            <VTXODetailRow label="Expiry Height" value={vtxo.expiry_height.toLocaleString()} />
            <VTXODetailRow
              label="Blocks Until Expiry"
              value={
                blockHeight
                  ? vtxo.expiry_height > blockHeight
                    ? `${(vtxo.expiry_height - blockHeight).toLocaleString()}`
                    : "Expired"
                  : "Loading..."
              }
            />
            <VTXODetailRow label="Exit Delta" value={vtxo.exit_delta.toString()} />
          </View>

          <View className="bg-card p-4 rounded-lg mb-4">
            <Text className="text-foreground text-lg font-semibold mb-3">Vtxo Details</Text>
            <VTXODetailRow label="ID" value={vtxo.id} copyable />
            <VTXODetailRow
              label="Anchor Point"
              value={vtxo.anchor_point}
              copyable
              explorerUrl={anchorExplorerUrl}
            />
            <VTXODetailRow label="Server Public Key" value={vtxo.server_pubkey} copyable />
          </View>
        </ScrollView>
      </View>
    </NoahSafeAreaView>
  );
};

export default VTXODetailScreen;
