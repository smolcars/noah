import type React from "react";
import { ScrollView, StyleSheet, useWindowDimensions, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import {
  ModalBottomSheet,
  type Detent,
} from "@swmansion/react-native-bottom-sheet";

type AppBottomSheetProps = {
  isOpen: boolean;
  onClose: () => void;
  onDismiss?: () => void;
  children: React.ReactNode;
  detents?: Detent[];
  scrimColor?: string;
  scrollable?: boolean;
};

export const AppBottomSheet = ({
  isOpen,
  onClose,
  onDismiss,
  children,
  detents,
  scrimColor = "rgba(0, 0, 0, 0.55)",
  scrollable = false,
}: AppBottomSheetProps) => {
  const { height: windowHeight } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const sheetHeight = Math.max(windowHeight - Math.max(insets.top, 16) - 12, 320);
  const resolvedDetents = detents ?? [0, sheetHeight];
  const openIndex = resolvedDetents.length - 1;
  const shouldConstrainContentHeight = detents === undefined;

  return (
    <ModalBottomSheet
      index={isOpen ? openIndex : 0}
      detents={resolvedDetents}
      onIndexChange={(index) => {
        if (index === 0) {
          onClose();
        }
      }}
      onSettle={(index) => {
        if (index === 0) {
          onDismiss?.();
        }
      }}
      scrimColor={scrimColor}
      surface={
        <View
          className="rounded-t-[32px] border border-border bg-background"
          style={StyleSheet.absoluteFill}
        />
      }
    >
      <View
        className="px-4 pt-3"
        style={shouldConstrainContentHeight ? { height: sheetHeight } : undefined}
      >
        <View className="mb-3 h-1 w-12 self-center rounded-full bg-muted-foreground/30" />
        {scrollable ? (
          <ScrollView
            bounces={false}
            className="flex-1"
            contentContainerStyle={{
              paddingBottom: Math.max(insets.bottom, 12) + 20,
            }}
            showsVerticalScrollIndicator={false}
          >
            {children}
          </ScrollView>
        ) : (
          children
        )}
      </View>
    </ModalBottomSheet>
  );
};
