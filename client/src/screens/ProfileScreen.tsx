import React, { useState } from "react";
import { Image, Pressable, ScrollView, View } from "react-native";
import * as ImagePicker from "expo-image-picker";
import RNFSTurbo from "react-native-fs-turbo";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Label } from "~/components/ui/label";
import { Text } from "~/components/ui/text";
import type { SettingsStackParamList } from "~/Navigators";
import { copyToClipboard } from "~/lib/clipboardUtils";
import { COLORS } from "~/lib/styleConstants";
import { DOCUMENT_DIRECTORY_PATH } from "~/constants";
import { useIconColor, useThemeColors } from "~/hooks/useTheme";
import { useDeriveKeyPairFromMnemonic } from "~/hooks/useCrypto";
import { useProfileStore } from "~/store/profileStore";
import { useServerStore } from "~/store/serverStore";
import logger from "~/lib/log";

type ProfileNavigationProp = NativeStackNavigationProp<SettingsStackParamList, "Profile">;
const log = logger("ProfileScreen");

const truncateValue = (value: string) => {
  if (value.length <= 44) {
    return value;
  }

  return `${value.slice(0, 18)}...${value.slice(-14)}`;
};

const CopyRow = ({ label, value }: { label: string; value: string }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await copyToClipboard(value, {
      onCopy: () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1800);
      },
    });
  };

  return (
    <Pressable onPress={handleCopy} className="flex-row items-center gap-3 px-4 py-4">
      <View className="min-w-0 flex-1">
        <Text className="text-sm text-muted-foreground">{label}</Text>
        <Text
          className="mt-1 text-base font-semibold text-foreground"
          numberOfLines={1}
          ellipsizeMode="middle"
        >
          {copied ? "Copied" : truncateValue(value)}
        </Text>
      </View>
      <Text
        className="text-xs font-semibold uppercase tracking-[2px]"
        style={{ color: copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
      >
        {copied ? "Copied" : "Copy"}
      </Text>
    </Pressable>
  );
};

const ProfileScreen = () => {
  const navigation = useNavigation<ProfileNavigationProp>();
  const iconColor = useIconColor();
  const colors = useThemeColors();
  const lightningAddress = useServerStore((state) => state.lightningAddress);
  const displayName = useProfileStore((state) => state.displayName);
  const avatarUri = useProfileStore((state) => state.avatarUri);
  const setDisplayName = useProfileStore((state) => state.setDisplayName);
  const setAvatarUri = useProfileStore((state) => state.setAvatarUri);
  const { data: derivedKeyPair } = useDeriveKeyPairFromMnemonic();
  const initials = displayName.trim().slice(0, 2).toUpperCase() || "N";

  const deleteLocalAvatar = (uri: string | null) => {
    if (!uri?.includes("noah-profile-avatar")) {
      return;
    }

    try {
      RNFSTurbo.unlink(uri.replace("file://", ""));
    } catch (error) {
      log.w("Failed to remove local avatar", [error]);
    }
  };

  const chooseAvatar = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ["images"],
      allowsEditing: true,
      aspect: [1, 1],
      quality: 0.85,
    });

    if (!result.canceled && result.assets?.[0]?.uri) {
      const sourceUri = result.assets[0].uri;
      const extension = sourceUri.split(".").pop()?.split("?")[0] || "jpg";
      const destinationPath = `${DOCUMENT_DIRECTORY_PATH}/noah-profile-avatar-${Date.now()}.${extension}`;

      try {
        RNFSTurbo.copyFile(sourceUri.replace("file://", ""), destinationPath);
        deleteLocalAvatar(avatarUri);
        setAvatarUri(`file://${destinationPath}`);
      } catch (error) {
        log.w("Failed to persist selected avatar", [error]);
        setAvatarUri(sourceUri);
      }
    }
  };

  const removeAvatar = () => {
    deleteLocalAvatar(avatarUri);
    setAvatarUri(null);
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
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
            <Text className="text-2xl font-bold text-foreground">Profile</Text>
          </View>

          <View className="mt-8 items-center">
            <Pressable onPress={chooseAvatar}>
              {avatarUri ? (
                <Image source={{ uri: avatarUri }} className="h-28 w-28 rounded-full" />
              ) : (
                <View
                  className="h-28 w-28 items-center justify-center rounded-full border"
                  style={{
                    borderColor: `${colors.mutedForeground}24`,
                    backgroundColor: `${colors.card}CC`,
                  }}
                >
                  <Text className="text-4xl font-bold text-foreground">{initials}</Text>
                </View>
              )}
            </Pressable>
            <View className="mt-4 flex-row gap-3">
              <Button onPress={chooseAvatar} variant="outline" className="h-11 rounded-2xl">
                <Text>{avatarUri ? "Change Avatar" : "Set Avatar"}</Text>
              </Button>
              {avatarUri ? (
                <Button
                  onPress={removeAvatar}
                  variant="ghost"
                  className="h-11 rounded-2xl"
                >
                  <Text>Remove</Text>
                </Button>
              ) : null}
            </View>
          </View>

          <View className="mt-8">
            <Label className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
              Name
            </Label>
            <Input
              value={displayName}
              onChangeText={setDisplayName}
              placeholder="Add a display name"
              className="mt-3 h-14 rounded-2xl px-4"
              maxLength={48}
              autoCapitalize="words"
              autoCorrect={false}
            />
            <Text className="mt-2 text-sm text-muted-foreground">
              Saved locally on this device until profile sync is added.
            </Text>
          </View>

          <View className="mt-8">
            <Text className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
              Lightning Address
            </Text>
            <View
              className="mt-3 overflow-hidden rounded-[18px] border"
              style={{
                borderColor: `${colors.mutedForeground}24`,
                backgroundColor: `${colors.card}CC`,
              }}
            >
              {lightningAddress ? (
                <CopyRow label="Address" value={lightningAddress} />
              ) : (
                <View className="px-4 py-4">
                  <Text className="text-base font-semibold text-foreground">
                    No Lightning address set
                  </Text>
                  <Text className="mt-1 text-sm text-muted-foreground">
                    Create one to receive payments with your QR code.
                  </Text>
                </View>
              )}
              <View className="h-px bg-border" />
              <Pressable
                onPress={() => navigation.navigate("LightningAddress", { fromOnboarding: false })}
                className="flex-row items-center justify-between px-4 py-4"
              >
                <Text className="text-base font-semibold text-foreground">
                  Change Lightning Address
                </Text>
                <Icon name="chevron-forward-outline" size={22} color={iconColor} />
              </Pressable>
            </View>
          </View>

          {derivedKeyPair?.public_key ? (
            <View className="mt-8">
              <Text className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
                Public Key
              </Text>
              <View
                className="mt-3 overflow-hidden rounded-[18px] border"
                style={{
                  borderColor: `${colors.mutedForeground}24`,
                  backgroundColor: `${colors.card}CC`,
                }}
              >
                <CopyRow label="Wallet public key" value={derivedKeyPair.public_key} />
              </View>
            </View>
          ) : null}
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default ProfileScreen;
