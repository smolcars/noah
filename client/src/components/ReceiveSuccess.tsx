import React, { useEffect } from "react";
import { View } from "react-native";
import Animated, { FadeInUp, ZoomIn } from "react-native-reanimated";
import { Text } from "./ui/text";
import { NoahButton } from "./ui/NoahButton";
import ReceiveAnimation from "./ReceiveAnimation";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import type { FiatCurrencyCode } from "~/lib/fiatCurrency";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import * as Haptics from "expo-haptics";
import { useThemeColors } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";

type ReceiveSuccessProps = {
  amountSat: number;
  btcPrice?: number;
  fiatCurrency: FiatCurrencyCode;
  totalWalletBalanceSat?: number;
  handleDone: () => void;
};

export const ReceiveSuccess: React.FC<ReceiveSuccessProps> = ({
  amountSat,
  btcPrice,
  fiatCurrency,
  totalWalletBalanceSat,
  handleDone,
}) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const fiatAmount = btcPrice ? satsToFiat(amountSat, btcPrice, fiatCurrency) : null;
  const colors = useThemeColors();
  const bottomTabBarHeight = useBottomTabBarHeight();

  useEffect(() => {
    const triggerHaptic = async () => {
      await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    };
    triggerHaptic();
  }, []);

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View
        className="flex-1 px-6 pt-3"
        style={{ paddingBottom: Math.max(bottomTabBarHeight, 20) + 12 }}
      >
        <View className="flex-1 items-center justify-start pt-10">
          <Animated.View entering={ZoomIn.duration(520).delay(120)} className="items-center">
            <ReceiveAnimation />
          </Animated.View>

          <Animated.View entering={FadeInUp.duration(520).delay(180)} className="mt-6 items-center">
            <Text className="text-center text-4xl font-bold text-foreground">
              {formatBitcoinAmount(amountSat)}
            </Text>
            {btcPrice && (
              <Text className="mt-3 text-base font-medium text-muted-foreground">
                ≈ {fiatAmount ? formatFiatAmount(fiatAmount, fiatCurrency) : null}
              </Text>
            )}
            <Text className="mt-6 text-center text-2xl font-bold text-foreground">
              Funds received
            </Text>
          </Animated.View>

          <Animated.View
            entering={FadeInUp.duration(520).delay(260)}
            className="mt-8 w-full max-w-[320px] border-t px-1 py-4"
            style={{
              borderColor: `${colors.mutedForeground}22`,
            }}
          >
            <View className="flex-row items-center justify-between">
              <Text className="text-sm font-medium uppercase tracking-[2px] text-muted-foreground">
                Status
              </Text>
              <Text className="text-sm font-semibold" style={{ color: COLORS.BITCOIN_ORANGE }}>
                Settled
              </Text>
            </View>
            <View className="mt-4 h-px bg-border" />
            <View className="mt-4 flex-row items-center justify-between">
              <Text className="text-base text-muted-foreground">Wallet balance</Text>
              <Text className="text-base font-semibold text-foreground">
                {totalWalletBalanceSat !== undefined
                  ? formatBitcoinAmount(totalWalletBalanceSat)
                  : "…"}
              </Text>
            </View>
          </Animated.View>
        </View>

        <Animated.View entering={FadeInUp.duration(520).delay(320)} className="mt-6">
          <NoahButton onPress={handleDone} className="rounded-2xl py-4">
            Done
          </NoahButton>
        </Animated.View>
      </View>
    </NoahSafeAreaView>
  );
};
