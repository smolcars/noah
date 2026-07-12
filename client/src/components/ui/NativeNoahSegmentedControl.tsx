import { Host } from "@expo/ui";
import {
  SegmentedButton as ComposeSegmentedButton,
  SingleChoiceSegmentedButtonRow,
  Text as ComposeText,
} from "@expo/ui/jetpack-compose";
import { fillMaxWidth, testID as composeTestID, weight } from "@expo/ui/jetpack-compose/modifiers";
import { Picker as SwiftPicker, Text as SwiftText } from "@expo/ui/swift-ui";
import {
  disabled as swiftDisabled,
  frame,
  pickerStyle,
  tag,
  tint,
} from "@expo/ui/swift-ui/modifiers";
import { useState } from "react";
import {
  Platform,
  View,
  type LayoutChangeEvent,
  type StyleProp,
  type ViewStyle,
} from "react-native";

import { useTheme, type ThemeColors } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

const SEGMENTED_CONTROL_HEIGHT = 44;

export type NativeNoahSegmentedControlOption<T extends string> = {
  label: string;
  value: T;
};

type NativeNoahSegmentedControlProps<T extends string> = {
  value: T;
  options: readonly NativeNoahSegmentedControlOption<T>[];
  onValueChange: (value: T) => void;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  testID?: string;
};

export function NativeNoahSegmentedControl<T extends string>({
  value,
  options,
  onValueChange,
  disabled = false,
  style,
  testID,
}: NativeNoahSegmentedControlProps<T>) {
  const { colors, colorScheme } = useTheme();
  const [measuredWidth, setMeasuredWidth] = useState<number>();

  const handleLayout = (event: LayoutChangeEvent) => {
    setMeasuredWidth(event.nativeEvent.layout.width);
  };

  return (
    <View
      onLayout={handleLayout}
      style={[
        {
          width: "100%",
          height: SEGMENTED_CONTROL_HEIGHT,
          opacity: disabled ? 0.65 : 1,
        },
        style,
      ]}
    >
      <Host
        seedColor={COLORS.BITCOIN_ORANGE}
        colorScheme={colorScheme}
        style={{ width: "100%", height: SEGMENTED_CONTROL_HEIGHT }}
      >
        {Platform.OS === "android" ? (
          <AndroidSegmentedControl
            value={value}
            options={options}
            onValueChange={onValueChange}
            disabled={disabled}
            colors={colors}
            testID={testID}
          />
        ) : (
          <IOSSegmentedControl
            value={value}
            options={options}
            onValueChange={onValueChange}
            disabled={disabled}
            width={measuredWidth}
            testID={testID}
          />
        )}
      </Host>
    </View>
  );
}

function AndroidSegmentedControl<T extends string>({
  value,
  options,
  onValueChange,
  disabled,
  colors,
  testID,
}: Omit<NativeNoahSegmentedControlProps<T>, "style"> & { colors: ThemeColors }) {
  return (
    <SingleChoiceSegmentedButtonRow modifiers={[fillMaxWidth()]}>
      {options.map((option) => (
        <ComposeSegmentedButton
          key={option.value}
          selected={value === option.value}
          enabled={!disabled}
          onClick={() => onValueChange(option.value)}
          modifiers={[weight(1), ...(testID ? [composeTestID(`${testID}-${option.value}`)] : [])]}
          colors={{
            activeBorderColor: colors.border,
            inactiveBorderColor: colors.border,
            activeContentColor: colors.foreground,
            inactiveContentColor: colors.mutedForeground,
            activeContainerColor: `${COLORS.BITCOIN_ORANGE}1f`,
            inactiveContainerColor: colors.card,
          }}
        >
          <ComposeSegmentedButton.Label>
            <ComposeText
              color={value === option.value ? colors.foreground : colors.mutedForeground}
              maxLines={1}
              style={{ fontSize: 14, fontWeight: "600", typography: "labelLarge" }}
            >
              {option.label}
            </ComposeText>
          </ComposeSegmentedButton.Label>
        </ComposeSegmentedButton>
      ))}
    </SingleChoiceSegmentedButtonRow>
  );
}

function IOSSegmentedControl<T extends string>({
  value,
  options,
  onValueChange,
  disabled,
  width,
  testID,
}: Omit<NativeNoahSegmentedControlProps<T>, "style"> & { width?: number }) {
  return (
    <SwiftPicker
      selection={value}
      onSelectionChange={onValueChange}
      testID={testID}
      modifiers={[
        pickerStyle("segmented"),
        frame({ width, height: SEGMENTED_CONTROL_HEIGHT }),
        tint(COLORS.BITCOIN_ORANGE),
        ...(disabled ? [swiftDisabled(true)] : []),
      ]}
    >
      {options.map((option) => (
        <SwiftText key={option.value} modifiers={[tag(option.value)]}>
          {option.label}
        </SwiftText>
      ))}
    </SwiftPicker>
  );
}
