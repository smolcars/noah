import { useGT, useLocaleSelector } from "gt-react-native";
import { View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Text } from "~/components/ui/text";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSelectionList } from "~/components/ui/NativeNoahSelectionList";
import type { SettingsStackParamList } from "~/Navigators";

type LanguageNavigationProp = NativeStackNavigationProp<SettingsStackParamList, "Language">;

const LanguageSettingsScreen = () => {
  const gt = useGT();
  const navigation = useNavigation<LanguageNavigationProp>();
  const { locale, locales, setLocale, getLocaleProperties } = useLocaleSelector();
  const options = locales.map((code) => {
    const name = getLocaleProperties(code).nativeName;
    return {
      value: code,
      title: name.charAt(0).toLocaleUpperCase(code) + name.slice(1),
      subtitle: code.toUpperCase(),
    };
  });

  const handleSelectLanguage = (code: string) => {
    setLocale(code);
    navigation.goBack();
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <View className="flex-row items-center px-5 pt-4">
        <NativeNoahBackButton
          onPress={() => navigation.goBack()}
          className="mr-3"
          testID="language-settings-back-button"
        />
        <Text className="text-2xl font-bold text-foreground">{gt("Language")}</Text>
      </View>
      <View className="mt-4 flex-1">
        <NativeNoahSelectionList
          value={locale}
          options={options}
          onValueChange={handleSelectLanguage}
          testID="language-option"
        />
      </View>
    </NoahSafeAreaView>
  );
};

export default LanguageSettingsScreen;
