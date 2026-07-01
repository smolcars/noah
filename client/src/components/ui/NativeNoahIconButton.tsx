import Icon from "@react-native-vector-icons/ionicons";
import { type ComponentProps } from "react";
import { Pressable, type StyleProp, type ViewStyle } from "react-native";

import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { useIconColor } from "~/hooks/useTheme";

type NativeNoahIconButtonProps = {
  iconName: ComponentProps<typeof Icon>["name"];
  onPress?: () => void;
  disabled?: boolean;
  isLoading?: boolean;
  size?: number;
  iconSize?: number;
  className?: string;
  style?: StyleProp<ViewStyle>;
  testID?: string;
};

export function NativeNoahIconButton({
  iconName,
  onPress,
  disabled = false,
  isLoading = false,
  size = 44,
  iconSize = 22,
  className,
  style,
  testID,
}: NativeNoahIconButtonProps) {
  const iconColor = useIconColor();
  const isDisabled = disabled || isLoading;

  return (
    <Pressable
      className={className}
      onPress={onPress}
      disabled={isDisabled}
      accessibilityRole="button"
      testID={testID}
      style={[
        {
          width: size,
          height: size,
          alignItems: "center",
          justifyContent: "center",
          borderRadius: size / 2,
          borderWidth: 1,
          borderColor: "#374151",
          backgroundColor: "transparent",
          opacity: isDisabled ? 0.55 : 1,
        },
        style,
      ]}
    >
      {isLoading ? (
        <NoahActivityIndicator size="small" />
      ) : (
        <Icon name={iconName} size={iconSize} color={iconColor} />
      )}
    </Pressable>
  );
}
