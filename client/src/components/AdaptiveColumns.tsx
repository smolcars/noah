import { Children, type ReactNode } from "react";
import { View } from "react-native";
import { useAdaptiveLayout } from "~/hooks/useAdaptiveLayout";
import { PANE_GAP } from "~/lib/adaptiveLayout";

// Keep the same children mounted when a form changes between one and two columns.
export function AdaptiveColumns({ children }: { children: ReactNode }) {
  const { width, isExpanded, onLayout } = useAdaptiveLayout();

  return (
    <View
      onLayout={onLayout}
      style={{ flexDirection: isExpanded ? "row" : "column", gap: isExpanded ? PANE_GAP : 0 }}
    >
      {Children.map(children, (child) => (
        <View style={{ width: isExpanded ? (width - PANE_GAP) / 2 : "100%", minWidth: 0 }}>
          {child}
        </View>
      ))}
    </View>
  );
}
