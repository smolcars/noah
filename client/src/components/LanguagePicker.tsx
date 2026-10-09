import { useGT, useLocaleSelector } from "gt-react-native";
import { MenuView } from "@expo/ui/community/menu";
import { Host, HStack, Image, Menu, Picker, Text as SwiftText } from "@expo/ui/swift-ui";
import {
  accessibilityLabel,
  accessibilityValue,
  background,
  buttonStyle,
  cornerRadius,
  font,
  foregroundStyle,
  frame,
  padding,
  tag,
} from "@expo/ui/swift-ui/modifiers";
import Icon from "@react-native-vector-icons/ionicons";
import { Platform, View } from "react-native";
import { Text } from "~/components/ui/text";
import { useTheme } from "~/hooks/useTheme";

export function LanguagePicker({ testID }: { testID: string }) {
  const gt = useGT();
  const { colors, colorScheme, isDark } = useTheme();
  const backgroundColor = isDark ? colors.tabBarBackground : colors.card;
  const { locale, locales, setLocale, getLocaleProperties } = useLocaleSelector();
  const actions = locales.map((code) => {
    const name = getLocaleProperties(code).nativeName;
    return {
      id: code,
      title: name.charAt(0).toLocaleUpperCase(code) + name.slice(1),
      state: code === locale ? ("on" as const) : ("off" as const),
    };
  });
  const selectedName = actions.find((action) => action.id === locale)?.title;
  const label = gt("Language", { $context: "App display language picker." });
  const trigger = (
    <View
      accessible={Platform.OS === "android"}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityValue={{ text: selectedName }}
      className="h-11 flex-row items-center gap-2 rounded-full border border-border px-4"
      style={{ backgroundColor }}
    >
      <Icon name="globe-outline" size={18} color={colors.mutedForeground} />
      <Text className="text-sm font-semibold text-foreground">{selectedName}</Text>
      <Icon name="chevron-down" size={14} color={colors.mutedForeground} />
    </View>
  );

  if (Platform.OS === "ios") {
    return (
      <Host matchContents colorScheme={colorScheme} ignoreSafeArea="all" testID={testID}>
        <Menu
          label={
            <HStack
              spacing={8}
              modifiers={[
                padding({ horizontal: 16 }),
                frame({ height: 44 }),
                background(backgroundColor),
                cornerRadius(22),
              ]}
            >
              <Image systemName="globe" size={18} color={colors.mutedForeground} />
              <SwiftText
                modifiers={[
                  font({ size: 14, weight: "semibold" }),
                  foregroundStyle(colors.foreground),
                ]}
              >
                {selectedName}
              </SwiftText>
              <Image systemName="chevron.down" size={12} color={colors.mutedForeground} />
            </HStack>
          }
          modifiers={[
            buttonStyle("plain"),
            accessibilityLabel(label),
            accessibilityValue(selectedName ?? ""),
          ]}
        >
          <Picker selection={locale} onSelectionChange={setLocale}>
            {actions.map((action) => (
              <SwiftText key={action.id} modifiers={[tag(action.id)]}>
                {action.title}
              </SwiftText>
            ))}
          </Picker>
        </Menu>
      </Host>
    );
  }

  return (
    <MenuView
      actions={actions}
      onPressAction={({ nativeEvent }) => setLocale(nativeEvent.event)}
      colorScheme={colorScheme}
      testID={testID}
    >
      {trigger}
    </MenuView>
  );
}
