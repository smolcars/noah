import { T, useGT } from "gt-react-native";
import React, { useState } from "react";
import { ScrollView, View } from "react-native";
import { useNavigation, useRoute } from "@react-navigation/native";
import type { RouteProp } from "@react-navigation/native";
import { Input } from "../components/ui/input";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { useUpdateLightningIdentity } from "../hooks/useUpdateLightningIdentity";
import { getLnurlDomain } from "../constants";
import { hexPubkeyToNpub } from "../lib/nostr";
import { useServerStore } from "../store/serverStore";
import { useWalletStore } from "../store/walletStore";
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { CheckCircle } from "lucide-react-native";
import type { OnboardingStackParamList, SettingsStackParamList } from "../Navigators";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";

type LightningAddressScreenRouteProp = RouteProp<
  OnboardingStackParamList & SettingsStackParamList,
  "LightningAddress"
>;

const LightningAddressScreen = () => {
  const gt = useGT();
  const navigation = useNavigation();
  const route = useRoute<LightningAddressScreenRouteProp>();
  const { fromOnboarding } = route.params || {};
  const { finishOnboarding } = useWalletStore();
  const { lightningAddress, nostrPubkey } = useServerStore();
  const domain = getLnurlDomain();
  const currentUsername = lightningAddress ? lightningAddress.split("@")[0] : "";
  const currentNpub = hexPubkeyToNpub(nostrPubkey);
  const [username, setUsername] = useState(currentUsername);
  const [nostrKey, setNostrKey] = useState(currentNpub);
  const normalizedUsername = username.trim().toLowerCase();
  const normalizedNostrKey = nostrKey.trim();
  const [showUpdateSuccess, setShowUpdateSuccess] = useState(false);

  const updateIdentityMutation = useUpdateLightningIdentity({
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

  const handleSave = () => {
    if (!normalizedUsername) {
      return;
    }

    const newAddress = `${normalizedUsername}@${domain}`;
    if (newAddress === lightningAddress && normalizedNostrKey === currentNpub) {
      if (fromOnboarding) {
        finishOnboarding();
      } else {
        navigation.goBack();
      }
      return;
    }

    updateIdentityMutation.mutate({
      username: normalizedUsername,
      nostrPubkey: normalizedNostrKey || null,
    });
  };

  const handleSkip = () => {
    if (fromOnboarding) {
      // Registration already assigned a Lightning address; NIP-05 remains disabled.
      finishOnboarding();
    }
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <ScrollView
        className="flex-1"
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ padding: 16, paddingBottom: 40 }}
        automaticallyAdjustKeyboardInsets
        keyboardShouldPersistTaps="handled"
      >
        <View className="mb-8 flex-row items-center">
          {!fromOnboarding && (
            <NativeNoahBackButton
              onPress={() => navigation.goBack()}
              className="mr-3"
              testID="lightning-address-back-button"
            />
          )}
          <Text className="text-2xl font-bold text-foreground">
            {fromOnboarding ? gt("Choose your Lightning Address") : gt("Lightning & NIP-05")}
          </Text>
        </View>
        {showUpdateSuccess && (
          <Alert icon={CheckCircle} className="mb-4">
            <T>
              <AlertTitle>Success!</AlertTitle>
            </T>
            <AlertDescription>
              {normalizedNostrKey
                ? gt("Your Lightning address and NIP-05 identity have been updated.")
                : gt("Your Lightning address has been updated without NIP-05.")}
            </AlertDescription>
          </Alert>
        )}
        <View className="mt-6">
          <T>
            <Text className="mb-3 text-muted-foreground">
              Choose a Lightning username. You can optionally link a Nostr public key so the same
              address also works as a NIP-05 identifier.
            </Text>
          </T>
          <View className="space-y-5 rounded-2xl border border-border bg-card p-5">
            <View>
              <T>
                <Text className="mb-2 text-xs uppercase tracking-widest text-muted-foreground">
                  Username
                </Text>
              </T>
              <Input
                value={username}
                onChangeText={(value) => setUsername(value.trim().toLowerCase())}
                className="h-16 rounded-2xl border border-border bg-background/90 px-4 text-lg leading-6 text-foreground"
                placeholder={gt("fiatjaf")}
                keyboardType="email-address"
                autoCapitalize="none"
                autoCorrect={false}
              />
            </View>
            <View className="rounded-xl border border-border/60 bg-background/70 p-3">
              <Text className="text-xs text-muted-foreground">
                {normalizedNostrKey
                  ? gt("Your Lightning address and NIP-05 identifier will be")
                  : gt("Your Lightning address will be")}
              </Text>
              <Text className="mt-1 text-md font-semibold text-foreground">
                {normalizedUsername}@{domain}
              </Text>
            </View>
            <View>
              <T>
                <Text className="mb-2 text-xs uppercase tracking-widest text-muted-foreground">
                  Nostr public key (optional)
                </Text>
              </T>
              <Input
                value={nostrKey}
                onChangeText={setNostrKey}
                className="min-h-16 rounded-2xl border border-border bg-background/90 px-4 py-4 font-mono text-base leading-5 text-foreground"
                placeholder={gt("npub1...")}
                autoCapitalize="none"
                autoCorrect={false}
                multiline={false}
                scrollEnabled
              />
              <T>
                <Text className="mt-2 text-sm text-muted-foreground">
                  Leave this blank to use Lightning without NIP-05. Only npub public keys are
                  accepted; never paste an nsec private key.
                </Text>
              </T>
            </View>
          </View>
        </View>
        {fromOnboarding ? (
          <View className="mt-8 flex-row items-center gap-4">
            <View className="flex-1">
              <NativeNoahSecondaryButton
                label={gt("Skip")}
                onPress={handleSkip}
                disabled={updateIdentityMutation.isPending}
                fullWidth
              />
            </View>
            <View className="flex-1">
              <NativeNoahButton
                label={
                  `${normalizedUsername}@${domain}` === lightningAddress &&
                  normalizedNostrKey === currentNpub
                    ? gt("Continue")
                    : gt("Save")
                }
                onPress={handleSave}
                isLoading={updateIdentityMutation.isPending}
                loadingLabel={gt("Saving...")}
                disabled={!normalizedUsername}
                fullWidth
              />
            </View>
          </View>
        ) : (
          <NativeNoahButton
            label={gt("Save")}
            onPress={handleSave}
            className="mt-8"
            isLoading={updateIdentityMutation.isPending}
            loadingLabel={gt("Saving...")}
            disabled={!normalizedUsername}
            fullWidth
          />
        )}
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default LightningAddressScreen;
