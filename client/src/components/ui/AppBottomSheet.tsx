import { Host as SwiftHost, Rectangle as SwiftRectangle } from "@expo/ui/swift-ui";
import { foregroundStyle, glassEffect } from "@expo/ui/swift-ui/modifiers";
import { useEffect, useState, type ReactNode } from "react";
import {
  Keyboard,
  Platform,
  ScrollView,
  StyleSheet,
  useWindowDimensions,
  View,
  type KeyboardEvent,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { ModalBottomSheet, type Detent } from "@swmansion/react-native-bottom-sheet";

import { useTheme } from "~/hooks/useTheme";

type AppBottomSheetProps = {
  isOpen: boolean;
  onClose: () => void;
  onDismiss?: () => void;
  children: ReactNode;
  detents?: Detent[];
  scrimColor?: string;
  scrollable?: boolean;
  avoidKeyboard?: boolean;
  liquidGlass?: boolean;
};

function LiquidGlassSheetSurface() {
  const { isDark } = useTheme();

  return (
    <SwiftHost
      colorScheme={isDark ? "dark" : "light"}
      pointerEvents="none"
      style={[
        StyleSheet.absoluteFill,
        {
          borderTopLeftRadius: 32,
          borderTopRightRadius: 32,
          overflow: "hidden",
        },
      ]}
    >
      <SwiftRectangle
        modifiers={[
          foregroundStyle("clear"),
          glassEffect({ glass: { variant: "regular" }, shape: "rectangle" }),
        ]}
      />
    </SwiftHost>
  );
}

export const AppBottomSheet = ({
  isOpen,
  onClose,
  onDismiss,
  children,
  detents,
  scrimColor = "rgba(0, 0, 0, 0.55)",
  scrollable = false,
  avoidKeyboard = false,
  liquidGlass = false,
}: AppBottomSheetProps) => {
  const { height: windowHeight } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [keyboardOffset, setKeyboardOffset] = useState(0);
  const sheetHeight = Math.max(windowHeight - Math.max(insets.top, 16) - 12, 320);
  const resolvedDetents: Detent[] = detents ?? [0, "content"];
  const openIndex = resolvedDetents.length - 1;
  const openDetent = resolvedDetents[openIndex];
  const openDetentValue =
    typeof openDetent === "object" && openDetent !== null ? openDetent.value : openDetent;
  const contentHeight =
    detents === undefined
      ? sheetHeight
      : typeof openDetentValue === "number" && openDetentValue > 0
        ? openDetentValue
        : undefined;
  const showsLiquidGlass = liquidGlass && Platform.OS === "ios" && Number(Platform.Version) >= 26;

  useEffect(() => {
    if (!avoidKeyboard) {
      return;
    }

    const handleKeyboardChange = (event: KeyboardEvent) => {
      Keyboard.scheduleLayoutAnimation(event);
      setKeyboardOffset(Math.max(0, windowHeight - event.endCoordinates.screenY));
    };
    const handleKeyboardHide = (event: KeyboardEvent) => {
      Keyboard.scheduleLayoutAnimation(event);
      setKeyboardOffset(0);
    };
    const changeEvent = Platform.OS === "ios" ? "keyboardWillChangeFrame" : "keyboardDidShow";
    const hideEvent = Platform.OS === "ios" ? "keyboardWillHide" : "keyboardDidHide";
    const changeSubscription = Keyboard.addListener(changeEvent, handleKeyboardChange);
    const hideSubscription = Keyboard.addListener(hideEvent, handleKeyboardHide);

    return () => {
      changeSubscription.remove();
      hideSubscription.remove();
    };
  }, [avoidKeyboard, windowHeight]);

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
      style={avoidKeyboard && keyboardOffset > 0 ? { bottom: keyboardOffset } : undefined}
      surface={
        showsLiquidGlass ? (
          <LiquidGlassSheetSurface />
        ) : (
          <View
            className="rounded-t-[32px] border border-border bg-background"
            style={StyleSheet.absoluteFill}
          />
        )
      }
    >
      <View
        className="px-4 pt-3"
        style={contentHeight === undefined ? undefined : { height: contentHeight }}
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
