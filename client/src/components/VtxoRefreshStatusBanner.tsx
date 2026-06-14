import Icon from "@react-native-vector-icons/ionicons";
import { StatusBannerStrip } from "~/components/StatusBannerStrip";
import { useGetExpiringVtxos, useRefreshExpiringVtxos } from "~/hooks/useWallet";
import { COLORS } from "~/lib/styleConstants";

export const VtxoRefreshStatusBanner = () => {
  const { data: expiringVtxos = [] } = useGetExpiringVtxos();
  const refreshExpiringVtxos = useRefreshExpiringVtxos();

  if (expiringVtxos.length === 0) {
    return null;
  }

  const vtxoLabel = expiringVtxos.length === 1 ? "VTXO" : "VTXOs";

  return (
    <StatusBannerStrip
      className="mx-4 mt-3 mb-1"
      title={expiringVtxos.length === 1 ? "VTXO expiring soon" : "VTXOs expiring soon"}
      message={`Refresh ${expiringVtxos.length} ${vtxoLabel} to keep ${expiringVtxos.length === 1 ? "it" : "them"} available.`}
      icon={<Icon name="warning-outline" size={16} color={COLORS.BITCOIN_ORANGE} />}
      tone="info"
      actionLabel="Refresh"
      actionBusyLabel="Refreshing"
      actionTextStyle={{ color: COLORS.BITCOIN_ORANGE }}
      isActionLoading={refreshExpiringVtxos.isPending}
      onActionPress={() => refreshExpiringVtxos.mutate()}
    />
  );
};
