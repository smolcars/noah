import { Host } from "@expo/ui";
import { useState } from "react";
import {
  Button as ComposeButton,
  Shape,
  Text as ComposeText,
} from "@expo/ui/jetpack-compose";
import { fillMaxSize } from "@expo/ui/jetpack-compose/modifiers";
import { Button as SwiftButton, Text as SwiftText } from "@expo/ui/swift-ui";
import {
  background,
  buttonBorderShape,
  buttonStyle,
  cornerRadius,
  controlSize,
  font,
  foregroundStyle,
  frame,
  lineLimit,
  tint,
} from "@expo/ui/swift-ui/modifiers";
import { Platform, View, type StyleProp, type ViewStyle } from "react-native";

import { COLORS } from "~/lib/styleConstants";

type NativeNoahButtonVariant = "primary" | "destructive";
type NativeNoahButtonSize = "default" | "lg";

type NativeNoahButtonProps = {
  label: string;
  onPress?: () => void;
  disabled?: boolean;
  isLoading?: boolean;
  loadingLabel?: string;
  variant?: NativeNoahButtonVariant;
  size?: NativeNoahButtonSize;
  width?: number;
  fullWidth?: boolean;
  className?: string;
  style?: StyleProp<ViewStyle>;
  testID?: string;
};

const BUTTON_COLORS = {
  primary: COLORS.BITCOIN_ORANGE,
  destructive: "#dc2626",
  primaryText: "#1a1a1a",
  destructiveText: "#ffffff",
  disabledContainer: "#d1d5db",
  disabledText: "#64748b",
} as const;

const BUTTON_HEIGHT = {
  default: 48,
  lg: 56,
} as const;

const BUTTON_MIN_WIDTH = {
  default: 132,
  lg: 156,
} as const;

const composeButtonMap = {
  primary: ComposeButton,
  destructive: ComposeButton,
} as const;

const swiftButtonStyleMap = {
  primary: "borderedProminent",
  destructive: "borderedProminent",
} as const;

export function NativeNoahButton({
  label,
  onPress,
  disabled = false,
  isLoading = false,
  loadingLabel,
  variant = "primary",
  size = "default",
  width,
  fullWidth = false,
  className,
  style,
  testID,
}: NativeNoahButtonProps) {
  const [measuredWidth, setMeasuredWidth] = useState<number | null>(null);
  const isDisabled = disabled || isLoading;
  const displayedLabel = isLoading ? (loadingLabel ?? "Loading...") : label;
  const height = BUTTON_HEIGHT[size];
  const activeColor =
    variant === "destructive" ? BUTTON_COLORS.destructive : BUTTON_COLORS.primary;
  const contentColor =
    variant === "destructive" ? BUTTON_COLORS.destructiveText : BUTTON_COLORS.primaryText;
  const buttonWidth = width ?? measuredWidth ?? BUTTON_MIN_WIDTH[size];
  const hostStyle = {
    width: "100%",
    height,
  } as const;

  return (
    <View
      className={className}
      onLayout={(event) => {
        if (width === undefined && fullWidth) {
          setMeasuredWidth(event.nativeEvent.layout.width);
        }
      }}
      style={[
        {
          width: fullWidth ? "100%" : buttonWidth,
          height,
          opacity: isDisabled ? 0.65 : 1,
          overflow: "hidden",
        },
        style,
      ]}
    >
      <Host seedColor={activeColor} style={hostStyle}>
        {Platform.OS === "android" ? (
          <AndroidButton
            label={displayedLabel}
            onPress={onPress}
            disabled={isDisabled}
            variant={variant}
            activeColor={activeColor}
            contentColor={contentColor}
            height={height}
            testID={testID}
          />
        ) : (
          <SwiftButton
            onPress={isDisabled ? undefined : onPress}
            testID={testID}
            role={variant === "destructive" ? "destructive" : "default"}
            modifiers={[
              buttonStyle(
                variant === "primary" || variant === "destructive"
                  ? "plain"
                  : swiftButtonStyleMap[variant],
              ),
              buttonBorderShape("capsule"),
              controlSize(size === "lg" ? "large" : "regular"),
              tint(activeColor),
            ]}
          >
            <SwiftText
              modifiers={[
                frame({ width: buttonWidth, height, alignment: "center" }),
                background(
                  isDisabled
                    ? BUTTON_COLORS.disabledContainer
                    : variant === "primary" || variant === "destructive"
                      ? activeColor
                      : "#00000000",
                ),
                cornerRadius(height / 2),
                foregroundStyle(isDisabled ? BUTTON_COLORS.disabledText : contentColor),
                font({ size: 15, weight: "bold", design: "default" }),
                lineLimit(1),
              ]}
            >
              {displayedLabel}
            </SwiftText>
          </SwiftButton>
        )}
      </Host>
    </View>
  );
}

function AndroidButton({
  label,
  onPress,
  disabled,
  variant,
  activeColor,
  contentColor,
}: {
  label: string;
  onPress?: () => void;
  disabled: boolean;
  variant: NativeNoahButtonVariant;
  activeColor: string;
  contentColor: string;
  height: number;
  testID?: string;
}) {
  const ButtonComponent = composeButtonMap[variant];

  return (
    <ButtonComponent
      onClick={disabled ? undefined : onPress}
      enabled={!disabled}
      modifiers={[fillMaxSize()]}
      shape={Shape.Pill({})}
      contentPadding={{ start: 18, top: 0, end: 18, bottom: 0 }}
      colors={{
        containerColor: activeColor,
        contentColor,
        disabledContainerColor: BUTTON_COLORS.disabledContainer,
        disabledContentColor: BUTTON_COLORS.disabledText,
      }}
    >
      <ComposeText
        color={disabled ? BUTTON_COLORS.disabledText : contentColor}
        maxLines={1}
        style={{
          fontSize: 16,
          fontWeight: "700",
          textAlign: "center",
          typography: "labelLarge",
        }}
      >
        {label}
      </ComposeText>
    </ButtonComponent>
  );
}
