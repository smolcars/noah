import React, { useState } from "react";
import { View, ViewStyle } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

export function NoahSafeAreaView({
  children,
  style,
  className,
  maxContentWidth,
}: {
  children: React.ReactNode;
  style?: ViewStyle;
  className?: string;
  maxContentWidth?: number;
}) {
  const insets = useSafeAreaInsets();
  const [width, setWidth] = useState(0);
  const contentInset = maxContentWidth
    ? Math.max(0, (width - insets.left - insets.right - 32 - maxContentWidth) / 2)
    : 0;

  return (
    <View
      onLayout={maxContentWidth ? (event) => setWidth(event.nativeEvent.layout.width) : undefined}
      style={[
        {
          paddingTop: insets.top,
          paddingBottom: insets.bottom,
          paddingLeft: insets.left + 16 + contentInset,
          paddingRight: insets.right + 16 + contentInset,
        },
        style,
      ]}
      className={className}
    >
      {children}
    </View>
  );
}
