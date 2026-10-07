import React from "react";
import { ScrollView, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { OnboardingStackParamList } from "../Navigators";
import { Text } from "../components/ui/text";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";

const OnboardingScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<OnboardingStackParamList>>();

  const handleCreateWallet = () => {
    navigation.navigate("BetaWarning");
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <ScrollView contentContainerClassName="grow items-center justify-center p-5">
        <Text className="text-3xl font-bold mb-4 text-center">Welcome to Noah</Text>
        <Text className="text-lg text-muted-foreground mb-10 text-center">
          Create a new wallet or restore an existing one.
        </Text>
        <View>
          <View className="flex-row flex-wrap justify-center gap-5">
            <NativeNoahButton
              label="Create Wallet"
              onPress={handleCreateWallet}
              size="lg"
              width={172}
            />
            <NativeNoahButton
              label="Restore Wallet"
              onPress={() => navigation.navigate("RestoreWallet")}
              size="lg"
              width={172}
            />
          </View>
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default OnboardingScreen;
