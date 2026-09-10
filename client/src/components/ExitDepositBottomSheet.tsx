import Icon from "@react-native-vector-icons/ionicons";
import { useQuery } from "@tanstack/react-query";
import { AccessibilityInfo, Pressable, useWindowDimensions, View } from "react-native";
import QRCode from "react-native-qrcode-svg";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { NativeNoahIconButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useThemeColors } from "~/hooks/useTheme";
import { useCopyToClipboard } from "~/lib/clipboardUtils";
import { onchainAddress } from "~/lib/paymentsApi";

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

  return (
    <AppBottomSheet isOpen={isOpen} onClose={onClose} detents={[0, "content"]}>
      <View className="gap-5">
        <View className="flex-row items-center justify-between gap-3">
          <Text accessibilityRole="header" className="flex-1 text-2xl font-bold text-foreground">
            Deposit Onchain Funds
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Close deposit details"
            onPress={onClose}
            className="h-11 w-11 items-center justify-center rounded-full border border-border"
          >
            <Icon name="close" size={22} color={colors.foreground} />
          </Pressable>
        </View>
        <View className="items-center gap-1">
          <Text className="text-sm text-muted-foreground">Suggested deposit</Text>
          <Text className="text-2xl font-semibold text-foreground">
            {formatAmount(broadcastFeeSat)}
          </Text>
          <Text className="text-center text-sm text-muted-foreground">
            Based on estimated broadcast fees. The amount needed may vary.
          </Text>
        </View>
        {address ? (
          <>
            <View
              accessible
              accessibilityLabel="Bitcoin deposit address QR code"
              className="self-center rounded-2xl bg-white p-4"
            >
              <QRCode value={address} size={Math.min(width - 96, 220)} />
            </View>
            <View className="flex-row items-center gap-3">
              <Text selectable className="flex-1 text-sm text-foreground">
                {address}
              </Text>
              <NativeNoahIconButton
                icon="copy"
                accessibilityLabel={isCopied(address) ? "Address copied" : "Copy Bitcoin address"}
                onPress={() =>
                  void copyWithState(address, address, {
                    onCopy: () =>
                      AccessibilityInfo.announceForAccessibility("Bitcoin address copied"),
                  })
                }
                testID="exit-deposit-copy-button"
              />
            </View>
          </>
        ) : addressQuery.isError ? (
          <View className="gap-3">
            <Text className="text-center text-sm text-muted-foreground">
              Couldn’t generate a Bitcoin address.
            </Text>
            <NativeNoahSecondaryButton
              label="Retry"
              onPress={() => void addressQuery.refetch()}
              fullWidth
            />
          </View>
        ) : (
          <View className="items-center gap-3 py-8">
            <NoahActivityIndicator />
            <Text className="text-sm text-muted-foreground">Generating Bitcoin address…</Text>
          </View>
        )}
        <Text className="text-center text-sm text-muted-foreground">
          Wait for the deposit to confirm before progressing your exit.
        </Text>
      </View>
    </AppBottomSheet>
  );
}
