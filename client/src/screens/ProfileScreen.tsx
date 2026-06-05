import React, { useEffect, useRef, useState } from "react";
import { Pressable, ScrollView, TextInput, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Input } from "~/components/ui/input";
import { Label } from "~/components/ui/label";
import { Text } from "~/components/ui/text";
import type { SettingsStackParamList } from "~/Navigators";
import { getUserInfo, updateProfile } from "~/lib/api";
import { copyToClipboard } from "~/lib/clipboardUtils";
import { COLORS } from "~/lib/styleConstants";
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
  const emailAddress = useServerStore((state) => state.emailAddress);
  const isEmailVerified = useServerStore((state) => state.isEmailVerified);
  const isRegisteredWithServer = useServerStore((state) => state.isRegisteredWithServer);
  const lightningAddress = useServerStore((state) => state.lightningAddress);
  const setEmailAddress = useServerStore((state) => state.setEmailAddress);
  const displayName = useProfileStore((state) => state.displayName);
  const setDisplayName = useProfileStore((state) => state.setDisplayName);
  const { data: derivedKeyPair } = useDeriveKeyPairFromMnemonic();
  const [draftDisplayName, setDraftDisplayName] = useState(displayName);
  const [saveStatus, setSaveStatus] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [isEditingName, setIsEditingName] = useState(false);
  const nameInputRef = useRef<TextInput>(null!);

  useEffect(() => {
    setDraftDisplayName(displayName);
  }, [displayName]);

  useEffect(() => {
    if (!isRegisteredWithServer || !isEmailVerified || emailAddress) {
      return;
    }

    let cancelled = false;

    const refreshEmail = async () => {
      const result = await getUserInfo();
      if (cancelled) {
        return;
      }

      if (result.isErr()) {
        log.w("Failed to refresh emergency email from server", [result.error]);
        return;
      }

      setEmailAddress(result.value.email);
    };

    refreshEmail();

    return () => {
      cancelled = true;
    };
  }, [emailAddress, isEmailVerified, isRegisteredWithServer, setEmailAddress]);

  const normalizedDisplayName = draftDisplayName.trim();

  const saveDisplayName = async () => {
    setSaveStatus("saving");

    const result = await updateProfile({
      display_name: normalizedDisplayName.length > 0 ? normalizedDisplayName : null,
    });

    if (result.isErr()) {
      log.w("Failed to update profile", [result.error]);
      setSaveStatus("error");
      return;
    }

    setDisplayName(normalizedDisplayName);
    setSaveStatus("saved");
    setIsEditingName(false);
    nameInputRef.current?.blur();
  };

  const handleNameAction = () => {
    if (isEditingName && saveStatus !== "saving") {
      void saveDisplayName();
      return;
    }

    setIsEditingName(true);
    requestAnimationFrame(() => nameInputRef.current?.focus());
  };

  const nameActionIcon =
    isEditingName || saveStatus === "saving" ? "save-outline" : "create-outline";
  const nameActionColor =
    saveStatus === "error" ? "#ef4444" : isEditingName ? COLORS.BITCOIN_ORANGE : iconColor;

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

          <View className="mt-8">
            <Label className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
              Name
            </Label>
            <View className="mt-3">
              <Input
                ref={nameInputRef}
                value={draftDisplayName}
                onChangeText={(value) => {
                  setDraftDisplayName(value);
                  setSaveStatus("idle");
                }}
                placeholder="Add a display name"
                editable={saveStatus !== "saving"}
                className="h-14 rounded-2xl py-0 pl-4 pr-14"
                style={{
                  borderColor: saveStatus === "error" ? "#ef4444" : `${colors.mutedForeground}24`,
                  backgroundColor: `${colors.card}CC`,
                  color: colors.foreground,
                }}
                maxLength={80}
                autoCapitalize="words"
                autoCorrect={false}
                returnKeyType="done"
                onFocus={() => {
                  if (!isEditingName) {
                    nameInputRef.current?.blur();
                  }
                }}
                onSubmitEditing={() => {
                  if (isEditingName) {
                    void saveDisplayName();
                  }
                }}
              />
              <Pressable
                onPress={handleNameAction}
                disabled={saveStatus === "saving"}
                accessibilityLabel={isEditingName ? "Save name" : "Edit name"}
                className="absolute right-2 top-2 h-10 w-10 items-center justify-center rounded-full"
              >
                <Icon name={nameActionIcon} size={22} color={nameActionColor} />
              </Pressable>
            </View>
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

          <View className="mt-8">
            <Text className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
              Emergency Email
            </Text>
            <View
              className="mt-3 overflow-hidden rounded-[18px] border"
              style={{
                borderColor: `${colors.mutedForeground}24`,
                backgroundColor: `${colors.card}CC`,
              }}
            >
              {isEmailVerified && emailAddress ? (
                <CopyRow label="Address" value={emailAddress} />
              ) : (
                <View className="px-4 py-4">
                  <Text className="text-base font-semibold text-foreground">
                    {isEmailVerified ? "Email alerts enabled" : "No emergency email set"}
                  </Text>
                  <Text className="mt-1 text-sm text-muted-foreground">
                    {isEmailVerified
                      ? "Your address will appear after the next server sync."
                      : "Optional alerts for urgent wallet communication."}
                  </Text>
                </View>
              )}
              {!isEmailVerified ? (
                <>
                  <View className="h-px bg-border" />
                  <Pressable
                    onPress={() => navigation.navigate("EmailVerification", { fromSettings: true })}
                    className="flex-row items-center justify-between px-4 py-4"
                  >
                    <Text className="text-base font-semibold text-foreground">
                      Add Emergency Email
                    </Text>
                    <Icon name="chevron-forward-outline" size={22} color={iconColor} />
                  </Pressable>
                </>
              ) : null}
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
