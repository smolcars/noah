import Icon from "@react-native-vector-icons/ionicons";
import { Pressable, View } from "react-native";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";

import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { Text } from "~/components/ui/text";
import { useThemeColors } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

export type SendChoiceOption<T extends string> = {
  value: T;
  title: string;
  subtitle: string;
  detail?: string;
  unavailableReason?: string;
};

type SendChoiceStageProps<T extends string> = {
  title: string;
  description: string;
  options: readonly SendChoiceOption<T>[];
  value: T | null;
  onBack: () => void;
  onChange: (value: T) => void;
  onContinue: () => void;
  testIDPrefix: string;
};

export function SendChoiceStage<T extends string>({
  title,
  description,
  options,
  value,
  onBack,
  onChange,
  onContinue,
  testIDPrefix,
}: SendChoiceStageProps<T>) {
  const colors = useThemeColors();
  const bottomTabBarHeight = useBottomTabBarHeight();
  const selectedOption = options.find((option) => option.value === value);
  const canContinue = selectedOption !== undefined && !selectedOption.unavailableReason;

  return (
    <View className="flex-1 px-5" testID={`${testIDPrefix}-stage`}>
      <View className="flex-row items-center pt-4">
        <NativeNoahBackButton onPress={onBack} testID={`${testIDPrefix}-back`} />
      </View>

      <View className="mt-8">
        <Text accessibilityRole="header" className="text-3xl font-bold text-foreground">
          {title}
        </Text>
        <Text className="mt-2 max-w-[330px] text-base leading-6 text-muted-foreground">
          {description}
        </Text>
      </View>

      <View className="mt-9 flex-1">
        {options.map((option, index) => {
          const isSelected = option.value === value;
          const isUnavailable = option.unavailableReason !== undefined;

          return (
            <Pressable
              key={option.value}
              accessibilityRole="radio"
              accessibilityLabel={`${option.title}. ${option.subtitle}`}
              accessibilityState={{ checked: isSelected, disabled: isUnavailable }}
              disabled={isUnavailable}
              onPress={() => onChange(option.value)}
              className={`flex-row items-center gap-4 py-5 ${
                index < options.length - 1 ? "border-b border-border/60" : ""
              }`}
              testID={`${testIDPrefix}-${option.value}`}
              style={{ opacity: isUnavailable ? 0.55 : 1 }}
            >
              <View className="min-w-0 flex-1">
                <View className="flex-row items-center gap-2">
                  <Text className="text-lg font-semibold text-foreground">{option.title}</Text>
                  {isSelected ? (
                    <View
                      className="rounded-full px-2 py-0.5"
                      style={{ backgroundColor: `${COLORS.BITCOIN_ORANGE}1F` }}
                    >
                      <Text
                        className="text-[10px] font-bold uppercase tracking-[1.4px]"
                        style={{ color: COLORS.BITCOIN_ORANGE }}
                      >
                        Selected
                      </Text>
                    </View>
                  ) : null}
                </View>
                <Text className="mt-1 text-sm leading-5 text-muted-foreground">
                  {option.subtitle}
                </Text>
                {option.detail ? (
                  <Text className="mt-2 text-sm font-semibold text-foreground">
                    {option.detail}
                  </Text>
                ) : null}
                {option.unavailableReason ? (
                  <Text className="mt-2 text-sm text-destructive">{option.unavailableReason}</Text>
                ) : null}
              </View>
              <Icon
                name={isSelected ? "checkmark-circle" : "ellipse-outline"}
                size={26}
                color={isSelected ? COLORS.BITCOIN_ORANGE : colors.mutedForeground}
              />
            </Pressable>
          );
        })}
      </View>

      <View style={{ paddingBottom: Math.max(bottomTabBarHeight, 20) + 8 }}>
        <NativeNoahButton
          label="Continue"
          onPress={onContinue}
          disabled={!canContinue}
          size="lg"
          fullWidth
          testID={`${testIDPrefix}-next`}
        />
      </View>
    </View>
  );
}
