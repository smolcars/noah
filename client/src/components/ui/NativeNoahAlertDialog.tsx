import { Host } from "@expo/ui";
import { Alert as SwiftAlert, Button as SwiftButton, Text as SwiftText } from "@expo/ui/swift-ui";
import { disabled as swiftDisabled, frame, opacity } from "@expo/ui/swift-ui/modifiers";
import {
  AlertDialog as ComposeAlertDialog,
  Text as ComposeText,
  TextButton as ComposeTextButton,
} from "@expo/ui/jetpack-compose";
import { Platform, View } from "react-native";

import { useTheme } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

type NativeNoahAlertDialogVariant = "default" | "destructive";

type NativeNoahAlertDialogProps = {
  open: boolean;
  title: string;
  description: string;
  confirmText?: string;
  cancelText?: string;
  confirmVariant?: NativeNoahAlertDialogVariant;
  isConfirmDisabled?: boolean;
  onConfirm: () => void | Promise<void>;
  onCancel?: () => void | Promise<void>;
  onOpenChange: (open: boolean) => void;
};

const DIALOG_HOST_STYLE = {
  position: "absolute",
  left: 0,
  top: 0,
  width: 1,
  height: 1,
} as const;

export function NativeNoahAlertDialog({
  open,
  title,
  description,
  confirmText = "OK",
  cancelText,
  confirmVariant = "default",
  isConfirmDisabled = false,
  onConfirm,
  onCancel,
  onOpenChange,
}: NativeNoahAlertDialogProps) {
  const { colors, colorScheme } = useTheme();
  const confirmColor = confirmVariant === "destructive" ? "#dc2626" : COLORS.BITCOIN_ORANGE;
  const hostSeedColor = Platform.OS === "ios" ? colors.foreground : confirmColor;

  const handleConfirm = () => {
    if (isConfirmDisabled) {
      return;
    }
    void onConfirm();
    onOpenChange(false);
  };

  const handleCancel = () => {
    void onCancel?.();
    onOpenChange(false);
  };

  if (!open) {
    return null;
  }

  return (
    <View style={DIALOG_HOST_STYLE}>
      <Host seedColor={hostSeedColor} colorScheme={colorScheme} style={{ width: 1, height: 1 }}>
        {Platform.OS === "android" ? (
          <ComposeAlertDialog
            onDismissRequest={() => onOpenChange(false)}
            colors={{
              containerColor: colors.background,
              titleContentColor: colors.foreground,
              textContentColor: colors.mutedForeground,
            }}
          >
            <ComposeAlertDialog.Title>
              <ComposeText
                color={colors.foreground}
                style={{ fontSize: 20, fontWeight: "700", typography: "titleLarge" }}
              >
                {title}
              </ComposeText>
            </ComposeAlertDialog.Title>
            <ComposeAlertDialog.Text>
              <ComposeText
                color={colors.mutedForeground}
                style={{ fontSize: 16, lineHeight: 22, typography: "bodyMedium" }}
              >
                {description}
              </ComposeText>
            </ComposeAlertDialog.Text>
            <ComposeAlertDialog.ConfirmButton>
              <ComposeTextButton
                onClick={handleConfirm}
                enabled={!isConfirmDisabled}
                colors={{
                  contentColor: confirmColor,
                  disabledContentColor: colors.mutedForeground,
                }}
              >
                <ComposeText
                  color={isConfirmDisabled ? colors.mutedForeground : confirmColor}
                  style={{ fontSize: 14, fontWeight: "700", typography: "labelLarge" }}
                >
                  {confirmText}
                </ComposeText>
              </ComposeTextButton>
            </ComposeAlertDialog.ConfirmButton>
            {cancelText ? (
              <ComposeAlertDialog.DismissButton>
                <ComposeTextButton
                  onClick={handleCancel}
                  colors={{ contentColor: colors.foreground }}
                >
                  <ComposeText
                    color={colors.foreground}
                    style={{ fontSize: 14, fontWeight: "600", typography: "labelLarge" }}
                  >
                    {cancelText}
                  </ComposeText>
                </ComposeTextButton>
              </ComposeAlertDialog.DismissButton>
            ) : null}
          </ComposeAlertDialog>
        ) : (
          <SwiftAlert
            title={title}
            isPresented={open}
            onIsPresentedChange={(isPresented) => {
              if (!isPresented) {
                onOpenChange(false);
              }
            }}
          >
            <SwiftAlert.Trigger>
              <SwiftText modifiers={[frame({ width: 0, height: 0 }), opacity(0)]}> </SwiftText>
            </SwiftAlert.Trigger>
            <SwiftAlert.Message>
              <SwiftText>{description}</SwiftText>
            </SwiftAlert.Message>
            <SwiftAlert.Actions>
              {cancelText ? (
                <SwiftButton label={cancelText} role="cancel" onPress={handleCancel} />
              ) : null}
              <SwiftButton
                label={confirmText}
                role={confirmVariant === "destructive" ? "destructive" : "default"}
                onPress={handleConfirm}
                modifiers={isConfirmDisabled ? [swiftDisabled(true)] : undefined}
              />
            </SwiftAlert.Actions>
          </SwiftAlert>
        )}
      </Host>
    </View>
  );
}
