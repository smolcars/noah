import Icon from "@react-native-vector-icons/ionicons";
import * as Haptics from "expo-haptics";
import { Pressable, View } from "react-native";

import { Text } from "~/components/ui/text";
import { useThemeColors } from "~/hooks/useTheme";

type AmountCurrency = "FIAT" | "SATS";
type KeypadKey = "backspace" | "." | `${number}`;

type AmountKeypadProps = {
  amount: string;
  currency: AmountCurrency;
  fiatDecimals: number;
  onChange: (amount: string) => void;
  disabled?: boolean;
  testIDPrefix: string;
};

const KEYPAD_ROWS: KeypadKey[][] = [
  ["1", "2", "3"],
  ["4", "5", "6"],
  ["7", "8", "9"],
  [".", "0", "backspace"],
];

const MAX_AMOUNT_LENGTH = 12;

export function AmountKeypad({
  amount,
  currency,
  fiatDecimals,
  onChange,
  disabled = false,
  testIDPrefix,
}: AmountKeypadProps) {
  const colors = useThemeColors();

  const confirmKeyPress = (nextAmount: string) => {
    void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    onChange(nextAmount);
  };

  const enterKey = (key: KeypadKey) => {
    if (disabled) {
      return;
    }

    if (key === "backspace") {
      if (amount.length > 0) {
        confirmKeyPress(amount.slice(0, -1));
      }
      return;
    }

    if (key === ".") {
      if (currency !== "FIAT" || fiatDecimals === 0 || amount.includes(".")) {
        return;
      }

      confirmKeyPress(amount.length === 0 ? "0." : `${amount}.`);
      return;
    }

    if (amount.length >= MAX_AMOUNT_LENGTH) {
      return;
    }

    const decimalPlaces = amount.split(".")[1]?.length ?? 0;
    if (currency === "FIAT" && amount.includes(".") && decimalPlaces >= fiatDecimals) {
      return;
    }

    confirmKeyPress(amount === "0" ? key : `${amount}${key}`);
  };

  return (
    <View accessibilityLabel="Amount keypad">
      {KEYPAD_ROWS.map((row, rowIndex) => (
        <View key={rowIndex} className="flex-row">
          {row.map((key) => {
            const isDecimalDisabled = key === "." && (currency !== "FIAT" || fiatDecimals === 0);

            return (
              <Pressable
                key={key}
                accessibilityRole="button"
                accessibilityLabel={key === "backspace" ? "Delete digit" : key}
                accessibilityState={{ disabled: isDecimalDisabled || disabled }}
                disabled={isDecimalDisabled || disabled}
                onPress={() => enterKey(key)}
                className="h-[66px] flex-1 items-center justify-center"
                testID={`${testIDPrefix}-${key}`}
              >
                {key === "backspace" ? (
                  <Icon name="backspace-outline" size={27} color={colors.foreground} />
                ) : isDecimalDisabled ? null : (
                  <Text className="text-3xl font-medium text-foreground">{key}</Text>
                )}
              </Pressable>
            );
          })}
        </View>
      ))}
    </View>
  );
}
