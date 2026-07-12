import { Host } from "@expo/ui";
import {
  LazyColumn as ComposeLazyColumn,
  ListItem as ComposeListItem,
  RadioButton as ComposeRadioButton,
  Text as ComposeText,
} from "@expo/ui/jetpack-compose";
import {
  Shapes,
  clip,
  fillMaxSize,
  fillMaxWidth,
  selectable,
  selectableGroup,
  testID as composeTestID,
} from "@expo/ui/jetpack-compose/modifiers";
import {
  Button as SwiftButton,
  HStack as SwiftHStack,
  Image as SwiftImage,
  List as SwiftList,
  Spacer as SwiftSpacer,
  Text as SwiftText,
  VStack as SwiftVStack,
} from "@expo/ui/swift-ui";
import {
  accessibilityIdentifier,
  background,
  buttonStyle,
  contentShape,
  font,
  foregroundStyle,
  frame,
  listRowBackground,
  listStyle,
  scrollContentBackground,
  shapes,
} from "@expo/ui/swift-ui/modifiers";
import { Platform, View } from "react-native";

import { useTheme, type ThemeColors } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

export type NativeNoahSelectionListOption<T extends string> = {
  value: T;
  title: string;
  subtitle: string;
};

type NativeNoahSelectionListProps<T extends string> = {
  value: T;
  options: readonly NativeNoahSelectionListOption<T>[];
  onValueChange: (value: T) => void;
  testID?: string;
};

export function NativeNoahSelectionList<T extends string>({
  value,
  options,
  onValueChange,
  testID,
}: NativeNoahSelectionListProps<T>) {
  const { colors, colorScheme } = useTheme();

  return (
    <View className="flex-1">
      <Host
        seedColor={COLORS.BITCOIN_ORANGE}
        colorScheme={colorScheme}
        style={{ flex: 1 }}
      >
        {Platform.OS === "android" ? (
          <AndroidSelectionList
            value={value}
            options={options}
            onValueChange={onValueChange}
            colors={colors}
            testID={testID}
          />
        ) : (
          <IOSSelectionList
            value={value}
            options={options}
            onValueChange={onValueChange}
            colors={colors}
            testID={testID}
          />
        )}
      </Host>
    </View>
  );
}

function AndroidSelectionList<T extends string>({
  value,
  options,
  onValueChange,
  colors,
  testID,
}: NativeNoahSelectionListProps<T> & { colors: ThemeColors }) {
  return (
    <ComposeLazyColumn
      contentPadding={{ start: 20, top: 24, end: 20, bottom: 32 }}
      verticalArrangement={{ spacedBy: 8 }}
      modifiers={[fillMaxSize(), selectableGroup()]}
    >
      {options.map((option) => (
        <ComposeListItem
          key={option.value}
          colors={{
            containerColor: colors.card,
            contentColor: colors.foreground,
            supportingContentColor: colors.mutedForeground,
            trailingContentColor: COLORS.BITCOIN_ORANGE,
          }}
          modifiers={[
            fillMaxWidth(),
            clip(Shapes.RoundedCorner(16)),
            selectable(
              value === option.value,
              () => onValueChange(option.value),
              "radioButton",
            ),
            ...(testID ? [composeTestID(`${testID}-${option.value}`)] : []),
          ]}
        >
          <ComposeListItem.HeadlineContent>
            <ComposeText
              color={colors.foreground}
              maxLines={1}
              style={{ fontSize: 16, fontWeight: "600", typography: "bodyLarge" }}
            >
              {option.title}
            </ComposeText>
          </ComposeListItem.HeadlineContent>
          <ComposeListItem.SupportingContent>
            <ComposeText
              color={colors.mutedForeground}
              maxLines={2}
              style={{ fontSize: 14, typography: "bodyMedium" }}
            >
              {option.subtitle}
            </ComposeText>
          </ComposeListItem.SupportingContent>
          <ComposeListItem.TrailingContent>
            <ComposeRadioButton selected={value === option.value} />
          </ComposeListItem.TrailingContent>
        </ComposeListItem>
      ))}
    </ComposeLazyColumn>
  );
}

function IOSSelectionList<T extends string>({
  value,
  options,
  onValueChange,
  colors,
  testID,
}: NativeNoahSelectionListProps<T> & { colors: ThemeColors }) {
  return (
    <SwiftList
      modifiers={[
        listStyle("insetGrouped"),
        scrollContentBackground("hidden"),
        background(colors.background),
      ]}
    >
      {options.map((option) => (
        <SwiftButton
          key={option.value}
          onPress={() => onValueChange(option.value)}
          modifiers={[
            buttonStyle("plain"),
            listRowBackground(colors.card),
            ...(testID
              ? [accessibilityIdentifier(`${testID}-${option.value}`)]
              : []),
          ]}
        >
          <SwiftHStack
            alignment="center"
            spacing={12}
            modifiers={[
              frame({ maxWidth: Infinity, minHeight: 52, alignment: "leading" }),
              contentShape(shapes.rectangle()),
            ]}
          >
            <SwiftVStack alignment="leading" spacing={4}>
              <SwiftText
                modifiers={[
                  font({ textStyle: "body", weight: "semibold" }),
                  foregroundStyle(colors.foreground),
                ]}
              >
                {option.title}
              </SwiftText>
              <SwiftText
                modifiers={[
                  font({ textStyle: "subheadline" }),
                  foregroundStyle(colors.mutedForeground),
                ]}
              >
                {option.subtitle}
              </SwiftText>
            </SwiftVStack>
            <SwiftSpacer />
            <SwiftImage
              systemName={value === option.value ? "checkmark.circle.fill" : "circle"}
              size={22}
              color={value === option.value ? COLORS.BITCOIN_ORANGE : colors.mutedForeground}
            />
          </SwiftHStack>
        </SwiftButton>
      ))}
    </SwiftList>
  );
}
