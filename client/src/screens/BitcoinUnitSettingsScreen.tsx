import { useGT, useLocale } from "gt-react-native";
import { View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { ScreenHeader } from "~/components/ScreenHeader";
import { NativeNoahSelectionList } from "~/components/ui/NativeNoahSelectionList";
import type { SettingsStackParamList } from "~/Navigators";
import {
  BITCOIN_AMOUNT_UNITS,
  formatBitcoinAmount,
  getBitcoinAmountUnitInfo,
  type BitcoinAmountUnit,
} from "~/lib/bitcoinAmount";
import { useProfileStore } from "~/store/profileStore";

type BitcoinUnitNavigationProp = NativeStackNavigationProp<SettingsStackParamList, "BitcoinUnit">;

const BitcoinUnitSettingsScreen = () => {
  const gt = useGT();
  const locale = useLocale();
  const BITCOIN_UNIT_OPTIONS = BITCOIN_AMOUNT_UNITS.map((unit) => {
    const info = getBitcoinAmountUnitInfo(unit);
    return {
      value: unit,
      title: `${info.title} · ${formatBitcoinAmount(1234, unit, locale)}`,
      subtitle:
        unit === "bip177"
          ? gt("Show bitcoin amounts with the Bitcoin symbol.")
          : gt("Show bitcoin amounts in satoshis."),
    };
  });
  const navigation = useNavigation<BitcoinUnitNavigationProp>();
  const bitcoinAmountUnit = useProfileStore((state) => state.bitcoinAmountUnit);
  const setBitcoinAmountUnit = useProfileStore((state) => state.setBitcoinAmountUnit);

  const handleSelectUnit = (unit: BitcoinAmountUnit) => {
    setBitcoinAmountUnit(unit);
    navigation.goBack();
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <ScreenHeader
        title={gt("Bitcoin Unit")}
        onBack={() => navigation.goBack()}
        backButtonTestID="bitcoin-unit-back-button"
        className="px-5"
      />
      <View className="mt-4 flex-1">
        <NativeNoahSelectionList
          value={bitcoinAmountUnit}
          options={BITCOIN_UNIT_OPTIONS}
          onValueChange={handleSelectUnit}
          testID="bitcoin-unit-option"
        />
      </View>
    </NoahSafeAreaView>
  );
};

export default BitcoinUnitSettingsScreen;
