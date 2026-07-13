import { useState } from "react";
import { Keyboard, ScrollView, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { AlertTriangle, CheckCircle } from "lucide-react-native";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Alert, AlertDescription, AlertTitle } from "~/components/ui/alert";
import { Input } from "~/components/ui/input";
import { Label } from "~/components/ui/label";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { Text } from "~/components/ui/text";
import { useSwitchEsploraEndpoint } from "~/hooks/useEsplora";
import { getDefaultEsploraEndpoint } from "~/lib/esplora";
import type { SettingsStackParamList } from "~/Navigators";
import { useEsploraStore } from "~/store/esploraStore";

type NavigationProp = NativeStackNavigationProp<SettingsStackParamList, "Esplora">;

const EsploraSettingsScreen = () => {
  const navigation = useNavigation<NavigationProp>();
  const endpointOverride = useEsploraStore((state) => state.endpointOverride);
  const defaultEndpoint = getDefaultEsploraEndpoint() ?? "";
  const effectiveEndpoint = endpointOverride ?? defaultEndpoint;
  const [endpointInput, setEndpointInput] = useState(effectiveEndpoint);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const switchEndpoint = useSwitchEsploraEndpoint();

  const clearStatus = () => {
    setSuccessMessage(null);
    switchEndpoint.reset();
  };

  const handleApply = () => {
    if (switchEndpoint.isPending || !endpointInput.trim()) {
      return;
    }
    Keyboard.dismiss();
    clearStatus();
    switchEndpoint.mutate(endpointInput, {
      onSuccess: ({ endpoint, isDefault }) => {
        setEndpointInput(endpoint);
        setSuccessMessage(
          isDefault
            ? "The wallet is using Noah's default Esplora endpoint."
            : "The wallet is now using the new Esplora endpoint.",
        );
      },
    });
  };

  const handleReset = () => {
    if (switchEndpoint.isPending || !endpointOverride) {
      return;
    }
    Keyboard.dismiss();
    clearStatus();
    switchEndpoint.mutate(null, {
      onSuccess: ({ endpoint }) => {
        setEndpointInput(endpoint);
        setSuccessMessage("The wallet is now using Noah's default Esplora endpoint.");
      },
    });
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="flex-row items-center px-5 pt-4">
        <NativeNoahBackButton
          onPress={() => navigation.goBack()}
          className="mr-3"
          testID="esplora-settings-back-button"
        />
        <Text className="text-2xl font-bold text-foreground">Esplora API</Text>
      </View>

      <ScrollView
        className="flex-1 px-5"
        contentContainerStyle={{ paddingBottom: 32 }}
        keyboardShouldPersistTaps="handled"
      >
        <View className="mt-6 rounded-2xl border border-border bg-card p-4">
          <Text className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
            Active endpoint
          </Text>
          <Text className="mt-2 text-base font-semibold text-foreground">{effectiveEndpoint}</Text>
          <Text className="mt-1 text-sm text-muted-foreground">
            {endpointOverride ? "Custom endpoint" : "Noah default"}
          </Text>
        </View>

        <View className="mt-6 gap-2">
          <Label className="text-base text-foreground">Esplora API base URL</Label>
          <Input
            value={endpointInput}
            onChangeText={(value) => {
              setEndpointInput(value);
              clearStatus();
            }}
            placeholder="https://mempool.space/api"
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            returnKeyType="done"
            editable={!switchEndpoint.isPending}
            className="h-12"
            onSubmitEditing={handleApply}
            testID="esplora-endpoint-input"
          />
          <Text className="text-sm leading-5 text-muted-foreground">
            Noah will test /block-height/0 and verify the Bitcoin network before changing the wallet
            configuration. You may also paste the full test or tip URL.
          </Text>
        </View>

        {successMessage ? (
          <Alert icon={CheckCircle} className="mt-5">
            <AlertTitle>Endpoint updated</AlertTitle>
            <AlertDescription>{successMessage}</AlertDescription>
          </Alert>
        ) : null}

        {switchEndpoint.error ? (
          <Alert icon={AlertTriangle} variant="destructive" className="mt-5">
            <AlertTitle>Endpoint not changed</AlertTitle>
            <AlertDescription>{switchEndpoint.error.message}</AlertDescription>
          </Alert>
        ) : null}

        <View className="mt-6 gap-3">
          <NativeNoahButton
            label="Test & Use Endpoint"
            loadingLabel="Testing Endpoint..."
            isLoading={switchEndpoint.isPending}
            disabled={!endpointInput.trim()}
            onPress={handleApply}
            fullWidth
            testID="apply-esplora-endpoint"
          />
          <NativeNoahSecondaryButton
            label="Reset to Noah Default"
            disabled={!endpointOverride || switchEndpoint.isPending}
            onPress={handleReset}
            fullWidth
            testID="reset-esplora-endpoint"
          />
        </View>

        <View className="mt-7 rounded-2xl border border-border bg-card p-4">
          <Text className="font-semibold text-foreground">Default endpoint</Text>
          <Text className="mt-2 text-sm text-muted-foreground">{defaultEndpoint}</Text>
          <Text className="mt-3 text-sm leading-5 text-muted-foreground">
            A custom Esplora server can observe the Bitcoin addresses and transactions requested by
            this wallet. Only use a server you trust.
          </Text>
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default EsploraSettingsScreen;
