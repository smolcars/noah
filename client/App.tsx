import "./global.css";

import { BottomSheetProvider } from "@swmansion/react-native-bottom-sheet";
import * as Sentry from "@sentry/react-native";
import { QueryClientProvider } from "@tanstack/react-query";
import { useFonts } from "expo-font";
import * as SplashScreen from "expo-splash-screen";
import React, { useEffect } from "react";
import { Platform, View } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { Uniwind } from "uniwind";
import { APP_VARIANT } from "~/config";
import { AlertProvider } from "~/contexts/AlertProvider";
import { appFonts } from "~/lib/fonts";
import { redactSentryBreadcrumbData, redactSentryRequestData } from "~/lib/sentryPrivacy";
import AppNavigation from "~/Navigators";
import { queryClient } from "~/queryClient";

const isSentryDisabled = __DEV__ || APP_VARIANT === "regtest";

SplashScreen.preventAutoHideAsync().catch(() => {});

if (!isSentryDisabled) {
  // LNURL-pay puts LUD-18 payer identity and LUD-12 comments in callback query
  // parameters. Keep Sentry's useful network context, but redact those values
  // both when JavaScript breadcrumbs are captured and before an event is sent.
  const sentryOptions = {
    dsn: "https://ac229acf494dda7d1d84eebcc14f7769@o4509731937648640.ingest.us.sentry.io/4509731938435072",
    sendDefaultPii: true,
    // Cocoa records NSURLSession breadcrumbs outside the JavaScript SDK, so the
    // hooks below cannot sanitize them. Disable only that iOS breadcrumb source;
    // sanitized JavaScript fetch/XHR breadcrumbs remain enabled.
    ...(Platform.OS === "ios" ? { enableNetworkBreadcrumbs: false } : {}),
    beforeBreadcrumb: (breadcrumb) => {
      const data = redactSentryBreadcrumbData(breadcrumb.data);
      return data === breadcrumb.data ? breadcrumb : { ...breadcrumb, data };
    },
    beforeSend: (event) => {
      event.breadcrumbs = event.breadcrumbs?.map((breadcrumb) => {
        const data = redactSentryBreadcrumbData(breadcrumb.data);
        return data === breadcrumb.data ? breadcrumb : { ...breadcrumb, data };
      });
      event.request = redactSentryRequestData(event.request);
      return event;
    },
  } satisfies Parameters<typeof Sentry.init>[0] & {
    // Sentry RN forwards this Cocoa option, but omits it from its TypeScript options.
    enableNetworkBreadcrumbs?: boolean;
  };
  Sentry.init(sentryOptions);
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
