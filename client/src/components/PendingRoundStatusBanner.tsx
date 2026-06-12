import { useEffect, useState } from "react";
import { Linking, Pressable, View } from "react-native";
import { Clock3 } from "lucide-react-native";
import Icon from "@react-native-vector-icons/ionicons";
import type { PendingRoundStatus } from "react-native-nitro-ark";
import { StatusBannerStrip } from "~/components/StatusBannerStrip";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { Text } from "~/components/ui/text";
import { usePendingRounds } from "~/hooks/useWallet";
import { COLORS } from "~/lib/styleConstants";
import { getMempoolTxUrl } from "~/constants";
import { truncateMiddle } from "~/lib/exitTimeline";
import { copyToClipboard } from "~/lib/clipboardUtils";

const PENDING_ROUNDS_REFETCH_MS = 30_000;

const isRoundActive = (round: PendingRoundStatus) =>
  !round.is_final || round.status === "pending" || round.status === "unconfirmed";

const formatRoundStatus = (status: PendingRoundStatus["status"]) =>
  status.charAt(0).toUpperCase() + status.slice(1);

const RoundDetailRow = ({
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
  const canUseActions = copyable || Boolean(explorerUrl);

  const handleCopy = async () => {
    await copyToClipboard(value, {
      onCopy: () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1000);
      },
    });
  };

  return (
    <View className="flex-row items-center justify-between border-b border-border/10 py-3 last:border-b-0">
      <Text className="mr-3 text-sm text-muted-foreground">{label}</Text>
      {canUseActions ? (
        <View className="min-w-0 flex-1 flex-row items-center justify-end gap-x-3">
          <Text className="min-w-0 flex-1 text-right text-sm text-foreground" numberOfLines={1}>
            {truncateMiddle(value, 10, 10)}
          </Text>
          {copyable ? (
            <Pressable
              onPress={handleCopy}
              hitSlop={10}
              className="h-8 w-8 items-center justify-center rounded-full bg-background"
              accessibilityRole="button"
              accessibilityLabel={`Copy ${label}`}
            >
              <Icon
                name={copied ? "checkmark-circle-outline" : "copy-outline"}
                size={17}
                color={copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE}
              />
            </Pressable>
          ) : null}
          {explorerUrl ? (
            <Pressable
              onPress={() => Linking.openURL(explorerUrl)}
              hitSlop={10}
              className="h-8 w-8 items-center justify-center rounded-full bg-background"
              accessibilityRole="button"
              accessibilityLabel={`Open ${label} in browser`}
            >
              <Icon name="open-outline" size={17} color={COLORS.BITCOIN_ORANGE} />
            </Pressable>
          ) : null}
        </View>
      ) : (
        <Text
          className="min-w-0 flex-1 text-right text-sm text-foreground"
          numberOfLines={2}
          ellipsizeMode="middle"
        >
          {value}
        </Text>
      )}
    </View>
  );
};

const PendingRoundDetail = ({ round }: { round: PendingRoundStatus }) => {
  const fundingTxUrl = round.funding_txid ? getMempoolTxUrl(round.funding_txid) : null;

  return (
    <View className="mb-4 rounded-lg bg-card p-4">
      <Text className="mb-3 text-base font-semibold text-foreground">Round #{round.round_id}</Text>
      <RoundDetailRow label="Status" value={formatRoundStatus(round.status)} />
      <RoundDetailRow label="Final" value={round.is_final ? "Yes" : "No"} />
      <RoundDetailRow label="Successful" value={round.is_success ? "Yes" : "No"} />
      {round.funding_txid ? (
        <RoundDetailRow
          label="Funding tx"
          value={round.funding_txid}
          copyable
          explorerUrl={fundingTxUrl}
        />
      ) : null}
      {round.unsigned_funding_txids.map((txid, index) => (
        <RoundDetailRow
          key={`${round.round_id}-${txid}`}
          label={`Unsigned tx ${index + 1}`}
          value={txid}
          explorerUrl={getMempoolTxUrl(txid)}
        />
      ))}
      {round.error ? <RoundDetailRow label="Error" value={round.error} /> : null}
    </View>
  );
};

export const PendingRoundStatusBanner = () => {
  const [isSheetOpen, setIsSheetOpen] = useState(false);
  const [shouldPoll, setShouldPoll] = useState(false);
  const { data: rounds = [] } = usePendingRounds(shouldPoll ? PENDING_ROUNDS_REFETCH_MS : false);

  const activeRounds = rounds.filter(isRoundActive);

  useEffect(() => {
    setShouldPoll(activeRounds.length > 0);
  }, [activeRounds.length]);

  if (activeRounds.length === 0) {
    return null;
  }

  const primaryRound = activeRounds[0];
  const title = activeRounds.length === 1 ? "Round in progress" : "Rounds in progress";
  const message =
    activeRounds.length === 1
      ? `Round #${primaryRound.round_id} ${primaryRound.status}`
      : `${activeRounds.length} rounds pending`;

  return (
    <>
      <StatusBannerStrip
        className="mx-4 mt-3 mb-1"
        title={title}
        message={message}
        icon={<Clock3 size={16} color="#60a5fa" />}
        tone="info"
        onPress={() => setIsSheetOpen(true)}
        actionLabel="View"
        onActionPress={() => setIsSheetOpen(true)}
      />
      <AppBottomSheet isOpen={isSheetOpen} onClose={() => setIsSheetOpen(false)} scrollable>
        <View className="px-1 pb-2">
          <View className="mb-5 flex-row items-center">
            <Pressable onPress={() => setIsSheetOpen(false)} className="mr-4">
              <Icon name="close-outline" size={24} color={COLORS.BITCOIN_ORANGE} />
            </Pressable>
            <Text className="text-2xl font-bold text-foreground">Pending Rounds</Text>
          </View>
          {activeRounds.map((round) => (
            <PendingRoundDetail key={round.round_id} round={round} />
          ))}
        </View>
      </AppBottomSheet>
    </>
  );
};
