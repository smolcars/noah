import React, { useState, useEffect, useCallback } from "react";
import { View, Pressable, Keyboard } from "react-native";
import { useNavigation, useRoute } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { RouteProp } from "@react-navigation/native";
import {
  CodeField,
  Cursor,
  useBlurOnFulfill,
  useClearByFocusCell,
} from "react-native-confirmation-code-field";
import { Input } from "../components/ui/input";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import { useAlert } from "~/contexts/AlertProvider";
import { sendVerificationEmail, verifyEmail } from "~/lib/api";
import { useServerRegistrationMutation } from "~/hooks/useServerRegistration";
import { useServerStore } from "~/store/serverStore";
import type { OnboardingStackParamList, SettingsStackParamList } from "../Navigators";
import { shouldUseUnifiedPush } from "~/constants";
import logger from "~/lib/log";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";

type EmailVerificationParams = {
  fromSettings?: boolean;
};

type EmailVerificationScreenRouteProp = RouteProp<
  { EmailVerification: EmailVerificationParams | undefined },
  "EmailVerification"
>;

const log = logger("EmailVerificationScreen");

const CELL_COUNT = 6;

const EmailVerificationScreen = () => {
  const navigation =
    useNavigation<NativeStackNavigationProp<OnboardingStackParamList & SettingsStackParamList>>();
  const route = useRoute<EmailVerificationScreenRouteProp>();
  const { showAlert } = useAlert();
  const { fromSettings } = route.params || {};

  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [isSendingCode, setIsSendingCode] = useState(false);
  const [isVerifying, setIsVerifying] = useState(false);
  const [codeSent, setCodeSent] = useState(false);

  const {
    emailAddress: currentEmailAddress,
    isEmailVerified,
    isRegisteredWithServer,
    setEmailAddress,
    setEmailVerified,
  } = useServerStore();
  const registerMutation = useServerRegistrationMutation();
  const isChangingEmail = Boolean(fromSettings && isEmailVerified);

  useEffect(() => {
    if (fromSettings || isRegisteredWithServer) {
      return;
    }
    log.i("Registering with server before email verification...");
    registerMutation.mutate(undefined, {
      onError: () => navigation.goBack(),
    });
  }, []);

  const ref = useBlurOnFulfill({ value: code, cellCount: CELL_COUNT });
  const [props, getCellOnLayoutHandler] = useClearByFocusCell({
    value: code,
    setValue: setCode,
  });

  const navigateToNextOnboardingStep = useCallback(() => {
    if (shouldUseUnifiedPush()) {
      navigation.navigate("UnifiedPush", { fromOnboarding: true });
    } else {
      navigation.navigate("LightningAddress", { fromOnboarding: true });
    }
  }, [navigation]);

  const handleSkip = () => {
    navigateToNextOnboardingStep();
  };

  const isValidEmail = (emailInput: string) => {
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return emailRegex.test(emailInput);
  };

  const handleSendCode = async () => {
    const trimmedEmail = email.trim();
    if (!isValidEmail(trimmedEmail)) {
      showAlert({
        title: "Invalid Email",
        description: "Please enter a valid email address.",
      });
      return;
    }

    Keyboard.dismiss();
    setIsSendingCode(true);
    setEmail(trimmedEmail);

    const result = await sendVerificationEmail({ email: trimmedEmail });

    if (result.isOk()) {
      if (result.value.message === "Email already verified") {
        log.i("Email is already verified on server, syncing local state");
        setEmailAddress(result.value.email ?? trimmedEmail);
        setEmailVerified(true);
        if (fromSettings) {
          showAlert({
            title: "Already Verified",
            description: "This email is already verified.",
          });
          navigation.goBack();
        } else {
          navigateToNextOnboardingStep();
        }
        setIsSendingCode(false);
        return;
      }
      setCodeSent(true);
      log.i("Verification code sent to", [email]);
    } else {
      log.e("Failed to send verification code", [result.error]);
      showAlert({
        title: "Error",
        description: result.error.message || "Failed to send verification code. Please try again.",
      });
    }

    setIsSendingCode(false);
  };

  const handleVerifyCode = async () => {
    if (code.length !== CELL_COUNT) {
      showAlert({
        title: "Invalid Code",
        description: "Please enter the complete 6-digit code.",
      });
      return;
    }

    Keyboard.dismiss();
    setIsVerifying(true);

    const result = await verifyEmail({ code });

    if (result.isOk() && result.value.success) {
      log.i("Email verified successfully");
      setEmailAddress(result.value.email ?? email.trim());
      setEmailVerified(true);

      if (fromSettings) {
        showAlert({
          title: isChangingEmail ? "Email Updated" : "Email Verified",
          description: isChangingEmail
            ? "Your emergency email has been updated."
            : "Your email has been verified successfully.",
        });
        navigation.goBack();
      } else {
        navigateToNextOnboardingStep();
      }
    } else {
      const errorMessage = result.isErr()
        ? result.error.message
        : "Invalid or expired verification code.";
      log.e("Failed to verify email", [errorMessage]);
      showAlert({
        title: "Verification Failed",
        description: errorMessage,
      });
      setCode("");
    }

    setIsVerifying(false);
  };

  const handleResendCode = async () => {
    setCode("");
    await handleSendCode();
  };

  if (registerMutation.isPending) {
    return (
      <NoahSafeAreaView className="flex-1 bg-background">
        <View className="flex-1 justify-center items-center p-4">
          <NoahActivityIndicator size="large" />
          <Text className="text-muted-foreground mt-4">Setting up your account...</Text>
        </View>
      </NoahSafeAreaView>
    );
  }

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="p-4">
        <View className="flex-row items-center mb-8">
          <NativeNoahBackButton
            onPress={() => navigation.goBack()}
            className="mr-3"
            testID="email-verification-back-button"
          />
          <Text className="text-2xl font-bold text-foreground">
            {isChangingEmail ? "Change Emergency Email" : "Emergency Email"}
          </Text>
        </View>

        {!codeSent ? (
          <>
            <Text className="text-muted-foreground mb-6">
              {isChangingEmail
                ? `Your current email${
                    currentEmailAddress ? `, ${currentEmailAddress},` : ""
                  } will stay active until the new email is verified.`
                : "Email is optional. Noah uses it only for urgent wallet safety messages, such as when your VTXOs are close to expiring and you need to come online to refresh them."}
            </Text>

            <View className="bg-card rounded-2xl border border-border p-5 space-y-5">
              <View>
                <Text className="text-xs uppercase tracking-widest text-muted-foreground mb-2">
                  Email Address
                </Text>
                <Input
                  value={email}
                  onChangeText={setEmail}
                  className="h-16 rounded-2xl border border-border bg-background/90 px-4 text-lg leading-6 text-foreground"
                  placeholder={isChangingEmail ? "new@email.com" : "your@email.com"}
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType="email-address"
                  autoComplete="email"
                />
              </View>
            </View>

            <NativeNoahButton
              label={isChangingEmail ? "Send Code to New Email" : "Send Verification Code"}
              onPress={handleSendCode}
              className="mt-8"
              isLoading={isSendingCode}
              loadingLabel="Sending..."
              disabled={!email || isSendingCode}
              fullWidth
            />
            {!fromSettings && (
              <Pressable onPress={handleSkip} className="mt-5 items-center">
                <Text className="text-muted-foreground font-semibold">Continue without email</Text>
              </Pressable>
            )}
          </>
        ) : (
          <>
            <Text className="text-muted-foreground mb-2">
              {isChangingEmail
                ? "We sent a 6-digit verification code to your new email:"
                : "We sent a 6-digit verification code to:"}
            </Text>
            <Text className="text-foreground font-semibold mb-6">{email}</Text>

            <View className="bg-card rounded-2xl border border-border p-5">
              <Text className="text-xs uppercase tracking-widest text-muted-foreground mb-4 text-center">
                Enter Verification Code
              </Text>
              <CodeField
                ref={ref}
                {...props}
                value={code}
                onChangeText={setCode}
                cellCount={CELL_COUNT}
                keyboardType="number-pad"
                textContentType="oneTimeCode"
                autoComplete="one-time-code"
                testID="verification-code-input"
                accessibilityLabel="verification-code-input"
                autoFocus={true}
                renderCell={({ index, symbol, isFocused }) => (
                  <View
                    key={index}
                    className={`w-12 h-14 border-2 rounded-xl justify-center items-center mx-1 ${
                      isFocused ? "border-primary" : "border-border"
                    }`}
                    onLayout={getCellOnLayoutHandler(index)}
                  >
                    <Text className="text-2xl text-foreground text-center">
                      {symbol || (isFocused ? <Cursor /> : null)}
                    </Text>
                  </View>
                )}
              />
            </View>

            <NativeNoahButton
              label={isChangingEmail ? "Update Email" : "Verify Email"}
              onPress={handleVerifyCode}
              className="mt-8"
              isLoading={isVerifying}
              loadingLabel="Verifying..."
              disabled={code.length !== CELL_COUNT || isVerifying}
              fullWidth
            />

            <View className="mt-6 items-center">
              <Text className="text-muted-foreground mb-2">Didn't receive the code?</Text>
              <Pressable onPress={handleResendCode} disabled={isSendingCode}>
                <Text className="text-primary font-semibold">
                  {isSendingCode ? "Sending..." : "Resend Code"}
                </Text>
              </Pressable>
            </View>

            <View className="mt-4 items-center">
              <Pressable
                onPress={() => {
                  setCodeSent(false);
                  setCode("");
                }}
              >
                <Text className="text-muted-foreground">Change email address</Text>
              </Pressable>
            </View>
          </>
        )}

        {codeSent && (
          <Text className="text-xs text-muted-foreground text-center mt-8">
            The verification code will expire in 10 minutes.
          </Text>
        )}
      </View>
    </NoahSafeAreaView>
  );
};

export default EmailVerificationScreen;
