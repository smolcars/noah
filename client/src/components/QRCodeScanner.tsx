import React from "react";
import { View, Pressable, StyleSheet } from "react-native";
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
      <View className="flex-1 bg-transparent">
        <View className="flex-1 bg-black/60" />
        <View className="flex-row h-[250px]">
          <View className="flex-1 bg-black/60" />
          <View className="w-[250px] h-[250px] border-2 border-white rounded-lg" />
          <View className="flex-1 bg-black/60" />
        </View>
        <View className="flex-1 bg-black/60 justify-center items-center">
          <View className="flex-row items-center gap-3">
            {onPaste ? (
              <Pressable
                onPress={handlePaste}
                className="bg-white/20 rounded-full p-4 border border-white/30"
              >
                <View className="flex-row items-center justify-center space-x-2">
                  <Icon name="clipboard" size={28} color="white" />
                  <Text className="text-white text-lg font-semibold ml-2">Paste</Text>
                </View>
              </Pressable>
            ) : null}
            <Pressable
              onPress={onClose}
              className="bg-white/20 rounded-full p-4 border border-white/30"
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
