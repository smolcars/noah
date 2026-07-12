import {
  Button as SwiftButton,
  Host as SwiftHost,
  HStack as SwiftHStack,
  Spacer as SwiftSpacer,
} from "@expo/ui/swift-ui";
import {
  accessibilityIdentifier,
  buttonBorderShape,
  buttonStyle,
  controlSize,
  frame,
  labelStyle,
} from "@expo/ui/swift-ui/modifiers";
import Icon from "@react-native-vector-icons/ionicons";
import { Platform, Pressable, View } from "react-native";

import { useTheme } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

const BUTTON_SIZE = 52;
const ACTION_GROUP_WIDTH = 112;

type NativeHomeHeaderActionsProps = {
  onBoardArk: () => void;
  onOpenQr: () => void;
  onOpenSettings: () => void;
};

export function NativeHomeHeaderActions({
  onBoardArk,
  onOpenQr,
  onOpenSettings,
}: NativeHomeHeaderActionsProps) {
  const { colors } = useTheme();
  const supportsLiquidGlass = Platform.OS === "ios" && Number(Platform.Version) >= 26;

  if (supportsLiquidGlass) {
    return (
      <SwiftHost seedColor={COLORS.BITCOIN_ORANGE} style={{ flex: 1 }}>
        <SwiftHStack alignment="center">
          <SwiftButton
            label="Board Ark"
            systemImage="ferry"
            onPress={onBoardArk}
            modifiers={[
              buttonStyle("glass"),
              buttonBorderShape("circle"),
              controlSize("large"),
              labelStyle("iconOnly"),
              accessibilityIdentifier("home-board-ark-button"),
            ]}
          />
          <SwiftSpacer />
          <SwiftHStack
            alignment="center"
            spacing={8}
            modifiers={[frame({ width: ACTION_GROUP_WIDTH, height: BUTTON_SIZE })]}
          >
            <SwiftButton
              label="Open QR code"
              systemImage="qrcode"
              onPress={onOpenQr}
              modifiers={[
                buttonStyle("glass"),
                buttonBorderShape("circle"),
                controlSize("large"),
                labelStyle("iconOnly"),
                accessibilityIdentifier("home-qr-button"),
              ]}
            />
            <SwiftButton
              label="Open settings"
              systemImage="gearshape"
              onPress={onOpenSettings}
              modifiers={[
                buttonStyle("glass"),
                buttonBorderShape("circle"),
                controlSize("large"),
                labelStyle("iconOnly"),
                accessibilityIdentifier("home-settings-button"),
              ]}
            />
          </SwiftHStack>
        </SwiftHStack>
      </SwiftHost>
    );
  }

  return (
    <View className="flex-1 flex-row items-center justify-between">
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Board Ark"
        android_ripple={{ color: `${COLORS.BITCOIN_ORANGE}33`, borderless: true }}
        onPress={onBoardArk}
        testID="home-board-ark-button"
        style={({ pressed }) => ({
          width: BUTTON_SIZE,
          height: BUTTON_SIZE,
          alignItems: "center",
          justifyContent: "center",
          borderRadius: BUTTON_SIZE / 2,
          borderWidth: 1,
          borderColor: colors.border,
          backgroundColor: `${COLORS.BITCOIN_ORANGE}14`,
          opacity: pressed ? 0.72 : 1,
          overflow: "hidden",
        })}
      >
        <Icon name="boat-outline" size={24} color={colors.foreground} />
      </Pressable>
      <View
        className="flex-row justify-between"
        style={{
          width: ACTION_GROUP_WIDTH,
          height: BUTTON_SIZE,
        }}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Open QR code"
          android_ripple={{ color: `${COLORS.BITCOIN_ORANGE}33`, borderless: true }}
          onPress={onOpenQr}
          testID="home-qr-button"
          style={({ pressed }) => ({
            width: BUTTON_SIZE,
            height: BUTTON_SIZE,
            alignItems: "center",
            justifyContent: "center",
            borderRadius: BUTTON_SIZE / 2,
            borderWidth: 1,
            borderColor: colors.border,
            backgroundColor: `${COLORS.BITCOIN_ORANGE}14`,
            opacity: pressed ? 0.72 : 1,
            overflow: "hidden",
          })}
        >
          <Icon name="qr-code-outline" size={24} color={colors.foreground} />
        </Pressable>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Open settings"
          android_ripple={{ color: `${COLORS.BITCOIN_ORANGE}33`, borderless: true }}
          onPress={onOpenSettings}
          testID="home-settings-button"
          style={({ pressed }) => ({
            width: BUTTON_SIZE,
            height: BUTTON_SIZE,
            alignItems: "center",
            justifyContent: "center",
            borderRadius: BUTTON_SIZE / 2,
            borderWidth: 1,
            borderColor: colors.border,
            backgroundColor: `${COLORS.BITCOIN_ORANGE}14`,
            opacity: pressed ? 0.72 : 1,
            overflow: "hidden",
          })}
        >
          <Icon name="settings-outline" size={24} color={colors.foreground} />
        </Pressable>
      </View>
    </View>
  );
}
