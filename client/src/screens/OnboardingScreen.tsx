import React from "react";
import { View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { OnboardingStackParamList } from "../Navigators";
import { Text } from "../components/ui/text";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";

const OnboardingScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<OnboardingStackParamList>>();

  const handleCreateWallet = () => {
    navigation.navigate("BetaWarning");
  };

  return (
    <View className="flex-1 items-center justify-center bg-background p-5">
      <Text className="text-3xl font-bold mb-4 text-center">Welcome to Noah</Text>
      <Text className="text-lg text-muted-foreground mb-10 text-center">
        Create a new wallet or restore an existing one.
      </Text>
      <View>
        <View className="flex-row justify-center">
          <NativeNoahButton
            label="Create Wallet"
            onPress={handleCreateWallet}
            size="lg"
            width={172}
          />
          <View style={{ width: 20 }} />
          <NativeNoahButton
            label="Restore Wallet"
            onPress={() => navigation.navigate("RestoreWallet")}
            size="lg"
            width={172}
          />
        </View>
      </View>
    </View>
  );
};

export default OnboardingScreen;
