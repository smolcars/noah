import type { ReactNode } from "react";
import { View } from "react-native";

import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { Text } from "~/components/ui/text";
import { cn } from "~/lib/utils";

type ScreenHeaderProps = {
  title: string;
  onBack?: () => void;
  backButtonTestID?: string;
  actions?: ReactNode;
  className?: string;
};

// Place outside scrolling content so native glass press animations are not clipped.
export function ScreenHeader({
  title,
  onBack,
  backButtonTestID,
  actions,
  className,
}: ScreenHeaderProps) {
  return (
    <View className={cn("flex-row items-center gap-3 pt-4", className)}>
      {onBack ? <NativeNoahBackButton onPress={onBack} testID={backButtonTestID} /> : null}
      <Text
        accessibilityRole="header"
        className="min-w-0 flex-1 text-2xl font-bold text-foreground"
      >
        {title}
      </Text>
      {actions ? <View className="flex-row items-center gap-4">{actions}</View> : null}
    </View>
  );
}
