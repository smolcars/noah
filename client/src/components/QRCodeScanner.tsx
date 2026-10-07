import React from "react";
import { View, Pressable, StyleSheet, useWindowDimensions } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Camera, CodeScanner, useCameraDevice } from "react-native-vision-camera";
import { useIsFocused } from "@react-navigation/native";
import * as Clipboard from "expo-clipboard";
import { NoahSafeAreaView } from "./NoahSafeAreaView";
import { Text } from "./ui/text";
import { NativeNoahButton } from "./ui/NativeNoahButton";
import Icon from "@react-native-vector-icons/ionicons";

type QRCodeScannerProps = {
  codeScanner: CodeScanner;
  onClose: () => void;
  onPaste?: (value: string) => void;
};

export const QRCodeScanner = ({ codeScanner, onClose, onPaste }: QRCodeScannerProps) => {
  const device = useCameraDevice("back");
  const isFocused = useIsFocused();
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const frameSize = Math.max(
    1,
    Math.min(
      250,
      width - insets.left - insets.right - 32,
      height - insets.top - insets.bottom - 160,
    ),
  );

  const handlePaste = async () => {
    const text = await Clipboard.getStringAsync();
    if (text.trim().length === 0) {
      return;
    }

    onPaste?.(text);
  };

  if (!device) {
    return (
      <NoahSafeAreaView className="flex-1 bg-background justify-center items-center p-4">
        <Text className="text-lg text-center">No camera device found.</Text>
        <NativeNoahButton label="Back" onPress={onClose} className="mt-4" />
      </NoahSafeAreaView>
    );
  }

  return (
    <View className="flex-1">
      <Camera
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={isFocused}
        codeScanner={codeScanner}
      />
      <View
        className="flex-1 bg-transparent"
        style={{
          paddingTop: insets.top,
          paddingBottom: insets.bottom,
          paddingLeft: insets.left,
          paddingRight: insets.right,
        }}
      >
        <View className="flex-1 bg-black/60" />
        <View className="flex-row" style={{ height: frameSize }}>
          <View className="flex-1 bg-black/60" />
          <View
            className="border-2 border-white rounded-lg"
            style={{ width: frameSize, height: frameSize }}
          />
          <View className="flex-1 bg-black/60" />
        </View>
        <View className="flex-1 bg-black/60 justify-center items-center">
          <View className="flex-row items-center gap-3">
            {onPaste ? (
              <Pressable
                accessibilityLabel="Paste payment request"
                accessibilityRole="button"
                onPress={handlePaste}
                className="bg-white/20 rounded-full p-4 border border-white/30"
                testID="qr-scanner-paste"
              >
                <View className="flex-row items-center justify-center space-x-2">
                  <Icon name="clipboard" size={28} color="white" />
                  <Text className="text-white text-lg font-semibold ml-2">Paste</Text>
                </View>
              </Pressable>
            ) : null}
            <Pressable
              accessibilityLabel="Close scanner"
              accessibilityRole="button"
              onPress={onClose}
              className="bg-white/20 rounded-full p-4 border border-white/30"
              testID="qr-scanner-close"
            >
              <View className="flex-row items-center justify-center space-x-2">
                <Icon name="close-circle" size={28} color="white" />
                <Text className="text-white text-lg font-semibold ml-2">Close</Text>
              </View>
            </Pressable>
          </View>
        </View>
      </View>
    </View>
  );
};
