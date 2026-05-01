import React, { useEffect, useState } from "react";
import {
  Keyboard,
  KeyboardAvoidingView,
  Pressable,
  TouchableWithoutFeedback,
  View,
} from "react-native";
import { type NativeStackScreenProps } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { NoahButton } from "~/components/ui/NoahButton";
import { Input } from "~/components/ui/input";
import { Text } from "~/components/ui/text";
import { useIconColor } from "~/hooks/useTheme";
import { useCreateWallet, useRestoreWallet } from "~/hooks/useWallet";
import type { OnboardingStackParamList } from "~/Navigators";

type Props = NativeStackScreenProps<OnboardingStackParamList, "ArkServerAccessToken">;

const ArkServerAccessTokenScreen = ({ navigation, route }: Props) => {
  const iconColor = useIconColor();
  const [token, setToken] = useState("");
  const {
    mutate: createWallet,
    isPending: isCreating,
    isSuccess: isCreateSuccess,
  } = useCreateWallet();
  const { mutate: restoreWallet, isPending: isRestoring } = useRestoreWallet();
  const isPending = isCreating || isRestoring;
  const hasToken = token.trim().length > 0;

  useEffect(() => {
    if (isCreateSuccess) {
      navigation.navigate("Mnemonic", { fromOnboarding: true });
    }
  }, [isCreateSuccess, navigation]);

  const handleContinue = () => {
    const serverAccessToken = token.trim() || null;
    if (route.params.mode === "create") {
      createWallet({ serverAccessToken });
      return;
    }

    restoreWallet({
      mnemonic: route.params.mnemonic,
      serverAccessToken,
    });
  };

  const dismissKeyboard = () => {
    Keyboard.dismiss();
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <KeyboardAvoidingView className="flex-1">
        <TouchableWithoutFeedback onPress={dismissKeyboard}>
          <View className="flex-1 p-4">
            <View className="mb-4 flex-row items-center">
              <Pressable onPress={() => navigation.goBack()} className="mr-4">
                <Icon name="arrow-back-outline" size={24} color={iconColor} />
              </Pressable>
              <Text className="text-2xl font-bold text-foreground">Server Access</Text>
            </View>

            <View className="w-full items-center pt-8">
              <Text className="mb-4 text-center text-lg text-muted-foreground">
                Enter your Ark server access token if you have one.
              </Text>

              <Input
                value={token}
                onChangeText={setToken}
                placeholder="Access token"
                className="h-12 w-full"
                autoCapitalize="none"
                autoCorrect={false}
                secureTextEntry
                editable={!isPending}
              />

              <View className="h-6" />

              <NoahButton
                onPress={handleContinue}
                disabled={isPending || !hasToken}
                isLoading={isPending}
              >
                Continue
              </NoahButton>

              <Pressable onPress={handleContinue} disabled={isPending} className="mt-6">
                <Text className="text-base font-semibold text-muted-foreground">Skip for now</Text>
              </Pressable>
            </View>
          </View>
        </TouchableWithoutFeedback>
      </KeyboardAvoidingView>
    </NoahSafeAreaView>
  );
};

export default ArkServerAccessTokenScreen;
