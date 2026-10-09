import { T, useGT, Var } from "gt-react-native";
import { Fragment } from "react";
import type { BarkFeeEstimate } from "~/lib/paymentsApi";
import { Text } from "~/components/ui/text";
import { COLORS } from "~/lib/styleConstants";
import { FeeEstimateBox, FeeEstimateRow, FeeEstimateSeparator } from "~/components/FeeEstimateBox";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

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
  title: titleProp,
  netLabel: netLabelProp,
  feeLabel: feeLabelProp,
  grossLabel: grossLabelProp,
  unavailableText: unavailableTextProp,
  note = null,
  feeValueClassName,
  rowOrder = ["net", "fee", "gross"],
}: FeeEstimateSummaryProps) => {
  const gt = useGT();
  const unavailableText =
    unavailableTextProp ??
    gt("Fee estimate unavailable. The final fee will be calculated when you send.");
  const grossLabel = grossLabelProp ?? gt("Total deducted");
  const feeLabel = feeLabelProp ?? gt("Estimated fee");
  const netLabel = netLabelProp ?? gt("Recipient gets");
  const title = titleProp ?? gt("Fee estimate");
  const formatBitcoinAmount = useBitcoinAmountFormatter();

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
                    value: formatBitcoinAmount(estimate.net_amount_sat),
                    valueClassName: undefined,
                  }
                : rowKey === "fee"
                  ? {
                      label: feeLabel,
                      value: formatBitcoinAmount(estimate.fee_sat),
                      valueClassName: feeValueClassName,
                    }
                  : {
                      label: grossLabel,
                      value: formatBitcoinAmount(estimate.gross_amount_sat),
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
            <T>
              <Text className="mt-2 text-xs text-muted-foreground">
                Spending <Var>{estimate.vtxos_spent.length}</Var> VTXO
                <Var>{estimate.vtxos_spent.length === 1 ? "" : gt("s")}</Var>
              </Text>
            </T>
          ) : null}
          {note ? <Text className="mt-2 text-xs text-muted-foreground">{note}</Text> : null}
        </>
      ) : error ? (
        <Text className="text-sm leading-5" style={{ color: COLORS.BITCOIN_ORANGE }}>
          {unavailableText}
        </Text>
      ) : (
        <T>
          <Text className="text-sm text-muted-foreground">Estimating fee...</Text>
        </T>
      )}
    </FeeEstimateBox>
  );
};
