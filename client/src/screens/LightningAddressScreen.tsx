import React, { useState } from "react";
import { View, Pressable } from "react-native";
import { useNavigation, useRoute } from "@react-navigation/native";
import type { RouteProp } from "@react-navigation/native";
import { Input } from "../components/ui/input";
import { Button } from "../components/ui/button";
import { NoahButton } from "../components/ui/NoahButton";
import { Text } from "../components/ui/text";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { useUpdateLightningAddress } from "../hooks/useUpdateLightningAddress";
import { getLnurlDomain } from "../constants";
import { useServerStore } from "../store/serverStore";
import { useWalletStore } from "../store/walletStore";
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { CheckCircle } from "lucide-react-native";
import type { OnboardingStackParamList, SettingsStackParamList } from "../Navigators";

type LightningAddressScreenRouteProp = RouteProp<
  OnboardingStackParamList & SettingsStackParamList,
  "LightningAddress"
>;

const LightningAddressScreen = () => {
  const navigation = useNavigation();
  const iconColor = useIconColor();
  const route = useRoute<LightningAddressScreenRouteProp>();
  const { fromOnboarding } = route.params || {};
  const { finishOnboarding } = useWalletStore();
  const { lightningAddress } = useServerStore();
  const domain = getLnurlDomain();
  const currentUsername = lightningAddress ? lightningAddress.split("@")[0] : "";
  const [username, setUsername] = useState(currentUsername);
  const normalizedUsername = username.trim().toLowerCase();
  const [showUpdateSuccess, setShowUpdateSuccess] = useState(false);

  const updateLightningAddressMutation = useUpdateLightningAddress({
    onSuccess: () => {
      setShowUpdateSuccess(true);
      setTimeout(() => {
        setShowUpdateSuccess(false);
        if (fromOnboarding) {
          finishOnboarding();
        } else {
          navigation.goBack();
        }
      }, 2000);
    },
  });

  const handleSave = async () => {
    if (normalizedUsername) {
      const newAddress = `${normalizedUsername}@${domain}`;
      if (newAddress !== lightningAddress) {
        updateLightningAddressMutation.mutate(newAddress);
      } else if (fromOnboarding) {
        finishOnboarding();
      }
    }
  };

  const handleSkip = () => {
    if (fromOnboarding) {
      // User already registered with server-generated lightning address during email verification
      finishOnboarding();
    }
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="p-4">
        <View className="flex-row items-center mb-8">
          {!fromOnboarding && (
            <Pressable onPress={() => navigation.goBack()} className="mr-4">
              <Icon name="arrow-back-outline" size={24} color={iconColor} />
            </Pressable>
          )}
          <Text className="text-2xl font-bold text-foreground">
            {fromOnboarding ? "Choose your Lightning Address" : "Lightning Address"}
          </Text>
        </View>
        {showUpdateSuccess && (
          <Alert icon={CheckCircle} className="mb-4">
            <AlertTitle>Success!</AlertTitle>
            <AlertDescription>Lightning address has been updated.</AlertDescription>
          </Alert>
        )}
        <View className="mt-10">
          <Text className="text-muted-foreground mb-3">
            Pick a username or let us assign one for you.
          </Text>
          <View className="bg-card rounded-2xl border border-border p-5 space-y-5">
            <View>
              <Text className="text-xs uppercase tracking-widest text-muted-foreground mb-2">
                Username
              </Text>
              <Input
                value={username}
                onChangeText={(value) => setUsername(value.trim().toLowerCase())}
                className="h-16 rounded-2xl border border-border bg-background/90 px-4 text-lg leading-6 text-foreground"
                placeholder="fiatjaf"
                keyboardType="email-address"
                autoCapitalize="none"
                autoCorrect={false}
              />
            </View>
            <View className="bg-background/70 rounded-xl p-3 border border-border/60">
              <Text className="text-xs text-muted-foreground">Your lightning address will be</Text>
              <Text className="text-md font-semibold text-foreground mt-1">
                {normalizedUsername}@{domain}
              </Text>
            </View>
          </View>
        </View>
        {fromOnboarding ? (
          <View className="flex-row items-center mt-8 gap-4">
            <View className="flex-1">
              <Button
                onPress={handleSkip}
                variant="outline"
                disabled={updateLightningAddressMutation.isPending}
              >
                <Text>Skip</Text>
              </Button>
            </View>
            <View className="flex-1">
              <NoahButton
                onPress={handleSave}
                isLoading={updateLightningAddressMutation.isPending}
                disabled={!normalizedUsername}
              >
                {`${normalizedUsername}@${domain}` === lightningAddress ? "Continue" : "Save"}
              </NoahButton>
            </View>
          </View>
        ) : (
          <NoahButton
            onPress={handleSave}
            className="mt-8"
            isLoading={updateLightningAddressMutation.isPending}
            disabled={!normalizedUsername}
          >
            Save
          </NoahButton>
        )}
      </View>
    </NoahSafeAreaView>
  );
};

export default LightningAddressScreen;
