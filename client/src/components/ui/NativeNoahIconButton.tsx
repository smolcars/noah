import { Button as SwiftButton, Host as SwiftHost } from "@expo/ui/swift-ui";
import {
  accessibilityIdentifier,
  accessibilityLabel as swiftAccessibilityLabel,
  buttonBorderShape,
  buttonStyle,
  controlSize,
  disabled as swiftDisabled,
  labelStyle,
  symbolEffect,
} from "@expo/ui/swift-ui/modifiers";
import Icon from "@react-native-vector-icons/ionicons";
import { type ComponentProps } from "react";
import { Platform, Pressable, View, type StyleProp, type ViewStyle } from "react-native";

import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { useTheme } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

export type NativeNoahIcon = "back" | "board" | "history" | "refresh" | "share";

const ICONS = {
  back: {
    ios: "chevron.backward",
    android: "arrow-back-outline",
  },
  board: {
    ios: "ferry",
    android: "boat-outline",
  },
  history: {
    ios: "clock.arrow.circlepath",
    android: "time-outline",
  },
  refresh: {
    ios: "arrow.clockwise",
    android: "refresh-outline",
  },
  share: {
    ios: "square.and.arrow.up",
    android: "share-outline",
  },
} as const satisfies Record<
  NativeNoahIcon,
  {
    ios: ComponentProps<typeof SwiftButton>["systemImage"];
    android: ComponentProps<typeof Icon>["name"];
  }
>;

type NativeNoahIconButtonProps = {
  icon: NativeNoahIcon;
  accessibilityLabel: string;
  onPress?: () => void;
  disabled?: boolean;
  isLoading?: boolean;
  size?: number;
  iconSize?: number;
  className?: string;
  style?: StyleProp<ViewStyle>;
  testID?: string;
};

type NativeNoahBackButtonProps = Pick<
  NativeNoahIconButtonProps,
  "onPress" | "disabled" | "className" | "style" | "testID"
>;

export function NativeNoahBackButton(props: NativeNoahBackButtonProps) {
  return <NativeNoahIconButton icon="back" accessibilityLabel="Go back" {...props} />;
}

export function NativeNoahIconButton({
  icon,
  accessibilityLabel,
  onPress,
  disabled = false,
  isLoading = false,
  size = 44,
  iconSize = 22,
  className,
  style,
  testID,
}: NativeNoahIconButtonProps) {
  const { colors } = useTheme();
  const isDisabled = disabled || isLoading;
  const iconNames = ICONS[icon];
  const supportsLiquidGlass = Platform.OS === "ios" && Number(Platform.Version) >= 26;

  if (supportsLiquidGlass) {
    return (
      <View
        className={className}
        style={[
          {
            width: size,
            height: size,
            opacity: isDisabled ? 0.55 : 1,
          },
          style,
        ]}
      >
        <SwiftHost seedColor={COLORS.BITCOIN_ORANGE} style={{ flex: 1 }}>
          <SwiftButton
            label={accessibilityLabel}
            systemImage={iconNames.ios}
            onPress={isDisabled ? undefined : onPress}
            modifiers={[
              buttonStyle("glass"),
              buttonBorderShape("circle"),
              controlSize("large"),
              labelStyle("iconOnly"),
              swiftAccessibilityLabel(accessibilityLabel),
              ...(testID ? [accessibilityIdentifier(testID)] : []),
              ...(isDisabled ? [swiftDisabled(true)] : []),
              ...(isLoading && icon === "refresh"
                ? [
                    symbolEffect(
                      { effect: "rotate", direction: "clockwise" },
                      { options: { repeat: "continuous", speed: 1.2 } },
                    ),
                  ]
                : []),
            ]}
          />
        </SwiftHost>
      </View>
    );
  }

  return (
    <Pressable
      className={className}
      onPress={onPress}
      disabled={isDisabled}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled: isDisabled, busy: isLoading }}
      android_ripple={{ color: `${COLORS.BITCOIN_ORANGE}33`, borderless: true }}
      testID={testID}
      style={({ pressed }) => [
        {
          width: size,
          height: size,
          alignItems: "center",
          justifyContent: "center",
          borderRadius: size / 2,
          borderWidth: 1,
          borderColor: colors.border,
          backgroundColor: `${COLORS.BITCOIN_ORANGE}14`,
          opacity: isDisabled ? 0.55 : pressed ? 0.72 : 1,
          overflow: "hidden",
        },
        style,
      ]}
    >
      {isLoading ? (
        <NoahActivityIndicator size="small" />
      ) : (
        <Icon name={iconNames.android} size={iconSize} color={colors.foreground} />
      )}
    </Pressable>
  );
}
