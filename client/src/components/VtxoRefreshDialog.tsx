import { useLocale, T, useGT } from "gt-react-native";
import { View } from "react-native";
import type { BarkFeeEstimate } from "react-native-nitro-ark";

import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useBtcToFiatRate } from "~/hooks/useMarketData";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import { cn } from "~/lib/utils";
import { useProfileStore } from "~/store/profileStore";

import { ConfirmationDialog } from "./ConfirmationDialog";
import { Text } from "./ui/text";

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
      className={cn(
        "ml-3 flex-shrink text-right text-sm font-semibold text-foreground",
        valueClassName,
      )}
      numberOfLines={1}
      adjustsFontSizeToFit
      minimumFontScale={0.8}
    >
      {value}
    </Text>
  </View>
);

type VtxoRefreshDialogProps = {
  amountSat: number;
  estimate: BarkFeeEstimate | null;
  isBusy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  onOpenChange: (open: boolean) => void;
  open: boolean;
  vtxoCount: number;
};

export function VtxoRefreshDialog({
  amountSat,
  estimate,
  isBusy,
  onCancel,
  onConfirm,
  onOpenChange,
  open,
  vtxoCount,
}: VtxoRefreshDialogProps) {
  const gt = useGT();
  const locale = useLocale();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const { data: btcToFiatRate } = useBtcToFiatRate();
  const amountAfterFeeSat = Math.max(amountSat - (estimate?.fee_sat ?? 0), 0);

  const formatBitcoinWithFiat = (valueSat: number) => {
    if (btcToFiatRate === undefined) {
      return formatBitcoinAmount(valueSat);
    }

    const fiatValue = formatFiatAmount(
      satsToFiat(valueSat, btcToFiatRate, fiatCurrency),
      fiatCurrency,
      locale,
    );
    return `${formatBitcoinAmount(valueSat)} (${fiatValue})`;
  };

  return (
    <ConfirmationDialog
      title={vtxoCount === 1 ? gt("Refresh VTXO?") : gt("Refresh VTXOs?")}
      description={
        vtxoCount === 1
          ? gt("This refreshes this VTXO in a delegated Ark round.")
          : gt("This refreshes the selected VTXOs in a delegated Ark round.")
      }
      confirmText={gt("Refresh")}
      cancelText={gt("Cancel")}
      confirmVariant="default"
      open={open}
      onOpenChange={onOpenChange}
      onConfirm={onConfirm}
      onCancel={onCancel}
      isConfirmDisabled={isBusy || !estimate}
      contentClassName="w-[92%] rounded-2xl border-border bg-background p-5"
      headerClassName="gap-2"
      titleClassName="text-2xl font-bold text-foreground"
      descriptionClassName="text-base leading-6 text-muted-foreground"
      footerClassName="mt-1 gap-3 space-x-0"
      cancelClassName="h-12 rounded-xl border-border bg-background"
      actionClassName="h-12 rounded-xl"
    >
      {estimate ? (
        <View className="gap-3">
          <View className="rounded-xl border border-border/70 bg-card/80 p-4">
            <T>
              <Text className="text-sm font-medium text-muted-foreground">Amount selected</Text>
            </T>
            <Text
              className="mt-1 text-3xl font-bold text-foreground"
              numberOfLines={1}
              adjustsFontSizeToFit
              minimumFontScale={0.72}
            >
              {formatBitcoinWithFiat(amountSat)}
            </Text>
          </View>

          <View className="rounded-xl border border-border/70 bg-card/60 px-3 py-1">
            <RefreshPlanRow label={gt("VTXOs selected")} value={vtxoCount.toLocaleString(locale)} />
            <View className="h-px bg-border/70" />
            <RefreshPlanRow
              label={gt("Refresh fee")}
              value={formatBitcoinWithFiat(estimate.fee_sat)}
              valueClassName="text-red-500"
            />
            <View className="h-px bg-border/70" />
            <RefreshPlanRow
              label={gt("Amount after fee")}
              value={formatBitcoinWithFiat(amountAfterFeeSat)}
              valueClassName="text-green-500"
            />
          </View>
        </View>
      ) : null}
    </ConfirmationDialog>
  );
}
