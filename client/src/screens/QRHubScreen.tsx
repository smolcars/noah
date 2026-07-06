import React, { useEffect, useState } from "react";
import { Pressable, ScrollView, View } from "react-native";
import { useIsFocused, useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import QRCode from "react-native-qrcode-svg";
import Animated, { FadeInDown } from "react-native-reanimated";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { QRCodeScanner } from "~/components/QRCodeScanner";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { Text } from "~/components/ui/text";
import type { HomeStackParamList } from "~/Navigators";
import { useQRCodeScanner } from "~/hooks/useQRCodeScanner";
import { useIconColor } from "~/hooks/useTheme";
import { useServerStore } from "~/store/serverStore";
import { useProfileStore } from "~/store/profileStore";
import { copyToClipboard } from "~/lib/clipboardUtils";
import { COLORS } from "~/lib/styleConstants";
import { PLATFORM } from "~/constants";
import { cn } from "~/lib/utils";
import logoImage from "../../assets/All_Files/light_dark_tinted/icon_clear_tinted_ios.png";

type QRMode = "scan" | "my-code";

const addAddressBreakOpportunities = (address: string) =>
  address.replace("@", "@\u200B").replace(/\./g, ".\u200B");

const QRHubScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<HomeStackParamList>>();
  const isFocused = useIsFocused();
  const iconColor = useIconColor();
  const tabBarHeight = useBottomTabBarHeight();
  const lightningAddress = useServerStore((state) => state.lightningAddress);
  const displayName = useProfileStore((state) => state.displayName);
  const [mode, setMode] = useState<QRMode>("my-code");
  const [copied, setCopied] = useState(false);
  const displayLightningAddress = lightningAddress
    ? addAddressBreakOpportunities(lightningAddress)
    : "";

  const handleScannerValue = (value: string) => {
    setMode("my-code");
    navigation.navigate("Send", { destination: value });
  };

  const { showCamera, setShowCamera, handleScanPress, codeScanner } = useQRCodeScanner({
    onScan: handleScannerValue,
  });

  const handleModePress = (nextMode: QRMode) => {
    setMode(nextMode);
  };

  const handleScannerClose = () => {
    setShowCamera(false);
    setMode("my-code");
  };

  const handlePasteFromScanner = (value: string) => {
    setShowCamera(false);
    handleScannerValue(value);
  };

  useEffect(() => {
    if (!isFocused || mode !== "scan" || showCamera) {
      return;
    }

    void handleScanPress().then((opened) => {
      if (!opened) {
        setMode("my-code");
      }
    });
  }, [handleScanPress, isFocused, mode, showCamera]);

  useEffect(() => {
    if (!isFocused && showCamera) {
      setShowCamera(false);
      setMode("my-code");
    }
  }, [isFocused, setShowCamera, showCamera]);

  if (showCamera) {
    return (
      <QRCodeScanner
        codeScanner={codeScanner}
        onClose={handleScannerClose}
        onPaste={handlePasteFromScanner}
      />
    );
  }

  const copyLightningAddress = async () => {
    if (!lightningAddress) {
      return;
    }

    await copyToClipboard(lightningAddress, {
      onCopy: () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1800);
      },
    });
  };

  return (
    <NoahSafeAreaView
      className="flex-1 bg-background"
      style={{
        paddingBottom: PLATFORM === "ios" ? tabBarHeight : 0,
      }}
    >
      <ScrollView
        className="flex-1"
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingBottom: 32 }}
      >
        <View className="px-5 pb-8 pt-4">
          <View className="flex-row items-center">
            <Pressable onPress={() => navigation.goBack()} className="mr-4">
              <Icon name="arrow-back-outline" size={24} color={iconColor} />
            </Pressable>
            <Text className="text-2xl font-bold text-foreground">QR Code</Text>
          </View>

          <View className="mt-7 mb-6 flex flex-row justify-around rounded-lg bg-muted p-1">
            {(["my-code", "scan"] as const).map((item) => (
              <Pressable
                key={item}
                onPress={() => handleModePress(item)}
                className={cn(
                  "flex-1 items-center justify-center rounded-md p-2",
                  mode === item && "bg-background",
                )}
              >
                <Text
                  className={cn(
                    "font-bold",
                    mode === item ? "text-foreground" : "text-muted-foreground",
                  )}
                  numberOfLines={1}
                  ellipsizeMode="tail"
                  maxFontSizeMultiplier={1.2}
                >
                  {item === "my-code" ? "My code" : "Scan"}
                </Text>
              </Pressable>
            ))}
          </View>

          {mode === "my-code" ? (
            <Animated.View entering={FadeInDown.duration(360)} className="items-center pt-10">
              {lightningAddress ? (
                <>
                  <View className="rounded-[28px] bg-white p-5 shadow-sm shadow-foreground/5">
                    <QRCode
                      value={lightningAddress}
                      size={230}
                      backgroundColor="white"
                      color="black"
                      logo={logoImage}
                      logoSize={54}
                      logoBackgroundColor="white"
                      logoMargin={5}
                      logoBorderRadius={12}
                      ecl="H"
                    />
                  </View>

                  <View className="mt-7 items-center">
                    {displayName.trim().length > 0 ? (
                      <Text className="text-xl font-bold text-foreground">{displayName}</Text>
                    ) : null}
                    <Pressable onPress={copyLightningAddress} className="mt-2 w-full px-4">
                      <Text
                        className="text-center text-base font-semibold"
                        numberOfLines={2}
                        ellipsizeMode="tail"
                        maxFontSizeMultiplier={1.2}
                        style={{ color: copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
                      >
                        {copied ? "Copied" : displayLightningAddress}
                      </Text>
                    </Pressable>
                  </View>

                  <Text className="mt-5 max-w-[300px] text-center text-sm leading-6 text-muted-foreground">
                    This QR contains only your Lightning address.
                  </Text>
                </>
              ) : (
                <View className="w-full rounded-[18px] border border-border/60 bg-card/70 px-4 py-5">
                  <Text className="text-lg font-semibold text-foreground">
                    Lightning address unavailable
                  </Text>
                  <Text className="mt-2 text-sm leading-6 text-muted-foreground">
                    Finish setting up your Lightning address before sharing your QR.
                  </Text>
                  <NativeNoahButton
                    label="Open Profile"
                    onPress={() => navigation.navigate("Settings", { screen: "Profile" })}
                    className="mt-5 h-12 rounded-2xl"
                    fullWidth
                  />
                </View>
              )}
            </Animated.View>
          ) : (
            <Animated.View entering={FadeInDown.duration(360)} className="pt-10">
              <View className="items-center rounded-[18px] border border-border/60 bg-card/70 px-5 py-7">
                <View className="h-16 w-16 items-center justify-center rounded-full bg-primary/10">
                  <Icon name="scan-outline" size={30} color={COLORS.BITCOIN_ORANGE} />
                </View>
                <Text className="mt-5 text-xl font-bold text-foreground">Scan to pay</Text>
                <Text className="mt-2 max-w-[280px] text-center text-sm leading-6 text-muted-foreground">
                  Scan a Bitcoin, Lightning, or Ark QR code and send from Noah.
                </Text>
              </View>
            </Animated.View>
          )}
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default QRHubScreen;
