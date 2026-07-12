import { View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Text } from "~/components/ui/text";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSelectionList } from "~/components/ui/NativeNoahSelectionList";
import type { SettingsStackParamList } from "~/Navigators";
import {
  getFiatCurrencyInfo,
  SUPPORTED_FIAT_CURRENCIES,
  type FiatCurrencyCode,
} from "~/lib/fiatCurrency";
import { useProfileStore } from "~/store/profileStore";

type CurrencyNavigationProp = NativeStackNavigationProp<SettingsStackParamList, "Currency">;

const CURRENCY_OPTIONS = SUPPORTED_FIAT_CURRENCIES.map((currency) => {
  const info = getFiatCurrencyInfo(currency);
  return {
    value: currency,
    title: `${info.code} · ${info.name}`,
    subtitle: info.symbol,
  };
});

const CurrencySettingsScreen = () => {
  const navigation = useNavigation<CurrencyNavigationProp>();
  const preferredCurrency = useProfileStore((state) => state.preferredCurrency);
  const setPreferredCurrency = useProfileStore((state) => state.setPreferredCurrency);

  const handleSelectCurrency = (currency: FiatCurrencyCode) => {
    setPreferredCurrency(currency);
    navigation.goBack();
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="flex-row items-center px-5 pt-4">
        <NativeNoahBackButton
          onPress={() => navigation.goBack()}
          className="mr-3"
          testID="currency-settings-back-button"
        />
        <Text className="text-2xl font-bold text-foreground">Currency</Text>
      </View>
      <View className="mt-4 flex-1">
        <NativeNoahSelectionList
          value={preferredCurrency}
          options={CURRENCY_OPTIONS}
          onValueChange={handleSelectCurrency}
          testID="currency-option"
        />
      </View>
    </NoahSafeAreaView>
  );
};

export default CurrencySettingsScreen;
