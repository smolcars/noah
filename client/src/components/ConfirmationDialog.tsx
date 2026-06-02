import React from "react";
import * as Haptics from "expo-haptics";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "./ui/alert-dialog";
import { Text } from "./ui/text";
import { Pressable, View } from "react-native";
import { Label } from "./ui/label";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { cn } from "~/lib/utils";

type DangerZoneRowProps = {
  title: string;
  description?: string;
  isPressable: boolean;
  variant?: "default" | "destructive";
  onPress?: () => void;
};

export const DangerZoneRow = ({
  title,
  description,
  isPressable,
  variant = "default",
  onPress,
}: DangerZoneRowProps) => {
  const iconColor = useIconColor();
  return (
    <Pressable
      disabled={!isPressable}
      onPress={onPress}
      className="flex-row justify-between items-center p-4 border-b border-border bg-card rounded-lg mb-2"
    >
      <View className="flex-1">
        <Label
          className={`text-lg ${variant === "destructive" ? "text-destructive" : "text-foreground"}`}
        >
          {title}
        </Label>
        {description && <Text className="text-muted-foreground text-base mt-1">{description}</Text>}
      </View>
      {isPressable && <Icon name="chevron-forward-outline" size={24} color={iconColor} />}
    </Pressable>
  );
};

type ConfirmationDialogProps = {
  trigger?: React.ReactNode;
  title: string;
  description: string;
  onConfirm: () => void;
  onCancel?: () => void;
  children?: React.ReactNode;
  confirmText?: string;
  cancelText?: string;
  confirmVariant?: "default" | "destructive";
  isConfirmDisabled?: boolean;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  contentClassName?: string;
  headerClassName?: string;
  titleClassName?: string;
  descriptionClassName?: string;
  footerClassName?: string;
  cancelClassName?: string;
  actionClassName?: string;
  /** Haptic feedback type for confirm action (default: Success for default variant, Warning for destructive) */
  confirmHapticType?: Haptics.NotificationFeedbackType;
  /** Haptic feedback type for cancel action (default: Light) */
  cancelHapticType?: Haptics.ImpactFeedbackStyle;
  /** Whether to enable haptic feedback (default: true) */
  enableHaptics?: boolean;
};

export const ConfirmationDialog = ({
  trigger,
  title,
  description,
  onConfirm,
  onCancel,
  children,
  confirmText = "Confirm",
  cancelText = "Cancel",
  confirmVariant = "destructive",
  isConfirmDisabled,
  open,
  onOpenChange,
  contentClassName,
  headerClassName,
  titleClassName,
  descriptionClassName,
  footerClassName,
  cancelClassName,
  actionClassName,
  confirmHapticType,
  cancelHapticType = Haptics.ImpactFeedbackStyle.Light,
  enableHaptics = true,
}: ConfirmationDialogProps) => {
  // Set default haptic type based on variant
  const defaultConfirmHapticType =
    confirmVariant === "destructive"
      ? Haptics.NotificationFeedbackType.Warning
      : Haptics.NotificationFeedbackType.Success;

  const finalConfirmHapticType = confirmHapticType ?? defaultConfirmHapticType;

  const handleConfirm = async () => {
    if (enableHaptics) {
      await Haptics.notificationAsync(finalConfirmHapticType);
    }
    onConfirm();
  };

  const handleCancel = async () => {
    if (enableHaptics) {
      await Haptics.impactAsync(cancelHapticType);
    }
    onCancel?.();
  };
  const content = (
    <AlertDialogContent className={contentClassName}>
      <AlertDialogHeader className={headerClassName}>
        <AlertDialogTitle className={titleClassName}>{title}</AlertDialogTitle>
        <AlertDialogDescription className={descriptionClassName}>
          {description}
        </AlertDialogDescription>
      </AlertDialogHeader>
      {children}
      <AlertDialogFooter className={cn("flex-row space-x-2", footerClassName)}>
        <AlertDialogCancel onPress={handleCancel} className={cn("flex-1", cancelClassName)}>
          <Text>{cancelText}</Text>
        </AlertDialogCancel>
        <AlertDialogAction
          variant={confirmVariant}
          onPress={handleConfirm}
          className={cn("flex-1", actionClassName)}
          disabled={isConfirmDisabled}
        >
          <Text>{confirmText}</Text>
        </AlertDialogAction>
      </AlertDialogFooter>
    </AlertDialogContent>
  );

  if (trigger) {
    return (
      <AlertDialog open={open} onOpenChange={onOpenChange}>
        <AlertDialogTrigger asChild>{trigger}</AlertDialogTrigger>
        {content}
      </AlertDialog>
    );
  }

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      {content}
    </AlertDialog>
  );
};
