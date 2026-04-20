import "./global.css";

import { QueryClientProvider } from "@tanstack/react-query";
import { SafeAreaProvider } from "react-native-safe-area-context";
import React, { useEffect } from "react";
import { AlertProvider } from "~/contexts/AlertProvider";
import AppNavigation from "~/Navigators";
import * as Sentry from "@sentry/react-native";
import { View } from "react-native";
import { Uniwind } from "uniwind";

import { GestureHandlerRootView } from "react-native-gesture-handler";
import { queryClient } from "~/queryClient";
import { APP_VARIANT } from "~/config";
const isSentryDisabled = __DEV__ || APP_VARIANT === "regtest";

if (!isSentryDisabled) {
  Sentry.init({
    dsn: "https://ac229acf494dda7d1d84eebcc14f7769@o4509731937648640.ingest.us.sentry.io/4509731938435072",
    sendDefaultPii: true,
    integrations: [
      Sentry.feedbackIntegration({
        showName: true,
        showEmail: true,
        isNameRequired: false,
        isEmailRequired: false,
      }),
    ],
  });
}

const AppContent = () => {
  useEffect(() => {
    // Let Uniwind manage theme based on system preference
    Uniwind.setTheme("system");
  }, []);

  return (
    <View className="flex-1">
      <QueryClientProvider client={queryClient}>
        <SafeAreaProvider>
          <GestureHandlerRootView style={{ flex: 1 }}>
            <AlertProvider>
              <AppNavigation />
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
