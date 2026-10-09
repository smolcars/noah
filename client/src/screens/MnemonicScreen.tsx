import { T, useGT } from "gt-react-native";
import React, { useEffect, useState } from "react";
import { ScrollView, View } from "react-native";
import { useNavigation, useRoute } from "@react-navigation/native";
import type { RouteProp } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { copyToClipboard } from "../lib/clipboardUtils";
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
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";

type MnemonicScreenRouteProp = RouteProp<
  OnboardingStackParamList & SettingsStackParamList,
  "Mnemonic"
>;

const MnemonicScreen = () => {
  const gt = useGT();
  const authenticationPrompt = gt("Authenticate to view your seed phrase");
  const authenticationFailedTitle = gt("Authentication Failed");
  const authenticationRequiredMessage = gt("You must authenticate to view your seed phrase.");
  const errorTitle = gt("Error");
  const retrievalFailedMessage = gt(
    "Could not retrieve your recovery phrase. Please try again from settings.",
  );
  const navigation =
    useNavigation<NativeStackNavigationProp<OnboardingStackParamList & SettingsStackParamList>>();
  const route = useRoute<MnemonicScreenRouteProp>();
  const { fromOnboarding } = route.params || {};

  const [mnemonic, setMnemonic] = useState("");
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const { showAlert } = useAlert();
  const { authenticateIfEnabled } = useBiometrics();

  useEffect(() => {
    const authenticate = async () => {
      if (!fromOnboarding) {
        const result = await authenticateIfEnabled(authenticationPrompt);
        if (result.isErr()) {
          showAlert({
            title: authenticationFailedTitle,
            description: authenticationRequiredMessage,
          });
          navigation.goBack();
          return;
        }
      }

      setIsAuthenticated(true);
    };
    authenticate();
  }, [
    showAlert,
    navigation,
    fromOnboarding,
    authenticateIfEnabled,
    authenticationPrompt,
    authenticationFailedTitle,
    authenticationRequiredMessage,
  ]);

  useEffect(() => {
    if (!isAuthenticated) return;

    const fetchMnemonic = async () => {
      const mnemonicResult = await getMnemonic();
      if (mnemonicResult.isOk()) {
        setMnemonic(mnemonicResult.value);
      } else {
        showAlert({
          title: errorTitle,
          description: retrievalFailedMessage,
        });
        navigation.goBack();
      }
    };
    fetchMnemonic();
  }, [isAuthenticated, showAlert, navigation, errorTitle, retrievalFailedMessage]);

  const handleCopy = async () => {
    await copyToClipboard(mnemonic, {
      onCopy: () => {
        showAlert({ title: gt("Copied!"), description: gt("Seed phrase copied to clipboard.") });
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
    <NoahSafeAreaView className="flex-1 bg-background p-4" maxContentWidth={640}>
      <ScrollView contentContainerStyle={{ padding: 16, paddingBottom: 32 }}>
        <View className="flex-row items-center mb-8">
          {!fromOnboarding && (
            <NativeNoahBackButton
              onPress={() => navigation.goBack()}
              className="mr-3"
              testID="mnemonic-back-button"
            />
          )}
          <T>
            <Text className="text-2xl font-bold text-foreground">Your Recovery Phrase</Text>
          </T>
        </View>

        <T>
          <Text className="text-lg text-muted-foreground mb-6">
            Write down these 12 words in order and store them in a safe place. This is the only way
            to recover your wallet.
          </Text>
        </T>

        {!isAuthenticated ? (
          <View className="flex-1 justify-center items-center">
            <NoahActivityIndicator size="large" />
            <T>
              <Text className="text-muted-foreground mt-4">Authenticating...</Text>
            </T>
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
            <NativeNoahSecondaryButton
              label={gt("Copy Seed Phrase")}
              onPress={handleCopy}
              fullWidth
            />
          </View>
        )}

        <NativeNoahButton
          label={fromOnboarding ? gt("I Have Saved It, Continue") : gt("Done")}
          onPress={handleContinue}
          disabled={!mnemonic}
          className="mt-4"
          fullWidth
        />
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default MnemonicScreen;
