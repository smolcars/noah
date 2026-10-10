import { T, useGT } from "gt-react-native";
import React from "react";
import { ScrollView, View } from "react-native";
import { AlertTriangle } from "lucide-react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { OnboardingStackParamList } from "../Navigators";
import { Text } from "../components/ui/text";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { useCreateWallet } from "../hooks/useWallet";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";

const BetaWarningScreen = () => {
  const gt = useGT();
  const navigation = useNavigation<NativeStackNavigationProp<OnboardingStackParamList>>();
  const { mutate: createWallet, isPending } = useCreateWallet();

  const handleDecline = () => {
    navigation.goBack();
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <ScrollView contentContainerClassName="grow justify-center px-6 py-10">
        <View className="items-center">
          <View className="h-20 w-20 items-center justify-center rounded-3xl border border-border bg-card">
            <AlertTriangle size={40} color="#f97316" />
          </View>
          <T>
            <Text className="mt-6 text-center text-3xl font-bold text-foreground">
              Noah is in beta
            </Text>
          </T>
          <T>
            <Text className="mt-4 text-center text-lg leading-7 text-muted-foreground">
              Noah is still in beta. There is a possibility you could lose money. Only deposit funds
              you are willing to lose.
            </Text>
          </T>
        </View>

        <View className="mt-10">
          {isPending ? (
            <View className="items-center">
              <NoahActivityIndicator size="large" />
              <T>
                <Text className="mt-4 text-muted-foreground">Creating your wallet...</Text>
              </T>
            </View>
          ) : (
            <View className="space-y-3">
              <NativeNoahButton
                label={gt("I accept")}
                onPress={() =>
                  createWallet(undefined, {
                    onSuccess: () => navigation.navigate("Mnemonic", { fromOnboarding: true }),
                  })
                }
                size="lg"
                fullWidth
              />
              <View className="mt-3">
                <NativeNoahSecondaryButton
                  label={gt("I decline")}
                  onPress={handleDecline}
                  size="lg"
                  fullWidth
                />
              </View>
            </View>
          )}
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default BetaWarningScreen;
