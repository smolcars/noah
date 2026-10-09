import { Host } from "@expo/ui";
import {
  DropdownMenuItem,
  ExposedDropdownMenu,
  ExposedDropdownMenuBox,
  Shape,
  Text as ComposeText,
  TextField as ComposeTextField,
  useNativeState,
} from "@expo/ui/jetpack-compose";
import {
  fillMaxWidth,
  menuAnchor,
  semantics,
  testID as composeTestID,
} from "@expo/ui/jetpack-compose/modifiers";
import { Picker as SwiftPicker, Text as SwiftText } from "@expo/ui/swift-ui";
import {
  accessibilityLabel as swiftAccessibilityLabel,
  disabled as swiftDisabled,
  foregroundStyle,
  frame,
  pickerStyle,
  tag,
  tint,
} from "@expo/ui/swift-ui/modifiers";
import { useEffect, useState } from "react";
import {
  Platform,
  View,
  type LayoutChangeEvent,
  type StyleProp,
  type ViewStyle,
} from "react-native";

import { useTheme, type ThemeColors } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

const PICKER_HEIGHT = 56;
const TRANSPARENT = "#00000000";

export type NativeNoahPickerOption<T extends string> = {
  label: string;
  value: T;
};

type NativeNoahPickerProps<T extends string> = {
  value: T;
  options: readonly NativeNoahPickerOption<T>[];
  onValueChange: (value: T) => void;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  testID?: string;
  accessibilityLabel?: string;
};

export function NativeNoahPicker<T extends string>({
  value,
  options,
  onValueChange,
  disabled = false,
  style,
  testID,
  accessibilityLabel,
}: NativeNoahPickerProps<T>) {
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
          height: PICKER_HEIGHT,
          overflow: "hidden",
          borderWidth: 1,
          borderColor: colors.border,
          borderRadius: 8,
          backgroundColor: colors.background,
          opacity: disabled ? 0.65 : 1,
        },
        style,
      ]}
    >
      <Host
        seedColor={COLORS.BITCOIN_ORANGE}
        colorScheme={colorScheme}
        style={{ width: "100%", height: PICKER_HEIGHT }}
      >
        {Platform.OS === "android" ? (
          <AndroidNoahPicker
            value={value}
            options={options}
            onValueChange={onValueChange}
            disabled={disabled}
            colors={colors}
            testID={testID}
            accessibilityLabel={accessibilityLabel}
          />
        ) : (
          <SwiftNoahPicker
            value={value}
            options={options}
            onValueChange={onValueChange}
            disabled={disabled}
            colors={colors}
            width={measuredWidth === undefined ? undefined : Math.max(measuredWidth - 2, 0)}
            testID={testID}
            accessibilityLabel={accessibilityLabel}
          />
        )}
      </Host>
    </View>
  );
}

function AndroidNoahPicker<T extends string>({
  value,
  options,
  onValueChange,
  disabled,
  colors,
  testID,
  accessibilityLabel,
}: Omit<NativeNoahPickerProps<T>, "style"> & { colors: ThemeColors }) {
  const [expanded, setExpanded] = useState(false);
  const selectedLabel = options.find((option) => option.value === value)?.label ?? "";
  const labelState = useNativeState(selectedLabel);

  useEffect(() => {
    labelState.set(selectedLabel);
  }, [labelState, selectedLabel]);

  return (
    <ExposedDropdownMenuBox
      expanded={expanded}
      onExpandedChange={disabled ? undefined : setExpanded}
      modifiers={[fillMaxWidth()]}
    >
      <ComposeTextField
        value={labelState}
        readOnly
        singleLine
        enabled={!disabled}
        modifiers={[
          menuAnchor("primaryNotEditable", !disabled),
          fillMaxWidth(),
          ...(testID ? [composeTestID(testID)] : []),
          ...(accessibilityLabel ? [semantics({ contentDescription: accessibilityLabel })] : []),
        ]}
        shape={Shape.RoundedCorner({
          cornerRadii: { topStart: 8, topEnd: 8, bottomStart: 8, bottomEnd: 8 },
        })}
        textStyle={{ color: colors.foreground, fontSize: 16 }}
        colors={{
          focusedTextColor: colors.foreground,
          unfocusedTextColor: colors.foreground,
          disabledTextColor: colors.mutedForeground,
          focusedContainerColor: TRANSPARENT,
          unfocusedContainerColor: TRANSPARENT,
          disabledContainerColor: TRANSPARENT,
          focusedIndicatorColor: TRANSPARENT,
          unfocusedIndicatorColor: TRANSPARENT,
          disabledIndicatorColor: TRANSPARENT,
          focusedTrailingIconColor: colors.mutedForeground,
          unfocusedTrailingIconColor: colors.mutedForeground,
          disabledTrailingIconColor: colors.mutedForeground,
        }}
      >
        <ComposeTextField.TrailingIcon>
          <ComposeText color={colors.mutedForeground} style={{ fontSize: 16 }}>
            ▾
          </ComposeText>
        </ComposeTextField.TrailingIcon>
      </ComposeTextField>
      <ExposedDropdownMenu
        expanded={expanded}
        onDismissRequest={() => setExpanded(false)}
        containerColor={colors.card}
      >
        {options.map((option) => (
          <DropdownMenuItem
            key={option.value}
            elementColors={{ textColor: colors.foreground }}
            onClick={() => {
              onValueChange(option.value);
              setExpanded(false);
            }}
          >
            <DropdownMenuItem.Text>
              <ComposeText color={colors.foreground}>{option.label}</ComposeText>
            </DropdownMenuItem.Text>
          </DropdownMenuItem>
        ))}
      </ExposedDropdownMenu>
    </ExposedDropdownMenuBox>
  );
}

function SwiftNoahPicker<T extends string>({
  value,
  options,
  onValueChange,
  disabled,
  colors,
  width,
  testID,
  accessibilityLabel,
}: Omit<NativeNoahPickerProps<T>, "style"> & {
  colors: ThemeColors;
  width?: number;
}) {
  return (
    <SwiftPicker
      selection={value}
      onSelectionChange={onValueChange}
      testID={testID}
      modifiers={[
        pickerStyle("menu"),
        frame({ width, height: PICKER_HEIGHT, alignment: "leading" }),
        tint(colors.foreground),
        foregroundStyle(colors.foreground),
        ...(disabled ? [swiftDisabled(true)] : []),
        ...(accessibilityLabel ? [swiftAccessibilityLabel(accessibilityLabel)] : []),
      ]}
    >
      {options.map((option) => (
        <SwiftText
          key={option.value}
          modifiers={[tag(option.value), foregroundStyle(colors.foreground)]}
        >
          {option.label}
        </SwiftText>
      ))}
    </SwiftPicker>
  );
}
