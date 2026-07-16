import {
  Button as SwiftButton,
  Divider as SwiftDivider,
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
  glassEffect,
  labelStyle,
} from "@expo/ui/swift-ui/modifiers";
import Icon from "@react-native-vector-icons/ionicons";
import { Platform, Pressable, View } from "react-native";

import { useTheme } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

const BUTTON_SIZE = 52;
const ACTION_GROUP_WIDTH = 112;

type NativeHomeHeaderActionsProps = {
  onOpenPlaces: () => void;
  onOpenQr: () => void;
  onOpenSettings: () => void;
};

export function NativeHomeHeaderActions({
  onOpenPlaces,
  onOpenQr,
  onOpenSettings,
}: NativeHomeHeaderActionsProps) {
  const { colors } = useTheme();
  const supportsLiquidGlass = Platform.OS === "ios" && Number(Platform.Version) >= 26;

  if (supportsLiquidGlass) {
    return (
      <SwiftHost seedColor={COLORS.BITCOIN_ORANGE} style={{ flex: 1 }}>
        <SwiftHStack alignment="center">
          <SwiftHStack
            alignment="center"
            spacing={0}
            modifiers={[
              frame({ width: ACTION_GROUP_WIDTH, height: BUTTON_SIZE }),
              glassEffect({
                glass: { variant: "regular", interactive: true },
                shape: "capsule",
              }),
            ]}
          >
            <SwiftButton
              label="Open settings"
              systemImage="gearshape"
              onPress={onOpenSettings}
              modifiers={[
                buttonStyle("borderless"),
                controlSize("large"),
                labelStyle("iconOnly"),
                frame({ width: BUTTON_SIZE, height: BUTTON_SIZE }),
                accessibilityIdentifier("home-settings-button"),
              ]}
            />
            <SwiftDivider modifiers={[frame({ height: 22 })]} />
            <SwiftButton
              label="Find places that accept bitcoin"
              systemImage="map"
              onPress={onOpenPlaces}
              modifiers={[
                buttonStyle("borderless"),
                controlSize("large"),
                labelStyle("iconOnly"),
                frame({ width: BUTTON_SIZE, height: BUTTON_SIZE }),
                accessibilityIdentifier("home-btc-map-button"),
              ]}
            />
          </SwiftHStack>
          <SwiftSpacer />
          <SwiftHStack
            alignment="center"
            modifiers={[frame({ width: BUTTON_SIZE, height: BUTTON_SIZE })]}
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
          </SwiftHStack>
        </SwiftHStack>
      </SwiftHost>
    );
  }

  return (
    <View className="flex-1 flex-row items-center justify-between">
      <View
        className="flex-row overflow-hidden"
        style={{
          width: ACTION_GROUP_WIDTH,
          height: BUTTON_SIZE,
          borderRadius: BUTTON_SIZE / 2,
          borderWidth: 1,
          borderColor: colors.border,
          backgroundColor: `${COLORS.BITCOIN_ORANGE}14`,
        }}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Open settings"
          android_ripple={{ color: `${COLORS.BITCOIN_ORANGE}33` }}
          onPress={onOpenSettings}
          testID="home-settings-button"
          style={({ pressed }) => ({
            width: ACTION_GROUP_WIDTH / 2,
            height: BUTTON_SIZE,
            alignItems: "center",
            justifyContent: "center",
            opacity: pressed ? 0.72 : 1,
          })}
        >
          <Icon name="settings-outline" size={24} color={colors.foreground} />
        </Pressable>
        <View className="my-3 w-px bg-border" />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Find places that accept bitcoin"
          android_ripple={{ color: `${COLORS.BITCOIN_ORANGE}33` }}
          onPress={onOpenPlaces}
          testID="home-btc-map-button"
          style={({ pressed }) => ({
            width: ACTION_GROUP_WIDTH / 2 - 1,
            height: BUTTON_SIZE,
            alignItems: "center",
            justifyContent: "center",
            opacity: pressed ? 0.72 : 1,
          })}
        >
          <Icon name="map-outline" size={23} color={colors.foreground} />
        </Pressable>
      </View>
      <View
        className="flex-row"
        style={{
          width: BUTTON_SIZE,
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
      </View>
    </View>
  );
}
