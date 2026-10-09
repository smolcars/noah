import React from "react";
import { ScrollView, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { T, useGT } from "gt-react-native";
import type { OnboardingStackParamList } from "../Navigators";
import { Text } from "../components/ui/text";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { LanguagePicker } from "~/components/LanguagePicker";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";

const OnboardingScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<OnboardingStackParamList>>();
  const gt = useGT();

  const handleCreateWallet = () => {
    navigation.navigate("BetaWarning");
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <View className="items-end px-5 pt-4">
        <View className="w-40">
          <LanguagePicker testID="onboarding-language" />
        </View>
      </View>
      <ScrollView contentContainerClassName="grow items-center justify-center p-5">
        <T context="Welcome heading for Noah, a Bitcoin wallet. Keep the product name Noah unchanged.">
          <Text className="text-3xl font-bold mb-4 text-center">Welcome to Noah</Text>
        </T>
        <T context="Choose whether to create a new Bitcoin wallet or restore one you already have.">
          <Text className="text-lg text-muted-foreground mb-10 text-center">
            Create a new wallet or restore an existing one.
          </Text>
        </T>
        <View>
          <View className="flex-row flex-wrap justify-center gap-5">
            <NativeNoahButton
              label={gt("Create Wallet", { $context: "Button to create a new Bitcoin wallet." })}
              onPress={handleCreateWallet}
              size="lg"
              width={220}
            />
            <NativeNoahButton
              label={gt("Restore Wallet", {
                $context: "Button to restore an existing Bitcoin wallet from its recovery phrase.",
              })}
              onPress={() => navigation.navigate("RestoreWallet")}
              size="lg"
              width={220}
            />
          </View>
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default OnboardingScreen;
