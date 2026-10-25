import { T, useGT, Var } from "gt-react-native";
import Icon from "@react-native-vector-icons/ionicons";
import { useState, type ComponentProps } from "react";
import { Pressable, View } from "react-native";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { Text } from "~/components/ui/text";
import { useThemeColors } from "~/hooks/useTheme";
import { useRefreshExpiringVtxos } from "~/hooks/useWallet";
import { calculateBalances, groupBalances, type BalanceResults } from "~/lib/balanceUtils";
import { COLORS } from "~/lib/styleConstants";

const IN_PROGRESS_COLOR = "#60a5fa";
const NEEDS_REFRESH_COLOR = "#f59e0b";

type IconName = ComponentProps<typeof Icon>["name"];

const GroupRow = ({ color, label, amount }: { color: string; label: string; amount: string }) => (
  <View className="flex-row items-center justify-between gap-3">
    <View className="flex-1 flex-row items-center gap-2">
      <View className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: color }} />
      <Text className="flex-1 text-base text-foreground">{label}</Text>
    </View>
    <Text className="text-base font-semibold text-foreground">{amount}</Text>
  </View>
);

const SubRow = ({ icon, label, amount }: { icon: IconName; label: string; amount: string }) => {
  const { mutedForeground } = useThemeColors();
  return (
    <View className="flex-row items-center justify-between gap-3 pl-5">
      <View className="flex-1 flex-row items-center gap-2">
        <Icon name={icon} size={14} color={mutedForeground} />
        <Text className="flex-1 text-sm text-muted-foreground">{label}</Text>
      </View>
      <Text className="text-sm text-muted-foreground">{amount}</Text>
    </View>
  );
};

export function BalanceSheet({
  isOpen,
  onClose,
  balance,
  formatAmount,
}: {
  isOpen: boolean;
  onClose: () => void;
  balance: BalanceResults;
  formatAmount: (amountSat: number) => string;
}) {
  const gt = useGT();
  const { mutedForeground } = useThemeColors();
  const [showAllFields, setShowAllFields] = useState(false);
  const refreshVtxos = useRefreshExpiringVtxos();
  const groups = groupBalances(balance);
  const { totalBalance } = calculateBalances(balance);
  const inProgress = groups.arriving + groups.refreshing + groups.sending + groups.exiting;
  const segments = [
    { key: "spendable", amount: groups.spendable, color: COLORS.SUCCESS },
    { key: "in-progress", amount: inProgress, color: IN_PROGRESS_COLOR },
    { key: "onchain", amount: groups.onchain, color: COLORS.BITCOIN_ORANGE },
    { key: "needs-refresh", amount: groups.needsRefresh, color: NEEDS_REFRESH_COLOR },
  ].filter((segment) => segment.amount > 0);
  const subRows: { icon: IconName; label: string; amount: number }[] = [
    { icon: "arrow-down", label: gt("Arriving"), amount: groups.arriving },
    { icon: "refresh", label: gt("Refreshing"), amount: groups.refreshing },
    { icon: "arrow-up", label: gt("Sending"), amount: groups.sending },
    { icon: "exit-outline", label: gt("Exiting"), amount: groups.exiting },
  ];
  // Raw Bark fields for support; names stay untranslated so they match Bark's docs.
  const rawFields = [
    ...Object.entries(balance.offchain).map(([key, value]) => [`offchain.${key}`, value] as const),
    ...Object.entries(balance.onchain).map(([key, value]) => [`onchain.${key}`, value] as const),
  ];

  return (
    <AppBottomSheet isOpen={isOpen} onClose={onClose} detents={[0, "content"]} scrollable>
      <View className="gap-4 px-2 pb-2">
        <View className="flex-row items-center justify-between gap-3">
          <T>
            <Text accessibilityRole="header" className="text-xl font-semibold text-foreground">
              Balance
            </Text>
          </T>
          <View className="flex-1 flex-row items-center justify-end gap-3">
            <Text
              className="shrink text-xl font-semibold text-foreground"
              adjustsFontSizeToFit
              numberOfLines={1}
            >
              {formatAmount(totalBalance)}
            </Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={gt("Close balance details")}
              onPress={onClose}
              className="h-11 w-11 shrink-0 items-center justify-center rounded-full bg-muted/60"
            >
              <Icon name="close" size={20} color={mutedForeground} />
            </Pressable>
          </View>
        </View>

        {segments.length > 0 ? (
          <View
            className="h-2 flex-row gap-0.5 overflow-hidden rounded-full"
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
          >
            {segments.map((segment) => (
              <View
                key={segment.key}
                style={{ flex: segment.amount, backgroundColor: segment.color }}
              />
            ))}
          </View>
        ) : null}

        <View className="gap-3">
          <GroupRow
            color={COLORS.SUCCESS}
            label={gt("Ready to spend")}
            amount={formatAmount(groups.spendable)}
          />
          {inProgress > 0 ? (
            <View className="gap-2">
              <GroupRow
                color={IN_PROGRESS_COLOR}
                label={gt("In progress")}
                amount={formatAmount(inProgress)}
              />
              {subRows
                .filter((row) => row.amount > 0)
                .map((row) => (
                  <SubRow
                    key={row.label}
                    icon={row.icon}
                    label={row.label}
                    amount={formatAmount(row.amount)}
                  />
                ))}
            </View>
          ) : null}
          {groups.onchain > 0 ? (
            <GroupRow
              color={COLORS.BITCOIN_ORANGE}
              label={gt("On-chain")}
              amount={formatAmount(groups.onchain)}
            />
          ) : null}
        </View>

        {groups.needsRefresh > 0 ? (
          <View className="gap-3 rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4">
            <T>
              <Text className="text-base font-semibold text-foreground">
                <Var>{formatAmount(groups.needsRefresh)}</Var> needs refresh
              </Text>
            </T>
            <T>
              <Text className="text-sm text-muted-foreground">
                These funds can't be sent until they are refreshed.
              </Text>
            </T>
            <NativeNoahSecondaryButton
              label={refreshVtxos.isPending ? gt("Refreshing...") : gt("Refresh")}
              onPress={() => refreshVtxos.mutate()}
              disabled={refreshVtxos.isPending}
              fullWidth
            />
          </View>
        ) : null}

        <Pressable
          accessibilityRole="button"
          accessibilityState={{ expanded: showAllFields }}
          onPress={() => setShowAllFields((current) => !current)}
          className="flex-row items-center gap-1 border-t border-border pt-3"
        >
          <Icon
            name={showAllFields ? "chevron-down" : "chevron-forward"}
            size={14}
            color={mutedForeground}
          />
          <T>
            <Text className="text-sm text-muted-foreground">Show all Bark fields</Text>
          </T>
        </Pressable>
        {showAllFields ? (
          <View className="gap-1">
            {rawFields.map(([key, value]) => (
              <View key={key} className="flex-row justify-between gap-3">
                <Text className="flex-1 font-mono text-xs text-muted-foreground">{key}</Text>
                <Text className="font-mono text-xs text-muted-foreground">
                  {formatAmount(value)}
                </Text>
              </View>
            ))}
          </View>
        ) : null}
      </View>
    </AppBottomSheet>
  );
}
