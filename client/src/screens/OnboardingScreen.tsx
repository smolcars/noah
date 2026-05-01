import React, { useEffect } from "react";
import { View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { OnboardingStackParamList } from "../Navigators";
import { NoahButton } from "../components/ui/NoahButton";
import { Text } from "../components/ui/text";
import { useCreateWallet } from "../hooks/useWallet";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import { isArkServerAccessTokenEnabled } from "~/lib/walletApi";

const OnboardingScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<OnboardingStackParamList>>();
  const { mutate: createWallet, isPending, isSuccess } = useCreateWallet();

  useEffect(() => {
    if (isSuccess) {
      navigation.navigate("Mnemonic", { fromOnboarding: true });
    }
  }, [isSuccess, navigation]);

  const handleCreateWallet = () => {
    if (isArkServerAccessTokenEnabled) {
      navigation.navigate("ArkServerAccessToken", { mode: "create" });
      return;
    }

    createWallet(undefined);
  };

  return (
    <View className="flex-1 items-center justify-center bg-background p-5">
      <Text className="text-3xl font-bold mb-4 text-center">Welcome to Noah</Text>
      <Text className="text-lg text-muted-foreground mb-10 text-center">
        Create a new wallet or restore an existing one.
      </Text>
      {isPending ? (
        <View className="items-center">
          <NoahActivityIndicator size="large" />
          <Text className="mt-4 text-muted-foreground">Creating your wallet...</Text>
        </View>
      ) : (
        <View>
          <View className="flex-row justify-center">
            <NoahButton onPress={handleCreateWallet} size="lg">
              Create Wallet
            </NoahButton>
            <View style={{ width: 20 }} />
            <NoahButton onPress={() => navigation.navigate("RestoreWallet")} size="lg">
              Restore Wallet
            </NoahButton>
          </View>
        </View>
      )}
    </View>
  );
};

export default OnboardingScreen;
