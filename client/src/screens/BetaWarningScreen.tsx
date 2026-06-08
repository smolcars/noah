import React, { useEffect } from "react";
import { View } from "react-native";
import { AlertTriangle } from "lucide-react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { OnboardingStackParamList } from "../Navigators";
import { NoahButton } from "../components/ui/NoahButton";
import { Button } from "../components/ui/button";
import { Text } from "../components/ui/text";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { useCreateWallet } from "../hooks/useWallet";

const BetaWarningScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<OnboardingStackParamList>>();
  const { mutate: createWallet, isPending, isSuccess } = useCreateWallet();

  useEffect(() => {
    if (isSuccess) {
      navigation.navigate("Mnemonic", { fromOnboarding: true });
    }
  }, [isSuccess, navigation]);

  const handleDecline = () => {
    navigation.goBack();
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="flex-1 justify-center px-6 py-10">
        <View className="items-center">
          <View className="h-20 w-20 items-center justify-center rounded-3xl border border-border bg-card">
            <AlertTriangle size={40} color="#f97316" />
          </View>
          <Text className="mt-6 text-center text-3xl font-bold text-foreground">
            Noah is in beta
          </Text>
          <Text className="mt-4 text-center text-lg leading-7 text-muted-foreground">
            Noah is still in beta. There is a possibility you could lose money. Only deposit funds
            you are willing to lose.
          </Text>
        </View>

        <View className="mt-10">
          {isPending ? (
            <View className="items-center">
              <NoahActivityIndicator size="large" />
              <Text className="mt-4 text-muted-foreground">Creating your wallet...</Text>
            </View>
          ) : (
            <View className="space-y-3">
              <NoahButton onPress={() => createWallet()} size="lg">
                I accept
              </NoahButton>
              <Button onPress={handleDecline} variant="outline" size="lg" className="mt-3">
                <Text>I decline</Text>
              </Button>
            </View>
          )}
        </View>
      </View>
    </NoahSafeAreaView>
  );
};

export default BetaWarningScreen;
