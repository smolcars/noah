import { ExpoConfig } from "expo/config";

const config: { expo: ExpoConfig } = {
  expo: {
    name: "Noah",
    slug: "noahs-ark-wallet",
    orientation: "portrait",
    userInterfaceStyle: "automatic",
    experiments: {
      reactCompiler: true,
    },
    extra: {
      eas: {
        projectId: "6e79dffb-dcd4-4f3d-b596-638b16377eb0",
      },
    },
    plugins: [
      "expo-sqlite",
      [
        "expo-local-authentication",
        {
          faceIDPermission: "Allow $(PRODUCT_NAME) to use Face ID.",
        },
      ],
      [
        "expo-image-picker",
        {
          photosPermission:
            "Allow $(PRODUCT_NAME) to access your photos so that you can pick screenshots to share feedback.",
        },
      ],
      [
        "react-native-share",
        {
          ios: ["whatsapp", "telegram", "signal"],
          android: ["com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms"],
          enableBase64ShareAndroid: true,
        },
      ],
      [
        "react-native-edge-to-edge",
        {
          android: {
            parentTheme: "Material3",
            enforceNavigationBarContrast: false,
          },
        },
      ],
      [
        "expo-build-properties",
        {
          android: {
            buildArchs: ["arm64-v8a", "x86_64"],
          },
          ios: {
            extraPods: [
              { name: "SDWebImage", modular_headers: true },
              { name: "SDWebImageSVGCoder", modular_headers: true },
            ],
          },
        },
      ],
      [
        "react-native-vision-camera",
        {
          cameraPermissionText: "$(PRODUCT_NAME) needs access to your Camera.",
          enableCodeScanner: true,
        },
      ],
      "expo-notifications",
      "./plugins/withNoahAndroidPrebuildFix.js",
      "./plugins/withNoahIosPrebuildFix.js",
    ],
    ios: {
      supportsTablet: true,
      bundleIdentifier: "com.noahwallet.mainnet",
      scheme: "com.noahwallet.mainnet",
      infoPlist: {
        UIBackgroundModes: ["remote-notification", "fetch"],
      },
      splash: {
        image: "./assets/All_Files/splash_screens/splash_screen_ios.png",
        resizeMode: "contain",
        backgroundColor: "#000000",
      },
      icon: "./assets/noah.icon",
    },
    android: {
      adaptiveIcon: {
        foregroundImage:
          "./assets/All_Files/android/Android_Adaptive/android_adaptive_foreground.png",
        backgroundColor: "#000000",
      },
      package: "com.noahwallet",
      splash: {
        image: "./assets/All_Files/splash_screens/splash_screen_android.png",
        resizeMode: "contain",
        backgroundColor: "#000000",
      },
      softwareKeyboardLayoutMode: "pan",
    },
    androidStatusBar: {
      barStyle: "light-content",
      backgroundColor: "#000000",
    },
    web: {
      favicon: "./assets/favicon.png",
      bundler: "metro",
    },
  },
};

// WARNING: Do not change these values manually. Use the `just bump` command instead.
config.expo.version = "0.0.12";
config.expo.android!.versionCode = 14;
config.expo.ios!.buildNumber = "14";

export default config;
