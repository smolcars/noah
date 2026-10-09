import { T, useGT } from "gt-react-native";
import Icon from "@react-native-vector-icons/ionicons";
import { useQuery } from "@tanstack/react-query";
import { AccessibilityInfo, Pressable, useWindowDimensions, View } from "react-native";
import QRCode from "react-native-qrcode-svg";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useThemeColors } from "~/hooks/useTheme";
import { useCopyToClipboard } from "~/lib/clipboardUtils";
import { onchainAddress } from "~/lib/paymentsApi";
import { COLORS } from "~/lib/styleConstants";

export function ExitDepositBottomSheet({
  isOpen,
  onClose,
  walletId,
  broadcastFeeSat,
}: {
  isOpen: boolean;
  onClose: () => void;
  walletId: string | null;
  broadcastFeeSat: number;
}) {
  const gt = useGT();
  const colors = useThemeColors();
  const formatAmount = useBitcoinAmountFormatter();
  const { width } = useWindowDimensions();
  const { copyWithState, isCopied } = useCopyToClipboard();
  const addressQuery = useQuery({
    queryKey: ["exit-deposit-address", walletId],
    queryFn: async () => {
      const result = await onchainAddress();
      if (result.isErr()) throw result.error;
      return result.value;
    },
    enabled: isOpen,
    staleTime: Infinity,
    retry: false,
  });
  const address = addressQuery.data;
  const copied = !!address && isCopied(address);
  const displayAddress =
    address && address.length > 42 ? `${address.slice(0, 18)}…${address.slice(-12)}` : address;

  return (
    <AppBottomSheet isOpen={isOpen} onClose={onClose} scrollable>
      <View className="gap-6 px-2 pb-2">
        <View className="flex-row items-center justify-between gap-3">
          <T>
            <Text
              accessibilityRole="header"
              className="flex-1 text-xl font-semibold text-foreground"
            >
              Deposit Onchain Funds
            </Text>
          </T>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={gt("Close deposit details")}
            onPress={onClose}
            className="h-11 w-11 items-center justify-center rounded-full bg-muted/60"
          >
            <Icon name="close" size={20} color={colors.mutedForeground} />
          </Pressable>
        </View>
        <View className="items-center gap-2">
          <T>
            <Text className="text-sm text-muted-foreground">Suggested deposit</Text>
          </T>
          <Text className="text-4xl font-semibold tracking-tight text-foreground">
            {formatAmount(broadcastFeeSat)}
          </Text>
          <T>
            <Text className="text-center text-xs text-muted-foreground">
              Estimated broadcast fees · amount may vary
            </Text>
          </T>
        </View>
        {address ? (
          <>
            <View
              accessible
              accessibilityLabel={gt("Bitcoin deposit address QR code")}
              className="self-center rounded-[24px] bg-white p-5"
            >
              <QRCode value={address} size={Math.min(width - 96, 220)} />
            </View>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={copied ? gt("Address copied") : gt("Copy Bitcoin address")}
              accessibilityHint={gt("Copies the full Bitcoin address")}
              className="flex-row items-center gap-4 rounded-2xl bg-card px-4 py-3"
              style={({ pressed }) => ({ opacity: pressed ? 0.7 : 1 })}
              onPress={() =>
                void copyWithState(address, address, {
                  onCopy: () =>
                    AccessibilityInfo.announceForAccessibility(gt("Bitcoin address copied")),
                })
              }
              testID="exit-deposit-copy-button"
            >
              <View className="min-w-0 flex-1 gap-1">
                <T>
                  <Text className="text-xs text-muted-foreground">Bitcoin address</Text>
                </T>
                <Text
                  className="text-sm font-medium text-foreground"
                  numberOfLines={1}
                  ellipsizeMode="middle"
                >
                  {displayAddress}
                </Text>
              </View>
              <View className="flex-row items-center gap-2">
                <Icon
                  name={copied ? "checkmark-circle" : "copy-outline"}
                  size={20}
                  color={copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE}
                />
                <Text
                  accessibilityLiveRegion="polite"
                  className="text-sm font-semibold"
                  style={{ color: copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
                >
                  {copied ? gt("Copied") : gt("Copy")}
                </Text>
              </View>
            </Pressable>
          </>
        ) : addressQuery.isError ? (
          <View className="gap-3">
            <T>
              <Text className="text-center text-sm text-muted-foreground">
                Couldn’t generate a Bitcoin address.
              </Text>
            </T>
            <NativeNoahSecondaryButton
              label={gt("Retry")}
              onPress={() => void addressQuery.refetch()}
              fullWidth
            />
          </View>
        ) : (
          <View className="items-center gap-3 py-8">
            <NoahActivityIndicator />
            <T>
              <Text className="text-sm text-muted-foreground">Generating Bitcoin address…</Text>
            </T>
          </View>
        )}
        <T>
          <Text className="px-4 text-center text-xs leading-5 text-muted-foreground">
            Wait for confirmation before progressing your exit.
          </Text>
        </T>
      </View>
    </AppBottomSheet>
  );
}
