import "./global.css";

import { BottomSheetProvider } from "@swmansion/react-native-bottom-sheet";
import * as Sentry from "@sentry/react-native";
import { QueryClientProvider } from "@tanstack/react-query";
import { useFonts } from "expo-font";
import * as SplashScreen from "expo-splash-screen";
import React, { useEffect } from "react";
import { View } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { Uniwind } from "uniwind";
import { APP_VARIANT } from "~/config";
import { AlertProvider } from "~/contexts/AlertProvider";
import { appFonts } from "~/lib/fonts";
import AppNavigation from "~/Navigators";
import { queryClient } from "~/queryClient";

const isSentryDisabled = __DEV__ || APP_VARIANT === "regtest";

SplashScreen.preventAutoHideAsync().catch(() => {});

if (!isSentryDisabled) {
  Sentry.init({
    dsn: "https://ac229acf494dda7d1d84eebcc14f7769@o4509731937648640.ingest.us.sentry.io/4509731938435072",
    sendDefaultPii: true,
  });
}

const AppContent = () => {
  const [fontsLoaded, fontError] = useFonts(appFonts);

  useEffect(() => {
    // Let Uniwind manage theme based on system preference
    Uniwind.setTheme("system");
  }, []);

  useEffect(() => {
    if (fontsLoaded || fontError) {
      void SplashScreen.hideAsync();
    }
  }, [fontsLoaded, fontError]);

  if (!fontsLoaded && !fontError) {
    return null;
  }

  return (
    <View className="flex-1">
      <QueryClientProvider client={queryClient}>
        <SafeAreaProvider>
          <GestureHandlerRootView style={{ flex: 1 }}>
            <AlertProvider>
              <BottomSheetProvider>
                <AppNavigation />
              </BottomSheetProvider>
            </AlertProvider>
          </GestureHandlerRootView>
        </SafeAreaProvider>
      </QueryClientProvider>
    </View>
  );
};

const App = () => {
  return <AppContent />;
};

export default isSentryDisabled ? App : Sentry.wrap(App);
