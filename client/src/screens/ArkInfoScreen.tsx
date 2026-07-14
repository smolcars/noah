import React from "react";
import { Pressable, ScrollView, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { ChevronRight } from "lucide-react-native";
import type { BarkArkInfo } from "react-native-nitro-ark";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { Text } from "~/components/ui/text";
import { useArkInfo } from "~/hooks/useWallet";
import { copyToClipboard } from "~/lib/clipboardUtils";
import { COLORS } from "~/lib/styleConstants";
import { useThemeColors } from "~/hooks/useTheme";
import type { SettingsStackParamList } from "~/Navigators";
import { mempoolHistoricalPriceEndpoint, mempoolPriceEndpoint } from "~/constants";
import { APP_VARIANT } from "~/config";
import { getBlockheightEndpoint } from "~/lib/esplora";
import { useEsploraStore } from "~/store/esploraStore";
import { getDefaultEsploraEndpoint, getWalletEndpoints } from "~/lib/walletConfig";

type NavigationProp = NativeStackNavigationProp<SettingsStackParamList, "ArkInfo">;

type ArkInfoValue = string | number | boolean;

type InfoRow = {
  label: string;
  value: ArkInfoValue;
  accessibilityHint?: string;
  accessibilityLabel?: string;
  actionLabel?: string;
  copyable?: boolean;
  onPress?: () => void;
  testID?: string;
  unit?: string;
};

type OptionalInfoRow = Omit<InfoRow, "value"> & {
  value?: ArkInfoValue | null;
};

const formatNumber = (value: number) => value.toLocaleString();

const formatValue = (value: ArkInfoValue, unit?: string) => {
  if (typeof value === "boolean") {
    return value ? "Required" : "Not required";
  }

  if (typeof value === "number") {
    return unit ? `${formatNumber(value)} ${unit}` : formatNumber(value);
  }

  return value;
};

const truncateValue = (value: string) => {
  if (value.length <= 44) {
    return value;
  }

  return `${value.slice(0, 18)}...${value.slice(-14)}`;
};

const InfoSection = ({ title, rows }: { title: string; rows: InfoRow[] }) => {
  const colors = useThemeColors();
  const [copiedKey, setCopiedKey] = React.useState<string | null>(null);

  const handlePress = async (row: InfoRow) => {
    if (row.onPress) {
      row.onPress();
      return;
    }

    if (!row.copyable) {
      return;
    }

    const value = String(row.value);
    await copyToClipboard(value, {
      onCopy: () => {
        setCopiedKey(row.label);
        setTimeout(() => setCopiedKey(null), 1800);
      },
    });
  };

  return (
    <View className="mt-7">
      <Text className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
        {title}
      </Text>
      <View
        className="mt-3 overflow-hidden rounded-[18px] border"
        style={{
          borderColor: `${colors.mutedForeground}24`,
          backgroundColor: `${colors.card}CC`,
        }}
      >
        {rows.map((row, index) => {
          const value = formatValue(row.value, row.unit);
          const copied = copiedKey === row.label;
          const isPressable = Boolean(row.onPress || row.copyable);

          return (
            <Pressable
              key={row.label}
              onPress={() => handlePress(row)}
              disabled={!isPressable}
              accessibilityRole={isPressable ? "button" : undefined}
              accessibilityLabel={
                row.accessibilityLabel ?? (row.copyable ? `Copy ${row.label}` : undefined)
              }
              accessibilityHint={row.accessibilityHint}
              android_ripple={{ color: `${COLORS.BITCOIN_ORANGE}14` }}
              style={({ pressed }) => (pressed && isPressable ? { opacity: 0.72 } : undefined)}
              testID={row.testID}
              className={`flex-row items-center gap-3 px-4 py-4 ${
                index < rows.length - 1 ? "border-b border-border/60" : ""
              }`}
            >
              <View className="min-w-0 flex-1">
                <Text className="text-sm text-muted-foreground">{row.label}</Text>
                <Text
                  className="mt-1 text-base font-semibold text-foreground"
                  numberOfLines={isPressable ? 1 : 2}
                  ellipsizeMode="middle"
                >
                  {isPressable && typeof row.value === "string" ? truncateValue(value) : value}
                </Text>
              </View>
              {row.actionLabel ? (
                <View className="flex-row items-center gap-1">
                  <Text
                    className="text-xs font-semibold uppercase tracking-[2px]"
                    style={{ color: COLORS.BITCOIN_ORANGE }}
                  >
                    {row.actionLabel}
                  </Text>
                  <ChevronRight size={16} color={COLORS.BITCOIN_ORANGE} strokeWidth={2.5} />
                </View>
              ) : row.copyable ? (
                <Text
                  className="text-xs font-semibold uppercase tracking-[2px]"
                  style={{ color: copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
                >
                  {copied ? "Copied" : "Copy"}
                </Text>
              ) : null}
            </Pressable>
          );
        })}
      </View>
    </View>
  );
};

const compactRows = (rows: OptionalInfoRow[]): InfoRow[] =>
  rows.filter((row): row is InfoRow => row.value !== undefined && row.value !== null);

const getMempoolExplorerBaseUrl = () => {
  switch (APP_VARIANT) {
    case "mainnet":
      return "https://mempool.space";
    case "signet":
    case "regtest":
      return "https://mempool.space/signet";
    default:
      return null;
  }
};

const buildSections = (arkInfo: BarkArkInfo) => [
  {
    title: "Keys",
    rows: [
      { label: "Server pubkey", value: arkInfo.server_pubkey, copyable: true },
      { label: "Mailbox pubkey", value: arkInfo.mailbox_pubkey, copyable: true },
    ],
  },
  {
    title: "Boarding limits",
    rows: [
      { label: "Minimum board amount", value: arkInfo.min_board_amount, unit: "sats" },
      { label: "Maximum VTXO amount", value: arkInfo.max_vtxo_amount, unit: "sats" },
      {
        label: "Required board confirmations",
        value: arkInfo.required_board_confirmations,
        unit: "blocks",
      },
    ],
  },
  {
    title: "Timing",
    rows: [
      { label: "Round interval", value: arkInfo.round_interval, unit: "seconds" },
      { label: "Round nonces", value: arkInfo.nb_round_nonces },
      { label: "VTXO exit delta", value: arkInfo.vtxo_exit_delta, unit: "blocks" },
      { label: "VTXO expiry delta", value: arkInfo.vtxo_expiry_delta, unit: "blocks" },
      { label: "HTLC send expiry delta", value: arkInfo.htlc_send_expiry_delta, unit: "blocks" },
    ],
  },
  {
    title: "Lightning",
    rows: [
      {
        label: "Receive anti-DoS",
        value: arkInfo.ln_receive_anti_dos_required,
      },
    ],
  },
];

const buildConfigurationSections = (
  esploraEndpoint: string | null,
  hasEsploraOverride: boolean,
  onEditEsplora: () => void,
) => {
  const walletEndpoints = getWalletEndpoints(esploraEndpoint);

  return [
    {
      title: "Wallet endpoints",
      rows: compactRows([
        { label: "Ark server", value: walletEndpoints.ark, copyable: true },
        {
          label: hasEsploraOverride ? "Esplora API · Custom" : "Esplora API · Noah default",
          value: walletEndpoints.esplora,
          actionLabel: "Edit",
          onPress: onEditEsplora,
          accessibilityLabel: "Edit Esplora API endpoint",
          accessibilityHint: "Opens the endpoint editor.",
          testID: "edit-esplora-endpoint",
        },
        { label: "Bitcoind RPC", value: walletEndpoints.bitcoind, copyable: true },
      ]),
    },
    {
      title: "Explorer APIs",
      rows: compactRows([
        { label: "Block height API", value: getBlockheightEndpoint(), copyable: true },
        { label: "Mempool explorer", value: getMempoolExplorerBaseUrl(), copyable: true },
        { label: "Price API", value: mempoolPriceEndpoint, copyable: true },
        { label: "Historical price API", value: mempoolHistoricalPriceEndpoint, copyable: true },
      ]),
    },
  ];
};

const ArkInfoScreen = () => {
  const navigation = useNavigation<NavigationProp>();
  const colors = useThemeColors();
  const endpointOverride = useEsploraStore((state) => state.endpointOverride);
  const esploraEndpoint = endpointOverride ?? getDefaultEsploraEndpoint();
  const { data: arkInfo, isLoading, isError, error, refetch, isFetching } = useArkInfo();
  const openEsploraEditor = () => navigation.navigate("Esplora");
  const configurationSections = buildConfigurationSections(
    esploraEndpoint,
    endpointOverride !== null,
    openEsploraEditor,
  );
  const sections = arkInfo
    ? [...buildSections(arkInfo), ...configurationSections]
    : configurationSections;

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <ScrollView
        className="flex-1"
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingBottom: 32 }}
      >
        <View className="px-5 pb-8 pt-4">
          <View className="flex-row items-center">
            <NativeNoahBackButton
              onPress={() => navigation.goBack()}
              className="mr-3"
              testID="ark-info-back-button"
            />
            <Text className="text-2xl font-bold text-foreground">Ark Info</Text>
          </View>

          <View className="mt-8 border-b border-border/60 pb-6">
            <Text className="text-[11px] font-semibold uppercase tracking-[3px] text-muted-foreground">
              Ark Server
            </Text>
            <Text className="mt-3 text-4xl font-bold capitalize text-foreground">
              {arkInfo?.network ?? "Network"}
            </Text>
            <Text className="mt-3 max-w-[320px] text-base leading-6 text-muted-foreground">
              Current server parameters reported by the loaded Ark wallet.
            </Text>
          </View>

          {isLoading ? (
            <View className="mt-8 rounded-[18px] border border-border/60 bg-card/70 px-4 py-5">
              <Text className="text-muted-foreground">Loading Ark server info...</Text>
            </View>
          ) : null}

          {isError ? (
            <View
              className="mt-8 rounded-[18px] border px-4 py-5"
              style={{
                borderColor: `${colors.mutedForeground}24`,
                backgroundColor: `${colors.card}CC`,
              }}
            >
              <Text className="text-lg font-semibold text-foreground">Ark info unavailable</Text>
              <Text className="mt-2 text-sm leading-6 text-muted-foreground">
                {error instanceof Error ? error.message : "Failed to load Ark server info."}
              </Text>
              <NativeNoahButton
                label="Retry"
                onPress={() => refetch()}
                isLoading={isFetching}
                loadingLabel="Retrying..."
                className="mt-5 rounded-2xl py-4"
                fullWidth
              />
            </View>
          ) : null}

          {sections.map((section, index, allSections) => (
            <View
              key={section.title}
              className={index === allSections.length - 1 ? "mb-10" : undefined}
            >
              <InfoSection title={section.title} rows={section.rows} />
            </View>
          ))}
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default ArkInfoScreen;
