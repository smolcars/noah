import { Fragment } from "react";
import type { BarkFeeEstimate } from "~/lib/paymentsApi";
import { Text } from "~/components/ui/text";
import { formatBip177 } from "~/lib/utils";
import { COLORS } from "~/lib/styleConstants";
import { FeeEstimateBox, FeeEstimateRow, FeeEstimateSeparator } from "~/components/FeeEstimateBox";

type FeeEstimateRowKey = "net" | "fee" | "gross";

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
  note?: string | null;
  feeValueClassName?: string;
  rowOrder?: FeeEstimateRowKey[];
};

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
  note = null,
  feeValueClassName,
  rowOrder = ["net", "fee", "gross"],
}: FeeEstimateSummaryProps) => {
  if (!estimate && !isLoading && !error) {
    return null;
  }

  return (
    <FeeEstimateBox title={title} isLoading={isLoading} compact={compact}>
      {estimate ? (
        <>
          {rowOrder.map((rowKey, index) => {
            const row =
              rowKey === "net"
                ? {
                    label: netLabel,
                    value: formatBip177(estimate.net_amount_sat),
                    valueClassName: undefined,
                  }
                : rowKey === "fee"
                  ? {
                      label: feeLabel,
                      value: formatBip177(estimate.fee_sat),
                      valueClassName: feeValueClassName,
                    }
                  : {
                      label: grossLabel,
                      value: formatBip177(estimate.gross_amount_sat),
                      valueClassName: undefined,
                    };

            return (
              <Fragment key={rowKey}>
                <FeeEstimateRow
                  label={row.label}
                  value={row.value}
                  compact={compact}
                  valueClassName={row.valueClassName}
                />
                {index < rowOrder.length - 1 ? <FeeEstimateSeparator /> : null}
              </Fragment>
            );
          })}
          {estimate.vtxos_spent.length > 0 ? (
            <Text className="mt-2 text-xs text-muted-foreground">
              Spending {estimate.vtxos_spent.length} VTXO
              {estimate.vtxos_spent.length === 1 ? "" : "s"}
            </Text>
          ) : null}
          {note ? <Text className="mt-2 text-xs text-muted-foreground">{note}</Text> : null}
        </>
      ) : error ? (
        <Text className="text-sm leading-5" style={{ color: COLORS.BITCOIN_ORANGE }}>
          {unavailableText}
        </Text>
      ) : (
        <Text className="text-sm text-muted-foreground">Estimating fee...</Text>
      )}
    </FeeEstimateBox>
  );
};
