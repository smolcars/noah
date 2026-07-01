import { Host } from "@expo/ui";
import { useEffect, useRef } from "react";
import { Slider as ComposeSlider } from "@expo/ui/jetpack-compose";
import { fillMaxWidth } from "@expo/ui/jetpack-compose/modifiers";
import { Slider as SwiftSlider } from "@expo/ui/swift-ui";
import { disabled as swiftDisabled, frame, tint as swiftTint } from "@expo/ui/swift-ui/modifiers";
import { Platform, View, type StyleProp, type ViewStyle } from "react-native";

import { useTheme } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

type NativeNoahSliderProps = {
  value: number;
  minimumValue?: number;
  maximumValue?: number;
  disabled?: boolean;
  minimumTrackTintColor?: string;
  maximumTrackTintColor?: string;
  thumbTintColor?: string;
  style?: StyleProp<ViewStyle>;
  onValueChange?: (value: number) => void;
  onSlidingComplete?: (value: number) => void;
};

const SLIDER_HEIGHT = 40;

export function NativeNoahSlider({
  value,
  minimumValue = 0,
  maximumValue = 1,
  disabled = false,
  minimumTrackTintColor = COLORS.BITCOIN_ORANGE,
  maximumTrackTintColor,
  thumbTintColor,
  style,
  onValueChange,
  onSlidingComplete,
}: NativeNoahSliderProps) {
  const { colors, colorScheme } = useTheme();
  const inactiveTrackColor = maximumTrackTintColor ?? colors.border;
  const thumbColor = thumbTintColor ?? minimumTrackTintColor;
  const clampedValue = clamp(value, minimumValue, maximumValue);
  const latestValueRef = useRef(clampedValue);

  useEffect(() => {
    latestValueRef.current = clampedValue;
  }, [clampedValue]);

  const handleValueChange = (nextValue: number) => {
    latestValueRef.current = nextValue;
    onValueChange?.(nextValue);
  };

  const handleSlidingComplete = () => {
    onSlidingComplete?.(latestValueRef.current);
  };

  return (
    <View style={[{ width: "100%", height: SLIDER_HEIGHT, justifyContent: "center" }, style]}>
      <Host
        seedColor={minimumTrackTintColor}
        colorScheme={colorScheme}
        matchContents={{ vertical: true }}
        style={{ width: "100%", height: SLIDER_HEIGHT }}
      >
        {Platform.OS === "android" ? (
          <ComposeSlider
            value={clampedValue}
            min={minimumValue}
            max={maximumValue}
            enabled={!disabled}
            colors={{
              activeTrackColor: minimumTrackTintColor,
              inactiveTrackColor,
              thumbColor,
            }}
            modifiers={[fillMaxWidth()]}
            onValueChange={handleValueChange}
            onValueChangeFinished={handleSlidingComplete}
          />
        ) : (
          <SwiftSlider
            value={clampedValue}
            min={minimumValue}
            max={maximumValue}
            onValueChange={handleValueChange}
            onEditingChanged={(isEditing) => {
              if (!isEditing) {
                handleSlidingComplete();
              }
            }}
            modifiers={[
              frame({ height: SLIDER_HEIGHT }),
              swiftTint(minimumTrackTintColor),
              ...(disabled ? [swiftDisabled(true)] : []),
            ]}
          />
        )}
      </Host>
    </View>
  );
}

function clamp(value: number, minimumValue: number, maximumValue: number) {
  return Math.min(maximumValue, Math.max(minimumValue, value));
}
