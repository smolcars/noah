import React, { useCallback, useEffect, useState } from "react";
import { AppState, View } from "react-native";
import { BatteryCharging, RefreshCcw, Zap } from "lucide-react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { OnboardingStackParamList } from "~/Navigators";
import { Text } from "~/components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { useAlert } from "~/contexts/AlertProvider";
import { useWalletStore } from "~/store/walletStore";
import {
  openBatteryOptimizationSettings,
  shouldPromptForBatteryOptimization,
} from "~/lib/batteryOptimization";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";

const highlights = [
  {
    title: "Push notifications keep arriving",
    description: "Without optimization, notifications can drop off completely, not just be delayed.",
    icon: Zap,
  },
  {
    title: "Background activity keeps working",
    description: "VTXOs keep refreshing in the background so they don't expire while the app is closed.",
    icon: RefreshCcw,
  },
];

type BatteryOptimizationScreenProps = {
  onContinue?: () => void;
};

const BatteryOptimizationScreen = ({ onContinue }: BatteryOptimizationScreenProps) => {
  const navigation = useNavigation<NativeStackNavigationProp<OnboardingStackParamList>>();
  const markBatteryOptimizationPromptShown = useWalletStore(
    (state) => state.markBatteryOptimizationPromptShown,
  );
  const { showAlert } = useAlert();
  const [isPromptApplicable, setIsPromptApplicable] = useState(false);

  const continueFlow = useCallback(() => {
    markBatteryOptimizationPromptShown();
    if (onContinue) {
      onContinue();
      return;
    }
    navigation.replace("LightningAddress", { fromOnboarding: true });
  }, [markBatteryOptimizationPromptShown, onContinue, navigation]);

  useEffect(() => {
    if (shouldPromptForBatteryOptimization()) {
      setIsPromptApplicable(true);
      return;
    }
    continueFlow();
  }, [continueFlow]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active" && !shouldPromptForBatteryOptimization()) {
        continueFlow();
      }
    });
    return () => {
      subscription.remove();
    };
  }, [continueFlow]);

  const handleOpenSettings = () => {
    const opened = openBatteryOptimizationSettings();
    if (!opened) {
      showAlert({
        title: "Couldn't open battery settings",
        description:
          "Please open your device settings and allow Noah to run in the background, then continue.",
      });
    }
  };

  const handleSkip = () => {
    continueFlow();
  };

  if (!isPromptApplicable) {
    return null;
  }

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="flex-1 px-6 py-10">
        <View className="items-center">
          <View className="h-24 w-24 items-center justify-center rounded-3xl bg-card border border-border shadow-lg shadow-black/30">
            <BatteryCharging size={48} color="#f97316" />
          </View>
          <Text className="mt-6 text-3xl font-bold text-center">
            Disable battery optimization
          </Text>
          <Text className="mt-3 text-center text-muted-foreground">
            Android can put Noah to sleep in the background to save energy. This has a negligible
            effect on battery life, but can cause push notifications to stop arriving entirely.
            Disabling battery optimization for Noah keeps the wallet operating correctly.
          </Text>
        </View>

        <View className="mt-10 space-y-4">
          {highlights.map((item) => (
            <View
              key={item.title}
              className="flex-row items-center rounded-2xl border border-border bg-card px-4 py-4 mb-2"
            >
              <View className="mr-4 h-11 w-11 items-center justify-center rounded-xl bg-orange-500/15">
                <item.icon size={22} color="#f97316" />
              </View>
              <View className="flex-1">
                <Text className="text-base font-semibold">{item.title}</Text>
                <Text className="text-sm text-muted-foreground">{item.description}</Text>
              </View>
            </View>
          ))}
        </View>

        <View className="mt-10 space-y-4">
          <NativeNoahButton
            label="Open battery settings"
            onPress={handleOpenSettings}
            size="lg"
            fullWidth
          />
          <View className="space-y-3 mt-3">
            <NativeNoahSecondaryButton
              label="Skip"
              emphasis="ghost"
              onPress={handleSkip}
              fullWidth
            />
          </View>
        </View>
      </View>
    </NoahSafeAreaView>
  );
};

export default BatteryOptimizationScreen;
