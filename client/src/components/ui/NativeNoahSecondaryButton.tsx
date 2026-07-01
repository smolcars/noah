import { Host } from "@expo/ui";
import { useState } from "react";
import {
  OutlinedButton as ComposeOutlinedButton,
  Shape,
  Text as ComposeText,
  TextButton as ComposeTextButton,
} from "@expo/ui/jetpack-compose";
import { fillMaxSize } from "@expo/ui/jetpack-compose/modifiers";
import { Button as SwiftButton, Text as SwiftText } from "@expo/ui/swift-ui";
import {
  buttonBorderShape,
  buttonStyle,
  controlSize,
  font,
  foregroundStyle,
  frame,
  lineLimit,
  tint,
} from "@expo/ui/swift-ui/modifiers";
import { Platform, View, type StyleProp, type ViewStyle } from "react-native";

import { useTheme } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

type NativeNoahSecondaryButtonEmphasis = "outline" | "ghost";
type NativeNoahSecondaryButtonSize = "default" | "lg";
type NativeNoahSecondaryButtonTone = "neutral" | "accent" | "destructive";

type NativeNoahSecondaryButtonProps = {
  label: string;
  onPress?: () => void;
  disabled?: boolean;
  emphasis?: NativeNoahSecondaryButtonEmphasis;
  size?: NativeNoahSecondaryButtonSize;
  tone?: NativeNoahSecondaryButtonTone;
  width?: number;
  fullWidth?: boolean;
  className?: string;
  style?: StyleProp<ViewStyle>;
  testID?: string;
};

const SECONDARY_COLORS = {
  accent: COLORS.BITCOIN_ORANGE,
  destructive: "#dc2626",
  darkText: "#f8fafc",
  lightText: "#1a2332",
  disabledText: "#64748b",
  disabledBorder: "#cbd5e1",
} as const;

const BUTTON_HEIGHT = {
  default: 48,
  lg: 56,
} as const;

const BUTTON_MIN_WIDTH = {
  default: 132,
  lg: 156,
} as const;

export function NativeNoahSecondaryButton({
  label,
  onPress,
  disabled = false,
  emphasis = "outline",
  size = "default",
  tone = "accent",
  width,
  fullWidth = false,
  className,
  style,
  testID,
}: NativeNoahSecondaryButtonProps) {
  const [measuredWidth, setMeasuredWidth] = useState<number | null>(null);
  const { isDark } = useTheme();
  const height = BUTTON_HEIGHT[size];
  const buttonWidth = width ?? measuredWidth ?? BUTTON_MIN_WIDTH[size];
  const accentColor =
    tone === "destructive" ? SECONDARY_COLORS.destructive : SECONDARY_COLORS.accent;
  const textColor = isDark ? SECONDARY_COLORS.darkText : SECONDARY_COLORS.lightText;
  const iosOutlineStyle =
    Platform.OS === "ios" && emphasis === "outline"
      ? {
          borderWidth: 1,
          borderColor: disabled ? SECONDARY_COLORS.disabledBorder : `${accentColor}66`,
          borderRadius: height / 2,
        }
      : null;
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
          opacity: disabled ? 0.65 : 1,
          overflow: "hidden",
        },
        iosOutlineStyle,
        style,
      ]}
    >
      <Host seedColor={accentColor} style={hostStyle}>
        {Platform.OS === "android" ? (
          <AndroidSecondaryButton
            label={label}
            onPress={onPress}
            disabled={disabled}
            emphasis={emphasis}
            textColor={textColor}
            testID={testID}
          />
        ) : (
          <SwiftButton
            onPress={disabled ? undefined : onPress}
            testID={testID}
            role={tone === "destructive" ? "destructive" : "default"}
            modifiers={[
              buttonStyle("plain"),
              buttonBorderShape("capsule"),
              controlSize(size === "lg" ? "large" : "regular"),
              tint(accentColor),
            ]}
          >
            <SwiftText
              modifiers={[
                frame({ width: buttonWidth, height, alignment: "center" }),
                foregroundStyle(disabled ? SECONDARY_COLORS.disabledText : textColor),
                font({ size: 16, weight: "semibold", design: "default" }),
                lineLimit(1),
              ]}
            >
              {label}
            </SwiftText>
          </SwiftButton>
        )}
      </Host>
    </View>
  );
}

function AndroidSecondaryButton({
  label,
  onPress,
  disabled,
  emphasis,
  textColor,
}: {
  label: string;
  onPress?: () => void;
  disabled: boolean;
  emphasis: NativeNoahSecondaryButtonEmphasis;
  textColor: string;
  testID?: string;
}) {
  const ButtonComponent = emphasis === "outline" ? ComposeOutlinedButton : ComposeTextButton;

  return (
    <ButtonComponent
      onClick={disabled ? undefined : onPress}
      enabled={!disabled}
      modifiers={[fillMaxSize()]}
      shape={Shape.Pill({})}
      contentPadding={{ start: 18, top: 0, end: 18, bottom: 0 }}
      colors={{
        containerColor: "#00000000",
        contentColor: disabled ? SECONDARY_COLORS.disabledText : textColor,
        disabledContainerColor: "#00000000",
        disabledContentColor: SECONDARY_COLORS.disabledText,
      }}
    >
      <ComposeText
        color={disabled ? SECONDARY_COLORS.disabledText : textColor}
        maxLines={1}
        style={{
          fontSize: 16,
          fontWeight: "600",
          textAlign: "center",
          typography: "labelLarge",
        }}
      >
        {label}
      </ComposeText>
    </ButtonComponent>
  );
}
