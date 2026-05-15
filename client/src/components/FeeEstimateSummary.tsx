import { View } from "react-native";
import type { BarkFeeEstimate } from "~/lib/paymentsApi";
import { Text } from "~/components/ui/text";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { formatBip177 } from "~/lib/utils";
import { useThemeColors } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

type FeeEstimateSummaryProps = {
  estimate?: BarkFeeEstimate;
  isLoading?: boolean;
  error?: Error | null;
  compact?: boolean;
  title?: string;
  netLabel?: string;
  feeLabel?: string;
  grossLabel?: string;
  unavailableText?: string;
};

const FeeEstimateRow = ({
  label,
  value,
  compact = false,
}: {
  label: string;
  value: string;
  compact?: boolean;
}) => (
  <View className={`flex-row items-center justify-between ${compact ? "py-1" : "py-2"}`}>
    <Text className="text-sm text-muted-foreground">{label}</Text>
    <Text className="text-sm font-semibold text-foreground">{value}</Text>
  </View>
);

export const FeeEstimateSummary = ({
  estimate,
  isLoading = false,
  error = null,
  compact = false,
  title = "Fee estimate",
  netLabel = "Recipient gets",
  feeLabel = "Estimated fee",
  grossLabel = "Total deducted",
  unavailableText = "Fee estimate unavailable. The final fee will be calculated when you send.",
}: FeeEstimateSummaryProps) => {
  const colors = useThemeColors();

  if (!estimate && !isLoading && !error) {
    return null;
  }

  return (
    <View
      className={`${compact ? "mt-3 rounded-[18px] px-3 py-3" : "mt-4 rounded-[20px] px-4 py-4"} border`}
      style={{
        borderColor: `${colors.mutedForeground}22`,
        backgroundColor: `${colors.card}CC`,
      }}
    >
      <View className={`${compact ? "mb-1" : "mb-2"} flex-row items-center justify-between`}>
        <Text className="text-xs font-semibold uppercase tracking-[2px] text-muted-foreground">
          {title}
        </Text>
        {isLoading ? <NoahActivityIndicator size="small" /> : null}
      </View>

      {estimate ? (
        <>
          <FeeEstimateRow
            label={netLabel}
            value={formatBip177(estimate.net_amount_sat)}
            compact={compact}
          />
          <View className="h-px bg-border/70" />
          <FeeEstimateRow
            label={feeLabel}
            value={formatBip177(estimate.fee_sat)}
            compact={compact}
          />
          <View className="h-px bg-border/70" />
          <FeeEstimateRow
            label={grossLabel}
            value={formatBip177(estimate.gross_amount_sat)}
            compact={compact}
          />
          {estimate.vtxos_spent.length > 0 ? (
            <Text className="mt-2 text-xs text-muted-foreground">
              Spending {estimate.vtxos_spent.length} VTXO
              {estimate.vtxos_spent.length === 1 ? "" : "s"}
            </Text>
          ) : null}
        </>
      ) : error ? (
        <Text className="text-sm leading-5" style={{ color: COLORS.BITCOIN_ORANGE }}>
          {unavailableText}
        </Text>
      ) : (
        <Text className="text-sm text-muted-foreground">Estimating fee...</Text>
      )}
    </View>
  );
};
