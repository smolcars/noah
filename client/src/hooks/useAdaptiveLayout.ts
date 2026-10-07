import { useState } from "react";
import { type LayoutChangeEvent, useWindowDimensions } from "react-native";
import { canShowCompanionPane } from "~/lib/adaptiveLayout";

export function useAdaptiveLayout() {
  const { fontScale } = useWindowDimensions();
  const [width, setWidth] = useState(0);

  const onLayout = (event: LayoutChangeEvent) => {
    setWidth(event.nativeEvent.layout.width);
  };

  return { width, isExpanded: canShowCompanionPane(width, fontScale), onLayout };
}
