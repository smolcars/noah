import Constants from "expo-constants";
import * as Device from "expo-device";
import { File } from "expo-file-system";
import * as ImagePicker from "expo-image-picker";
import { TextInput as ExpoTextInput, type TextInputRef } from "@expo/ui";
import { Host as ComposeHost } from "@expo/ui/jetpack-compose";
import { Host as SwiftHost } from "@expo/ui/swift-ui";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Icon from "@react-native-vector-icons/ionicons";
import { AlertCircle, CheckCircle, ImagePlus, X } from "lucide-react-native";
import React, { useRef, useState } from "react";
import {
  Image,
  Keyboard,
  Linking,
  Platform,
  Pressable,
  ScrollView,
  TouchableWithoutFeedback,
  View,
} from "react-native";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { fromByteArray } from "react-native-quick-base64";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import type { SettingsStackParamList } from "~/Navigators";
import { TelegramBrandIcon, TELEGRAM_SUPPORT_URL } from "~/components/BrandIcons";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { Text } from "~/components/ui/text";
import { useIconColor, useTheme } from "~/hooks/useTheme";
import { submitSupportTicket } from "~/lib/api";
import Logger from "~/lib/log";
import { COLORS } from "~/lib/styleConstants";
import type { SupportTicketAttachment } from "~/types/serverTypes";

const log = Logger("FeedbackScreen");

const MAX_SCREENSHOT_BYTES = 5 * 1024 * 1024;

type SubmitState = "idle" | "submitting" | "success";

type SelectedScreenshot = {
  uri: string;
  filename: string;
  contentType: string;
  base64Data: string;
  size?: number;
};

const FeedbackScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList, "Feedback">>();
  const { colors, isDark } = useTheme();
  const iconColor = useIconColor();
  const tabBarHeight = useBottomTabBarHeight();
  const { bottom: safeBottomInset } = useSafeAreaInsets();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [screenshot, setScreenshot] = useState<SelectedScreenshot | null>(null);
  const [submitState, setSubmitState] = useState<SubmitState>("idle");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const nameInputRef = useRef<TextInputRef>(null);
  const emailInputRef = useRef<TextInputRef>(null);
  const subjectInputRef = useRef<TextInputRef>(null);
  const bodyInputRef = useRef<TextInputRef>(null);

  const dismissKeyboard = () => {
    Keyboard.dismiss();
    nameInputRef.current?.blur();
    emailInputRef.current?.blur();
    subjectInputRef.current?.blur();
    bodyInputRef.current?.blur();
  };

  const handleAddScreenshot = async () => {
    setErrorMessage(null);

    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ["images"],
      allowsEditing: true,
      quality: 0.8,
      base64: true,
    });

    if (result.canceled || !result.assets?.[0]) {
      return;
    }

    const asset = result.assets[0];
    if (asset.fileSize !== undefined && asset.fileSize > MAX_SCREENSHOT_BYTES) {
      setErrorMessage("Screenshot must be 5 MB or smaller.");
      return;
    }

    const base64Data = asset.base64 ?? (await readBase64FromFile(asset.uri));
    if (!base64Data) {
      setErrorMessage("Could not read the selected screenshot.");
      return;
    }

    const contentType = normalizeImageContentType(asset.mimeType);
    if (!contentType) {
      setErrorMessage("Screenshot must be a JPEG or PNG image.");
      return;
    }

    setScreenshot({
      uri: asset.uri,
      filename: asset.fileName ?? defaultScreenshotFilename(contentType),
      contentType,
      base64Data,
      size: asset.fileSize,
    });
  };

  const handleSubmit = async () => {
    const trimmedName = name.trim();
    const trimmedSubject = subject.trim();
    const trimmedBody = body.trim();
    if (!trimmedName || !trimmedSubject || !trimmedBody || submitState !== "idle") {
      return;
    }

    setSubmitState("submitting");
    setErrorMessage(null);

    const attachment: SupportTicketAttachment | null = screenshot
      ? {
          filename: screenshot.filename,
          content_type: screenshot.contentType,
          base64_data: screenshot.base64Data,
        }
      : null;

    const result = await submitSupportTicket({
      subject: trimmedSubject,
      body: trimmedBody,
      name: trimmedName,
      email: email.trim() || null,
      attachment,
      device_info: {
        app_version: Constants.expoConfig?.version || null,
        os_name: Device.osName,
        os_version: Device.osVersion,
        device_model: Device.modelName,
        device_manufacturer: Device.manufacturer,
      },
    });

    if (result.isErr()) {
      log.e("Failed to submit feedback", [result.error]);
      setSubmitState("idle");
      setErrorMessage("Failed to submit feedback. Please try again.");
      return;
    }

    setSubmitState("success");
  };

  const handleTelegramPress = () => {
    Linking.openURL(TELEGRAM_SUPPORT_URL);
  };

  const isSubmitDisabled =
    !name.trim() || !subject.trim() || !body.trim() || submitState !== "idle";

  return (
    <NoahSafeAreaView className="flex-1 bg-background" style={{ paddingBottom: 0 }}>
      <TouchableWithoutFeedback onPress={dismissKeyboard} accessible={false}>
        <ScrollView
          className="flex-1 px-4"
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          onScrollBeginDrag={dismissKeyboard}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={{
            flexGrow: 1,
            paddingBottom: safeBottomInset + tabBarHeight + 24,
          }}
        >
          <View className="flex-row items-center py-4">
            <Pressable
              onPress={() => navigation.goBack()}
              className="mr-4 h-10 w-10 items-center justify-center rounded-full bg-muted"
            >
              <Icon name="arrow-back-outline" size={22} color={iconColor} />
            </Pressable>
            <View className="flex-1">
              <Text className="text-2xl font-bold text-foreground">Send Feedback</Text>
              <Text className="mt-1 text-sm text-muted-foreground">
                Report a bug or share what would make Noah better.
              </Text>
            </View>
          </View>

          {submitState === "success" ? (
            <View className="flex-1 items-center justify-center px-3 py-10">
              <CheckCircle size={56} color={COLORS.SUCCESS} />
              <Text className="mt-5 text-center text-2xl font-bold text-foreground">Thank You</Text>
              <Text className="mt-2 text-center text-base text-muted-foreground">
                Your feedback was sent to the Noah team.
              </Text>
              <NativeNoahButton
                label="Done"
                className="mt-8"
                fullWidth
                onPress={() => navigation.goBack()}
              />
            </View>
          ) : (
            <View className="gap-5 pb-2">
              <Pressable onPress={handleTelegramPress} className="flex-row items-center gap-3">
                <TelegramBrandIcon size={32} />
                <Text className="flex-1 text-sm text-muted-foreground">
                  You can also join our Telegram chat for support and feedback.
                </Text>
              </Pressable>

              {errorMessage ? (
                <View className="flex-row items-center gap-3 rounded-md border border-red-900 bg-red-950/40 p-3">
                  <AlertCircle size={20} color="#ef4444" />
                  <Text className="flex-1 text-sm text-red-300">{errorMessage}</Text>
                </View>
              ) : null}

              <InputGroup label="Name">
                <ExpoInputHost height={48}>
                  <ExpoTextInput
                    ref={nameInputRef}
                    defaultValue={name}
                    onChangeText={setName}
                    placeholder="Your name"
                    placeholderTextColor={colors.mutedForeground}
                    autoCorrect={false}
                    editable={submitState === "idle"}
                    textStyle={{
                      color: colors.foreground,
                      fontSize: 16,
                    }}
                    style={{
                      height: 48,
                      paddingHorizontal: 14,
                      borderWidth: 1,
                      borderRadius: 8,
                      borderColor: colors.border,
                      backgroundColor: isDark ? "#18181b" : "#ffffff",
                    }}
                  />
                </ExpoInputHost>
              </InputGroup>

              <InputGroup label="Email (Optional)">
                <ExpoInputHost height={48}>
                  <ExpoTextInput
                    ref={emailInputRef}
                    defaultValue={email}
                    onChangeText={setEmail}
                    placeholder="you@example.com"
                    placeholderTextColor={colors.mutedForeground}
                    keyboardType="email-address"
                    autoCapitalize="none"
                    autoCorrect={false}
                    editable={submitState === "idle"}
                    textStyle={{
                      color: colors.foreground,
                      fontSize: 16,
                    }}
                    style={{
                      height: 48,
                      paddingHorizontal: 14,
                      borderWidth: 1,
                      borderRadius: 8,
                      borderColor: colors.border,
                      backgroundColor: isDark ? "#18181b" : "#ffffff",
                    }}
                  />
                </ExpoInputHost>
              </InputGroup>

              <InputGroup label="Subject">
                <ExpoInputHost height={48}>
                  <ExpoTextInput
                    ref={subjectInputRef}
                    defaultValue={subject}
                    onChangeText={setSubject}
                    placeholder="Short summary"
                    placeholderTextColor={colors.mutedForeground}
                    maxLength={150}
                    editable={submitState === "idle"}
                    textStyle={{
                      color: colors.foreground,
                      fontSize: 16,
                    }}
                    style={{
                      height: 48,
                      paddingHorizontal: 14,
                      borderWidth: 1,
                      borderRadius: 8,
                      borderColor: colors.border,
                      backgroundColor: isDark ? "#18181b" : "#ffffff",
                    }}
                  />
                </ExpoInputHost>
              </InputGroup>

              <InputGroup label="Body">
                <ExpoInputHost height={150}>
                  <ExpoTextInput
                    ref={bodyInputRef}
                    defaultValue={body}
                    onChangeText={setBody}
                    placeholder="Describe the bug or share your feedback..."
                    placeholderTextColor={colors.mutedForeground}
                    multiline
                    numberOfLines={6}
                    maxLength={60000}
                    editable={submitState === "idle"}
                    textStyle={{
                      color: colors.foreground,
                      fontSize: 16,
                      lineHeight: 22,
                    }}
                    style={{
                      height: 150,
                      padding: 14,
                      borderWidth: 1,
                      borderRadius: 8,
                      borderColor: colors.border,
                      backgroundColor: isDark ? "#18181b" : "#ffffff",
                    }}
                  />
                </ExpoInputHost>
              </InputGroup>

              {screenshot ? (
                <View className="gap-1.5">
                  <View className="flex-row items-center justify-between">
                    <Text className="text-xs font-semibold uppercase text-muted-foreground">
                      Screenshot
                    </Text>
                    {screenshot.size ? (
                      <Text className="text-xs text-muted-foreground">
                        {formatBytes(screenshot.size)}
                      </Text>
                    ) : null}
                  </View>
                  <View className="relative overflow-hidden rounded-md border border-border">
                    <Image
                      source={{ uri: screenshot.uri }}
                      className="h-28 w-full"
                      resizeMode="cover"
                    />
                    <Pressable
                      onPress={() => setScreenshot(null)}
                      className="absolute right-2 top-2 h-9 w-9 items-center justify-center rounded-full bg-black/70"
                      disabled={submitState === "submitting"}
                    >
                      <X size={18} color="#ffffff" />
                    </Pressable>
                  </View>
                </View>
              ) : (
                <Pressable
                  onPress={handleAddScreenshot}
                  className="h-12 flex-row items-center justify-center gap-2 rounded-md border border-border bg-card"
                  disabled={submitState !== "idle"}
                >
                  <ImagePlus size={20} color={COLORS.BITCOIN_ORANGE} />
                  <Text
                    className="text-base font-semibold"
                    style={{ color: COLORS.BITCOIN_ORANGE }}
                  >
                    Add Screenshot
                  </Text>
                </Pressable>
              )}

              <NativeNoahButton
                label="Send Feedback"
                loadingLabel="Sending..."
                isLoading={submitState === "submitting"}
                disabled={isSubmitDisabled}
                fullWidth
                onPress={handleSubmit}
              />
            </View>
          )}
        </ScrollView>
      </TouchableWithoutFeedback>
    </NoahSafeAreaView>
  );
};

const InputGroup = ({ label, children }: { label: string; children: React.ReactNode }) => (
  <View className="gap-2">
    <Text className="text-xs font-semibold uppercase text-muted-foreground">{label}</Text>
    {children}
  </View>
);

const ExpoInputHost = ({ height, children }: { height: number; children: React.ReactNode }) => {
  const { colorScheme } = useTheme();
  const style = { width: "100%", height } as const;

  if (Platform.OS === "ios") {
    return (
      <SwiftHost colorScheme={colorScheme} seedColor={COLORS.BITCOIN_ORANGE} style={style}>
        {children}
      </SwiftHost>
    );
  }

  if (Platform.OS === "android") {
    return (
      <ComposeHost colorScheme={colorScheme} seedColor={COLORS.BITCOIN_ORANGE} style={style}>
        {children}
      </ComposeHost>
    );
  }

  return <View style={style}>{children}</View>;
};

const normalizeImageContentType = (contentType?: string | null): string | null => {
  if (contentType === "image/jpeg" || contentType === "image/png") {
    return contentType;
  }

  return null;
};

const defaultScreenshotFilename = (contentType: string): string =>
  contentType === "image/png" ? "screenshot.png" : "screenshot.jpg";

const readBase64FromFile = async (uri: string): Promise<string | null> => {
  try {
    const file = new File(uri);
    const bytes = await file.bytes();
    return uint8ArrayToBase64(bytes);
  } catch (error) {
    log.w("Failed to read screenshot bytes", [error]);
    return null;
  }
};

const uint8ArrayToBase64 = (bytes: Uint8Array): string => {
  return fromByteArray(bytes);
};

const formatBytes = (bytes: number): string => {
  if (bytes < 1024 * 1024) {
    return `${Math.ceil(bytes / 1024)} KB`;
  }

  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

export default FeedbackScreen;
