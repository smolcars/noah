import React from "react";
import { Pressable, ScrollView, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Text } from "~/components/ui/text";
import type { SettingsStackParamList } from "~/Navigators";
import {
  BITCOIN_AMOUNT_UNITS,
  getBitcoinAmountUnitInfo,
  type BitcoinAmountUnit,
} from "~/lib/bitcoinAmount";
import { COLORS } from "~/lib/styleConstants";
import { useIconColor, useThemeColors } from "~/hooks/useTheme";
import { useProfileStore } from "~/store/profileStore";

type BitcoinUnitNavigationProp = NativeStackNavigationProp<SettingsStackParamList, "BitcoinUnit">;

const BitcoinUnitSettingsScreen = () => {
  const navigation = useNavigation<BitcoinUnitNavigationProp>();
  const iconColor = useIconColor();
  const colors = useThemeColors();
  const bitcoinAmountUnit = useProfileStore((state) => state.bitcoinAmountUnit);
  const setBitcoinAmountUnit = useProfileStore((state) => state.setBitcoinAmountUnit);

  const handleSelectUnit = (unit: BitcoinAmountUnit) => {
    setBitcoinAmountUnit(unit);
    navigation.goBack();
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <ScrollView
        className="flex-1"
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingBottom: 32 }}
      >
        <View className="px-5 pb-8 pt-4">
          <View className="flex-row items-center">
            <Pressable onPress={() => navigation.goBack()} className="mr-4">
              <Icon name="arrow-back-outline" size={24} color={iconColor} />
            </Pressable>
            <Text className="text-2xl font-bold text-foreground">Bitcoin Unit</Text>
          </View>

          <View
            className="mt-8 overflow-hidden rounded-[18px] border"
            style={{
              borderColor: `${colors.mutedForeground}24`,
              backgroundColor: `${colors.card}CC`,
            }}
          >
            {BITCOIN_AMOUNT_UNITS.map((unit, index) => {
              const info = getBitcoinAmountUnitInfo(unit);
              const isSelected = unit === bitcoinAmountUnit;

              return (
                <Pressable
                  key={unit}
                  onPress={() => handleSelectUnit(unit)}
                  className={`flex-row items-center justify-between px-4 py-4 ${
                    index < BITCOIN_AMOUNT_UNITS.length - 1 ? "border-b border-border" : ""
                  }`}
                >
                  <View className="min-w-0 flex-1">
                    <Text className="text-base font-semibold text-foreground">
                      {info.title} · {info.value}
                    </Text>
                    <Text className="mt-1 text-sm text-muted-foreground">{info.description}</Text>
                  </View>
                  {isSelected ? (
                    <Icon name="checkmark-circle" size={24} color={COLORS.BITCOIN_ORANGE} />
                  ) : (
                    <Icon name="ellipse-outline" size={24} color={iconColor} />
                  )}
                </Pressable>
              );
            })}
          </View>
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default BitcoinUnitSettingsScreen;
