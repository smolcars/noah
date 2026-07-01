import React, { useEffect, useState } from "react";
import { View, Pressable } from "react-native";
import { useNavigation, useRoute } from "@react-navigation/native";
import type { RouteProp } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { copyToClipboard } from "../lib/clipboardUtils";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import { useAlert } from "~/contexts/AlertProvider";
import { useBiometrics } from "../hooks/useBiometrics";

import type { OnboardingStackParamList, SettingsStackParamList } from "../Navigators";
import { Card, CardContent } from "../components/ui/card";
import { getMnemonic } from "~/lib/crypto";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";

type MnemonicScreenRouteProp = RouteProp<
  OnboardingStackParamList & SettingsStackParamList,
  "Mnemonic"
>;

const MnemonicScreen = () => {
  const navigation =
    useNavigation<NativeStackNavigationProp<OnboardingStackParamList & SettingsStackParamList>>();
  const route = useRoute<MnemonicScreenRouteProp>();
  const iconColor = useIconColor();
  const { fromOnboarding } = route.params || {};

  const [mnemonic, setMnemonic] = useState("");
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const { showAlert } = useAlert();
  const { authenticateIfEnabled } = useBiometrics();

  useEffect(() => {
    const authenticate = async () => {
      if (!fromOnboarding) {
        const result = await authenticateIfEnabled("Authenticate to view your seed phrase");
        if (result.isErr()) {
          showAlert({
            title: "Authentication Failed",
            description: "You must authenticate to view your seed phrase.",
          });
          navigation.goBack();
          return;
        }
      }

      setIsAuthenticated(true);
    };
    authenticate();
  }, [showAlert, navigation, fromOnboarding, authenticateIfEnabled]);

  useEffect(() => {
    if (!isAuthenticated) return;

    const fetchMnemonic = async () => {
      const mnemonicResult = await getMnemonic();
      if (mnemonicResult.isOk()) {
        setMnemonic(mnemonicResult.value);
      } else {
        showAlert({
          title: "Error",
          description: "Could not retrieve your recovery phrase. Please try again from settings.",
        });
        navigation.goBack();
      }
    };
    fetchMnemonic();
  }, [isAuthenticated, showAlert, navigation]);

  const handleCopy = async () => {
    await copyToClipboard(mnemonic, {
      onCopy: () => {
        showAlert({ title: "Copied!", description: "Seed phrase copied to clipboard." });
      },
    });
  };

  const handleContinue = () => {
    if (fromOnboarding) {
      navigation.navigate("EmailVerification");
    } else {
      navigation.goBack();
    }
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background p-4">
      <View className="p-4">
        <View className="flex-row items-center mb-8">
          {!fromOnboarding && (
            <Pressable onPress={() => navigation.goBack()} className="mr-4">
              <Icon name="arrow-back-outline" size={24} color={iconColor} />
            </Pressable>
          )}
          <Text className="text-2xl font-bold text-foreground">Your Recovery Phrase</Text>
        </View>

        <Text className="text-lg text-muted-foreground mb-6">
          Write down these 12 words in order and store them in a safe place. This is the only way to
          recover your wallet.
        </Text>

        {!isAuthenticated ? (
          <View className="flex-1 justify-center items-center">
            <NoahActivityIndicator size="large" />
            <Text className="text-muted-foreground mt-4">Authenticating...</Text>
          </View>
        ) : mnemonic ? (
          <Card>
            <CardContent className="p-4">
              <Text className="text-xl text-center text-foreground tracking-widest leading-loose">
                {mnemonic}
              </Text>
            </CardContent>
          </Card>
        ) : (
          <View className="flex-1 justify-center items-center">
            <NoahActivityIndicator size="large" />
          </View>
        )}

        {mnemonic && (
          <View className="mt-6">
            <NativeNoahSecondaryButton label="Copy Seed Phrase" onPress={handleCopy} fullWidth />
          </View>
        )}

        <NativeNoahButton
          label={fromOnboarding ? "I Have Saved It, Continue" : "Done"}
          onPress={handleContinue}
          disabled={!mnemonic}
          className="mt-4"
          fullWidth
        />
      </View>
    </NoahSafeAreaView>
  );
};

export default MnemonicScreen;
