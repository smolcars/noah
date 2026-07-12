import { useState } from "react";
import { View, ScrollView, Pressable } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { SettingsStackParamList } from "../Navigators";
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
  decodeVtxoHex,
  importVtxo,
  dropVtxo,
} from "~/lib/walletApi";
import { offboardAllArk } from "~/lib/paymentsApi";
import { registerForPushNotificationsAsync } from "~/lib/pushNotifications";
import { useAlert } from "~/contexts/AlertProvider";
import logger from "~/lib/log";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { copyToClipboard } from "~/lib/clipboardUtils";
import { ConfirmationDialog } from "~/components/ConfirmationDialog";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import {
  NativeNoahPicker,
  type NativeNoahPickerOption,
} from "~/components/ui/NativeNoahPicker";

const log = logger("DebugScreen");

type DebugAction =
  | "getArkInfo"
  | "getPushToken"
  | "refreshServer"
  | "maintenance"
  | "maintenanceRefresh"
  | "maintenanceDelegated"
  | "maintenanceWithOnchainDelegated"
  | "decodeVtxoHex"
  | "importVtxo"
  | "dropVtxo"
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
  {
    id: "decodeVtxoHex",
    title: "Decode VTXO Hex",
    description: "Decode a serialized VTXO hex string without importing it",
    requiresInput: true,
    inputPlaceholder: "Enter VTXO hex",
  },
  {
    id: "importVtxo",
    title: "Import VTXO",
    description: "Import a serialized VTXO hex string into the local wallet",
    requiresInput: true,
    inputPlaceholder: "Enter VTXO hex",
  },
  {
    id: "dropVtxo",
    title: "Drop VTXO",
    description: "Dangerously remove a VTXO from the local wallet database",
    requiresInput: true,
    inputPlaceholder: "Enter VTXO ID",
  },
];

type DebugActionSelection = DebugAction | "none";

const DEBUG_ACTION_OPTIONS: readonly NativeNoahPickerOption<DebugActionSelection>[] = [
  { value: "none", label: "Choose an action..." },
  ...DEBUG_ACTIONS.map((action) => ({ value: action.id, label: action.title })),
];

const DebugScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const { showAlert } = useAlert();
  const [selectedAction, setSelectedAction] = useState<DebugAction | null>(null);
  const [inputValue, setInputValue] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [isDropDialogOpen, setIsDropDialogOpen] = useState(false);
  const [dropConfirmText, setDropConfirmText] = useState("");
  const [resultState, setResultState] = useState<{
    kind: "success" | "error";
    message: string;
  } | null>(null);
  const [copied, setCopied] = useState(false);

  const selectedActionConfig = DEBUG_ACTIONS.find((a) => a.id === selectedAction);

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
      case "decodeVtxoHex": {
        const vtxoHex = input.trim();
        log.d("Decoding VTXO hex", [{ length: vtxoHex.length }]);
        const result = await decodeVtxoHex(vtxoHex);
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return { success: true, message: JSON.stringify(result.value, null, 2) };
      }
      case "importVtxo": {
        const vtxoHex = input.trim();
        log.d("Importing VTXO hex", [{ length: vtxoHex.length }]);
        const result = await importVtxo(vtxoHex);
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return { success: true, message: JSON.stringify(result.value, null, 2) };
      }
      case "dropVtxo": {
        const vtxoId = input.trim();
        log.w("Executing dangerous VTXO drop", [{ vtxoId }]);
        const result = await dropVtxo(vtxoId);
        if (result.isErr()) {
          return { success: false, error: result.error.message };
        }
        return { success: true, message: `Dropped VTXO ${vtxoId}` };
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

    if (selectedAction === "dropVtxo") {
      setDropConfirmText("");
      setIsDropDialogOpen(true);
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

  const handleConfirmedDropVtxo = async () => {
    if (dropConfirmText.toLowerCase() !== "delete") {
      return;
    }

    setIsDropDialogOpen(false);
    setIsLoading(true);
    setResultState(null);

    const result = await executeAction("dropVtxo", inputValue);

    setIsLoading(false);
    setDropConfirmText("");

    if (result.success) {
      setResultState({ kind: "success", message: result.message });
      setInputValue("");
    } else {
      log.e("Debug action failed:", [result.error]);
      setResultState({ kind: "error", message: result.error });
      showAlert({ title: "Action Failed", description: result.error });
    }
  };

  const handleSelectAction = (action: DebugAction | null) => {
    setSelectedAction(action);
    setResultState(null);
    setInputValue("");
    setCopied(false);
    setDropConfirmText("");
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

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="flex-row items-center px-4 pb-4">
        <NativeNoahBackButton
          onPress={() => navigation.goBack()}
          className="mr-3"
          testID="debug-back-button"
        />
        <Text className="text-2xl font-bold text-foreground">Debug</Text>
      </View>

      <ScrollView
        className="flex-1 px-4"
        showsVerticalScrollIndicator
        contentContainerStyle={{ paddingBottom: 24 }}
      >
        <View className="mb-6 mt-6">
          <Label className="text-foreground text-2xl mb-2">Select Action</Label>
          <NativeNoahPicker
            value={selectedAction ?? "none"}
            options={DEBUG_ACTION_OPTIONS}
            onValueChange={(value) => handleSelectAction(value === "none" ? null : value)}
            testID="debug-action-picker"
          />
          {selectedActionConfig ? (
            <Text
              className={`mt-2 text-sm ${
                selectedAction === "dropVtxo"
                  ? "text-red-700 dark:text-red-300"
                  : "text-muted-foreground"
              }`}
            >
              {selectedActionConfig.description}
            </Text>
          ) : null}
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

        <NativeNoahButton
          label="Execute Action"
          onPress={handleExecute}
          disabled={!selectedAction || isLoading}
          isLoading={isLoading}
          loadingLabel="Executing..."
          className="mb-6"
          fullWidth
        />

        <ConfirmationDialog
          open={isDropDialogOpen}
          onOpenChange={setIsDropDialogOpen}
          title="Drop VTXO"
          description={`This action is irreversible and can cause loss of funds. To confirm, please type "delete" in the box below.`}
          onConfirm={() => {
            void handleConfirmedDropVtxo();
          }}
          onCancel={() => setDropConfirmText("")}
          isConfirmDisabled={dropConfirmText.toLowerCase() !== "delete" || isLoading}
        >
          <Input
            value={dropConfirmText}
            onChangeText={setDropConfirmText}
            placeholder='Type "delete" to confirm'
            className="h-12"
            autoCapitalize="none"
            autoCorrect={false}
          />
        </ConfirmationDialog>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default DebugScreen;
