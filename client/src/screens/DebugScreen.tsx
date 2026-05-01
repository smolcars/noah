import React, { useCallback, useEffect, useState } from "react";
import { View, ScrollView, Pressable } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { SettingsStackParamList } from "../Navigators";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Text } from "~/components/ui/text";
import { Label } from "~/components/ui/label";
import { Input } from "~/components/ui/input";
import {
  getArkInfo,
  refreshServer,
  maintanance,
  maintenanceRefresh,
  maintenanceDelegated,
  maintenanceWithOnchainDelegated,
  clearArkServerAccessToken,
  closeWalletIfLoaded,
  isArkServerAccessTokenEnabled,
  loadWalletIfNeeded,
  saveArkServerAccessToken,
} from "~/lib/walletApi";
import { offboardAllArk } from "~/lib/paymentsApi";
import { registerForPushNotificationsAsync } from "~/lib/pushNotifications";
import { useAlert } from "~/contexts/AlertProvider";
import logger from "~/lib/log";
import { NoahButton } from "~/components/ui/NoahButton";
import { copyToClipboard } from "~/lib/clipboardUtils";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  type Option,
} from "~/components/ui/select";
import { getArkServerAccessToken } from "~/lib/crypto";

const log = logger("DebugScreen");

type DebugAction =
  | "getArkInfo"
  | "getPushToken"
  | "refreshServer"
  | "maintenance"
  | "maintenanceRefresh"
  | "maintenanceDelegated"
  | "maintenanceWithOnchainDelegated"
  | "offboardAll";

interface ActionOption {
  id: DebugAction;
  title: string;
  description: string;
  requiresInput?: boolean;
  inputPlaceholder?: string;
}

const DEBUG_ACTIONS: ActionOption[] = [
  {
    id: "getArkInfo",
    title: "Get Ark Info",
    description: "Fetch the current Ark server info as JSON",
  },
  {
    id: "getPushToken",
    title: "Get Push Token",
    description: "Fetch the current Expo push token or UnifiedPush endpoint",
  },
  {
    id: "refreshServer",
    title: "Refresh Server",
    description: "Refresh server registration and sync the latest server state",
  },
  {
    id: "maintenance",
    title: "Maintenance",
    description: "Run maintenance to refresh expiring VTXOs",
  },
  {
    id: "maintenanceRefresh",
    title: "Maintenance Refresh",
    description: "Run maintenance refresh operation",
  },
  {
    id: "maintenanceDelegated",
    title: "Maintenance Delegated",
    description: "Run delegated maintenance operation",
  },
  {
    id: "maintenanceWithOnchainDelegated",
    title: "Maintenance With Onchain Delegated",
    description: "Run delegated maintenance with onchain operation",
  },
  {
    id: "offboardAll",
    title: "Offboard All",
    description: "Offboard all funds to an on-chain address",
    requiresInput: true,
    inputPlaceholder: "Enter Bitcoin address",
  },
];

const DebugScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const iconColor = useIconColor();
  const { showAlert } = useAlert();
  const [selectedOption, setSelectedOption] = useState<Option | undefined>(undefined);
  const [inputValue, setInputValue] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [resultState, setResultState] = useState<{
    kind: "success" | "error";
    message: string;
  } | null>(null);
  const [copied, setCopied] = useState(false);
  const [arkServerAccessToken, setArkServerAccessTokenInput] = useState("");
  const [hasArkServerAccessToken, setHasArkServerAccessToken] = useState(false);
  const [isSavingArkServerAccessToken, setIsSavingArkServerAccessToken] = useState(false);

  const selectedAction = selectedOption?.value as DebugAction | undefined;
  const selectedActionConfig = DEBUG_ACTIONS.find((a) => a.id === selectedAction);

  const refreshArkServerAccessTokenStatus = useCallback(async () => {
    if (!isArkServerAccessTokenEnabled) {
      return;
    }

    const result = await getArkServerAccessToken();
    if (result.isErr()) {
      log.w("Failed to read Ark server access token status", [result.error]);
      return;
    }

    setHasArkServerAccessToken(Boolean(result.value));
  }, []);

  useEffect(() => {
    refreshArkServerAccessTokenStatus();
  }, [refreshArkServerAccessTokenStatus]);

  type ActionResult = { success: true; message: string } | { success: false; error: string };

  const executeAction = async (action: DebugAction, input: string): Promise<ActionResult> => {
    switch (action) {
      case "getArkInfo": {
        log.d("Fetching Ark info");
        const result = await getArkInfo();
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return { success: true, message: JSON.stringify(result.value, null, 2) };
      }
      case "getPushToken": {
        log.d("Fetching push token");
        const result = await registerForPushNotificationsAsync();
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }

        const payload = result.value;
        switch (payload.kind) {
          case "success":
            return {
              success: true,
              message: `Push type: ${payload.pushType}\n\n${payload.pushToken}`,
            };
          case "permission_denied":
            return {
              success: false,
              error: `Push permission not granted (${payload.permissionStatus})`,
            };
          case "device_not_supported":
            return {
              success: false,
              error: "Push tokens are only available on a physical device",
            };
        }

        const exhaustiveCheck: never = payload;
        throw new Error(`Unsupported push registration result: ${String(exhaustiveCheck)}`);
      }
      case "refreshServer": {
        log.d("Executing refresh server");
        const result = await refreshServer();
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return { success: true, message: "Server refresh completed successfully" };
      }
      case "maintenance": {
        log.d("Executing maintenance");
        const result = await maintanance();
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return { success: true, message: "Maintenance completed successfully" };
      }
      case "maintenanceRefresh": {
        log.d("Executing maintenance refresh");
        const result = await maintenanceRefresh();
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return { success: true, message: "Maintenance refresh completed successfully" };
      }
      case "maintenanceDelegated": {
        log.d("Executing maintenance delegated");
        const result = await maintenanceDelegated();
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return { success: true, message: "Maintenance delegated completed successfully" };
      }
      case "maintenanceWithOnchainDelegated": {
        log.d("Executing maintenance with onchain delegated");
        const result = await maintenanceWithOnchainDelegated();
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return {
          success: true,
          message: "Maintenance with onchain delegated completed successfully",
        };
      }
      case "offboardAll": {
        log.d("Executing offboard all to address:", [input]);
        const result = await offboardAllArk(input.trim());
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        const statusStr =
          typeof result.value === "object"
            ? JSON.stringify(result.value, null, 2)
            : String(result.value);
        return { success: true, message: `Offboard completed.\n\nRound status:\n${statusStr}` };
      }
    }
  };

  const handleExecute = async () => {
    if (!selectedAction) {
      showAlert({ title: "Error", description: "Please select an action" });
      return;
    }

    if (selectedActionConfig?.requiresInput && !inputValue.trim()) {
      showAlert({ title: "Error", description: "Please enter the required input" });
      return;
    }

    setIsLoading(true);
    setResultState(null);

    const result = await executeAction(selectedAction, inputValue);

    setIsLoading(false);

    if (result.success) {
      setResultState({ kind: "success", message: result.message });
      if (selectedAction === "offboardAll") {
        setInputValue("");
      }
    } else {
      log.e("Debug action failed:", [result.error]);
      setResultState({ kind: "error", message: result.error });
      showAlert({ title: "Action Failed", description: result.error });
    }
  };

  const handleSelectChange = (option: Option | undefined) => {
    setSelectedOption(option);
    setResultState(null);
    setInputValue("");
    setCopied(false);
  };

  const handleCopyResult = async () => {
    if (resultState?.message) {
      await copyToClipboard(resultState.message, {
        onCopy: () => {
          setCopied(true);
          setTimeout(() => setCopied(false), 2000);
        },
      });
    }
  };

  const reloadWalletWithCurrentConfig = async (): Promise<ActionResult> => {
    const closeResult = await closeWalletIfLoaded();
    if (closeResult.isErr()) {
      return { success: false, error: closeResult.error.message };
    }
    if (!closeResult.value) {
      return { success: false, error: "Failed to close wallet before reload" };
    }

    const loadResult = await loadWalletIfNeeded();
    if (loadResult.isErr()) {
      return { success: false, error: loadResult.error.message };
    }

    return { success: true, message: "Wallet reloaded with current Ark server access token" };
  };

  const handleSaveArkServerAccessToken = async () => {
    setIsSavingArkServerAccessToken(true);
    const normalizedToken = arkServerAccessToken.trim();
    const saveResult = normalizedToken
      ? await saveArkServerAccessToken(normalizedToken)
      : await clearArkServerAccessToken();
    if (saveResult.isErr()) {
      setIsSavingArkServerAccessToken(false);
      showAlert({ title: "Save Failed", description: saveResult.error.message });
      return;
    }

    const reloadResult = await reloadWalletWithCurrentConfig();
    await refreshArkServerAccessTokenStatus();
    setArkServerAccessTokenInput("");
    setIsSavingArkServerAccessToken(false);

    if (!reloadResult.success) {
      showAlert({ title: "Reload Failed", description: reloadResult.error });
      return;
    }

    showAlert({
      title: normalizedToken ? "Token Saved" : "Token Cleared",
      description: normalizedToken
        ? "The wallet is using the updated Ark server access token."
        : "The wallet is using the default Ark server config.",
    });
  };

  const handleClearArkServerAccessToken = async () => {
    setIsSavingArkServerAccessToken(true);
    const clearResult = await clearArkServerAccessToken();
    if (clearResult.isErr()) {
      setIsSavingArkServerAccessToken(false);
      showAlert({ title: "Clear Failed", description: clearResult.error.message });
      return;
    }

    const reloadResult = await reloadWalletWithCurrentConfig();
    await refreshArkServerAccessTokenStatus();
    setArkServerAccessTokenInput("");
    setIsSavingArkServerAccessToken(false);

    if (!reloadResult.success) {
      showAlert({ title: "Reload Failed", description: reloadResult.error });
      return;
    }

    showAlert({
      title: "Token Cleared",
      description: "The wallet is using the default Ark server config.",
    });
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="flex-row items-center px-4 pb-4">
        <Pressable onPress={() => navigation.goBack()} className="mr-4">
          <Icon name="arrow-back" size={24} color={iconColor} />
        </Pressable>
        <Text className="text-2xl font-bold text-foreground">Debug</Text>
      </View>

      <ScrollView className="flex-1 px-4" showsVerticalScrollIndicator={false}>
        {isArkServerAccessTokenEnabled && (
          <View className="mb-8 mt-6 rounded-lg border border-input p-4">
            <Label className="mb-2 text-xl text-foreground">Ark Server Access Token</Label>
            <Text className="mb-4 text-sm text-muted-foreground">
              Status: {hasArkServerAccessToken ? "Token saved" : "No token saved"}
            </Text>
            <Input
              value={arkServerAccessToken}
              onChangeText={setArkServerAccessTokenInput}
              placeholder="Enter Ark server access token"
              className="mb-4 h-12"
              autoCapitalize="none"
              autoCorrect={false}
              secureTextEntry
              editable={!isSavingArkServerAccessToken}
            />
            <View className="flex-row">
              <NoahButton
                onPress={handleSaveArkServerAccessToken}
                disabled={isSavingArkServerAccessToken}
                isLoading={isSavingArkServerAccessToken}
                className="flex-1"
              >
                Save Token
              </NoahButton>
              <View className="w-3" />
              <NoahButton
                onPress={handleClearArkServerAccessToken}
                disabled={isSavingArkServerAccessToken || !hasArkServerAccessToken}
                className="flex-1"
              >
                Clear
              </NoahButton>
            </View>
          </View>
        )}

        <View className="mb-6 mt-6">
          <Label className="text-foreground text-2xl mb-2">Select Action</Label>

          <Select value={selectedOption} onValueChange={handleSelectChange}>
            <SelectTrigger className="w-full">
              <SelectValue
                className="text-foreground text-sm native:text-lg"
                placeholder="Choose an action..."
              />
            </SelectTrigger>
            <SelectContent className="w-full">
              {DEBUG_ACTIONS.map((action) => (
                <SelectItem
                  key={action.id}
                  label={action.title}
                  value={action.id}
                  description={action.description}
                />
              ))}
            </SelectContent>
          </Select>
        </View>

        {selectedActionConfig?.requiresInput && (
          <View className="mb-6">
            <Label className="text-foreground text-lg mb-2">
              {selectedActionConfig.inputPlaceholder}
            </Label>
            <Input
              value={inputValue}
              onChangeText={setInputValue}
              placeholder={selectedActionConfig.inputPlaceholder}
              className="h-12"
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>
        )}

        {resultState && (
          <Pressable
            onLongPress={handleCopyResult}
            className={`mb-6 rounded-lg border p-4 ${
              resultState.kind === "success"
                ? "border-green-300 bg-green-100 dark:border-green-700 dark:bg-green-900/30"
                : "border-red-300 bg-red-100 dark:border-red-700 dark:bg-red-900/30"
            }`}
          >
            <Text
              className={
                resultState.kind === "success"
                  ? "text-green-700 dark:text-green-400"
                  : "text-red-700 dark:text-red-400"
              }
            >
              {resultState.message}
            </Text>
            <Text className="text-muted-foreground text-md mt-2">
              {copied ? "Copied!" : "Long press to copy"}
            </Text>
          </Pressable>
        )}

        <NoahButton
          onPress={handleExecute}
          disabled={!selectedAction || isLoading}
          className="mb-6"
        >
          {isLoading ? "Executing..." : "Execute Action"}
        </NoahButton>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default DebugScreen;
