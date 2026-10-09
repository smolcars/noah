import { T, useGT } from "gt-react-native";
import React, { useState } from "react";
import {
  View,
  TextInput,
  Keyboard,
  TouchableWithoutFeedback,
  KeyboardAvoidingView,
  ScrollView,
} from "react-native";
import { type NativeStackScreenProps } from "@react-navigation/native-stack";
import { OnboardingStackParamList } from "../Navigators";
import { useRestoreWallet } from "~/hooks/useWallet";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Text } from "~/components/ui/text";
import { useWalletStore } from "~/store/walletStore";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";

type Props = NativeStackScreenProps<OnboardingStackParamList, "RestoreWallet">;

const RestoreWalletScreen = ({ navigation }: Props) => {
  const gt = useGT();
  const restoreStepLabels: Record<string, string> = {
    "Starting restore...": gt("Starting restore..."),
    "Fetching and validating backup...": gt("Fetching and validating backup..."),
    "Loading wallet...": gt("Loading wallet..."),
    "Finalizing...": gt("Finalizing..."),
    Complete: gt("Complete"),
  };
  const [mnemonic, setMnemonic] = useState("");
  const { mutate: restoreWallet, isPending } = useRestoreWallet();
  const restoreProgress = useWalletStore((state) => state.restoreProgress);

  const handleRestore = async () => {
    if (mnemonic) {
      const trimmedMnemonic = mnemonic.trim();
      restoreWallet({ mnemonic: trimmedMnemonic });
    }
  };

  const dismissKeyboard = () => {
    Keyboard.dismiss();
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <KeyboardAvoidingView className="flex-1">
        <TouchableWithoutFeedback onPress={dismissKeyboard}>
          <ScrollView
            contentContainerClassName="p-4 grow"
            keyboardShouldPersistTaps="handled"
            automaticallyAdjustKeyboardInsets
          >
            <View className="flex-row items-center mb-4">
              <NativeNoahBackButton
                onPress={() => navigation.goBack()}
                className="mr-3"
                testID="restore-wallet-back-button"
              />
              <T>
                <Text className="text-2xl font-bold text-foreground">Restore Wallet</Text>
              </T>
            </View>
            <View className="pt-8 items-center w-full">
              <T>
                <Text className="text-lg text-muted-foreground mb-10 text-center">
                  Enter your 12-word seed phrase to restore your wallet.
                </Text>
              </T>
              <TextInput
                className="w-full h-24 bg-input rounded-lg p-4 text-foreground text-lg text-left"
                placeholder={gt("Enter your seed phrase")}
                placeholderTextColor="#666"
                value={mnemonic}
                onChangeText={setMnemonic}
                autoCapitalize="none"
                autoCorrect={false}
                multiline
                returnKeyType="done"
                onSubmitEditing={dismissKeyboard}
                readOnly={isPending}
              />
              <View className="h-5" />

              {restoreProgress && (
                <View className="w-full mb-4">
                  <View className="flex-row items-center justify-between mb-2">
                    <Text className="text-sm text-muted-foreground">
                      {restoreStepLabels[restoreProgress.step] ?? restoreProgress.step}
                    </Text>
                    <Text className="text-sm text-muted-foreground">
                      {restoreProgress.progress}%
                    </Text>
                  </View>
                  <View className="w-full h-2 bg-input rounded-full overflow-hidden">
                    <View
                      className="h-full bg-primary"
                      style={{ width: `${restoreProgress.progress}%` }}
                    />
                  </View>
                </View>
              )}

              <NativeNoahButton
                label={gt("Restore")}
                onPress={handleRestore}
                disabled={isPending}
                isLoading={isPending}
                loadingLabel={gt("Restoring...")}
                width={168}
              />
            </View>
          </ScrollView>
        </TouchableWithoutFeedback>
      </KeyboardAvoidingView>
    </NoahSafeAreaView>
  );
};

export default RestoreWalletScreen;
