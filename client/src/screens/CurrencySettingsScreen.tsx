import React from "react";
import { Pressable, ScrollView, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Text } from "~/components/ui/text";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import type { SettingsStackParamList } from "~/Navigators";
import {
  getFiatCurrencyInfo,
  SUPPORTED_FIAT_CURRENCIES,
  type FiatCurrencyCode,
} from "~/lib/fiatCurrency";
import { COLORS } from "~/lib/styleConstants";
import { useIconColor, useThemeColors } from "~/hooks/useTheme";
import { useProfileStore } from "~/store/profileStore";

type CurrencyNavigationProp = NativeStackNavigationProp<SettingsStackParamList, "Currency">;

const CurrencySettingsScreen = () => {
  const navigation = useNavigation<CurrencyNavigationProp>();
  const iconColor = useIconColor();
  const colors = useThemeColors();
  const preferredCurrency = useProfileStore((state) => state.preferredCurrency);
  const setPreferredCurrency = useProfileStore((state) => state.setPreferredCurrency);

  const handleSelectCurrency = (currency: FiatCurrencyCode) => {
    setPreferredCurrency(currency);
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
            <NativeNoahBackButton
              onPress={() => navigation.goBack()}
              className="mr-3"
              testID="currency-settings-back-button"
            />
            <Text className="text-2xl font-bold text-foreground">Currency</Text>
          </View>

          <View
            className="mt-8 overflow-hidden rounded-[18px] border"
            style={{
              borderColor: `${colors.mutedForeground}24`,
              backgroundColor: `${colors.card}CC`,
            }}
          >
            {SUPPORTED_FIAT_CURRENCIES.map((currency, index) => {
              const info = getFiatCurrencyInfo(currency);
              const isSelected = currency === preferredCurrency;

              return (
                <Pressable
                  key={currency}
                  onPress={() => handleSelectCurrency(currency)}
                  className={`flex-row items-center justify-between px-4 py-4 ${
                    index < SUPPORTED_FIAT_CURRENCIES.length - 1 ? "border-b border-border" : ""
                  }`}
                >
                  <View className="min-w-0 flex-1">
                    <Text className="text-base font-semibold text-foreground">
                      {info.code} · {info.name}
                    </Text>
                    <Text className="mt-1 text-sm text-muted-foreground">{info.symbol}</Text>
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

export default CurrencySettingsScreen;
