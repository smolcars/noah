import React from "react";
import { View, Pressable } from "react-native";
import { ChevronRight, Mail, X } from "lucide-react-native";
import { Text } from "./ui/text";
import { useIconColor } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

interface EmailVerificationBannerProps {
  onPress: () => void;
  onDismiss: () => void;
}

export const EmailVerificationBanner: React.FC<EmailVerificationBannerProps> = ({
  onPress,
  onDismiss,
}) => {
  const iconColor = useIconColor();

  return (
    <View className="mx-4 mt-4 mb-2 bg-card border border-border rounded-xl">
      <Pressable onPress={onPress} className="p-4 pr-12">
        <View className="flex-row items-center justify-between mb-2">
          <View className="flex-row items-center gap-2">
            <Mail size={20} color={COLORS.BITCOIN_ORANGE} />
            <Text className="text-base font-semibold" style={{ color: COLORS.BITCOIN_ORANGE }}>
              Add Emergency Email
            </Text>
          </View>
          <ChevronRight size={20} color={iconColor} />
        </View>

        <Text className="text-sm text-muted-foreground">
          Optional alerts can help us reach you if your VTXOs need attention before they expire.
        </Text>
      </Pressable>
      <Pressable
        onPress={onDismiss}
        accessibilityLabel="Dismiss emergency email prompt"
        className="absolute right-3 top-3 h-8 w-8 items-center justify-center rounded-full"
      >
        <X size={18} color={iconColor} />
      </Pressable>
    </View>
  );
};
