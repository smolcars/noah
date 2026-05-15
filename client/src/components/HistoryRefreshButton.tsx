import React, { useEffect } from "react";
import { Pressable, View } from "react-native";
import Icon from "@react-native-vector-icons/ionicons";
import Animated, {
  cancelAnimation,
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
} from "react-native-reanimated";
import { COLORS } from "~/lib/styleConstants";
import { useIconColor, useThemeColors } from "~/hooks/useTheme";

type HistoryRefreshButtonProps = {
  isRefreshing: boolean;
  onRefresh: () => void | Promise<unknown>;
};

export const HistoryRefreshButton = ({
  isRefreshing,
  onRefresh,
}: HistoryRefreshButtonProps) => {
  const iconColor = useIconColor();
  const colors = useThemeColors();
  const rotation = useSharedValue(0);

  useEffect(() => {
    if (isRefreshing) {
      rotation.value = withRepeat(
        withTiming(360, {
          duration: 900,
          easing: Easing.linear,
        }),
        -1,
        false,
      );
      return;
    }

    cancelAnimation(rotation);
    rotation.value = withTiming(0, { duration: 180 });
  }, [isRefreshing, rotation]);

  const iconStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${rotation.value}deg` }],
  }));

  return (
    <Pressable
      onPress={() => {
        void onRefresh();
      }}
      disabled={isRefreshing}
      accessibilityRole="button"
      accessibilityLabel="Refresh history"
      className="h-10 w-10 items-center justify-center rounded-full border"
      style={{
        borderColor: isRefreshing ? `${COLORS.BITCOIN_ORANGE}66` : `${colors.mutedForeground}24`,
        backgroundColor: isRefreshing ? `${COLORS.BITCOIN_ORANGE}16` : `${colors.card}CC`,
        opacity: isRefreshing ? 0.9 : 1,
      }}
    >
      <View className="h-6 w-6 items-center justify-center">
        <Animated.View style={iconStyle}>
          <Icon
            name="refresh-outline"
            size={21}
            color={isRefreshing ? COLORS.BITCOIN_ORANGE : iconColor}
          />
        </Animated.View>
      </View>
    </Pressable>
  );
};
