import React from "react";
import { TouchableOpacity, View } from "react-native";
import { ArrowLeftRight } from "lucide-react-native";
import { COLORS } from "~/lib/styleConstants";

interface CurrencyToggleProps {
  onPress: () => void;
  disabled?: boolean;
}

export const CurrencyToggle: React.FC<CurrencyToggleProps> = ({ onPress, disabled = false }) => {
  return (
    <TouchableOpacity onPress={onPress} disabled={disabled} activeOpacity={0.7}>
      <View className="bg-background border border-border rounded-lg px-3 py-2 items-center justify-center">
        <ArrowLeftRight size={16} color={disabled ? "#6b7280" : COLORS.BITCOIN_ORANGE} />
      </View>
    </TouchableOpacity>
  );
};
