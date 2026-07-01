import { Host, Switch as ExpoSwitch } from "@expo/ui";
import { Switch as ComposeSwitch } from "@expo/ui/jetpack-compose";
import { testID as testIDModifier } from "@expo/ui/jetpack-compose/modifiers";
import { Platform, View } from "react-native";

type NativeSwitchTone = "primary" | "destructive";

export type NativeSwitchProps = {
  value: boolean;
  onValueChange: (value: boolean) => void;
  disabled?: boolean;
  tone?: NativeSwitchTone;
  testID?: string;
};

const SWITCH_COLORS = {
  primary: "#F7931A",
  destructive: "#dc2626",
  checkedThumb: "#ffffff",
  uncheckedTrack: "#d1d5db",
  uncheckedThumb: "#f8fafc",
  disabledCheckedTrack: "#f0c58d",
  disabledUncheckedTrack: "#e5e7eb",
  disabledThumb: "#cbd5e1",
} as const;

const SWITCH_CONTAINER_STYLE = {
  width: 72,
  height: 40,
  flexShrink: 0,
  marginLeft: 16,
  alignItems: "flex-end",
  justifyContent: "center",
} as const;

const SWITCH_HOST_STYLE = {
  width: 56,
  height: 36,
  alignItems: "flex-end",
  justifyContent: "center",
} as const;

export function NativeSwitch({
  value,
  onValueChange,
  disabled = false,
  tone = "primary",
  testID,
}: NativeSwitchProps) {
  return (
    <View style={SWITCH_CONTAINER_STYLE}>
      <Host seedColor={SWITCH_COLORS[tone]} style={SWITCH_HOST_STYLE}>
        {Platform.OS === "android" ? (
          <ComposeSwitch
            value={value}
            enabled={!disabled}
            onCheckedChange={disabled ? undefined : onValueChange}
            modifiers={testID ? [testIDModifier(testID)] : undefined}
            colors={{
              checkedTrackColor: SWITCH_COLORS[tone],
              checkedThumbColor: SWITCH_COLORS.checkedThumb,
              uncheckedTrackColor: SWITCH_COLORS.uncheckedTrack,
              uncheckedThumbColor: SWITCH_COLORS.uncheckedThumb,
              disabledCheckedTrackColor: SWITCH_COLORS.disabledCheckedTrack,
              disabledCheckedThumbColor: SWITCH_COLORS.disabledThumb,
              disabledUncheckedTrackColor: SWITCH_COLORS.disabledUncheckedTrack,
              disabledUncheckedThumbColor: SWITCH_COLORS.disabledThumb,
            }}
          />
        ) : (
          <ExpoSwitch
            value={value}
            onValueChange={onValueChange}
            disabled={disabled}
            testID={testID}
          />
        )}
      </Host>
    </View>
  );
}
