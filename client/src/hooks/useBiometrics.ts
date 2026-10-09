import { useGT } from "gt-react-native";
import * as LocalAuthentication from "expo-local-authentication";
import { useCallback } from "react";
import { useWalletStore } from "../store/walletStore";
import { err, ok, Result } from "neverthrow";

type BiometricAuthResult = Result<true, { cancelled: boolean; message: string }>;

export const useBiometrics = () => {
  const gt = useGT();
  const { isBiometricsEnabled } = useWalletStore();

  const authenticate = useCallback(
    async (promptMessage: string): Promise<BiometricAuthResult> => {
      const result = await LocalAuthentication.authenticateAsync({
        promptMessage,
        disableDeviceFallback: false,
      });

      if (result.success) {
        return ok(true);
      }

      return err({
        cancelled: result.error === "user_cancel",
        message: gt("Authentication failed"),
      });
    },
    [gt],
  );

  const authenticateIfEnabled = useCallback(
    async (promptMessage: string): Promise<BiometricAuthResult> => {
      if (!isBiometricsEnabled) {
        return ok(true);
      }
      return authenticate(promptMessage);
    },
    [isBiometricsEnabled, authenticate],
  );

  const checkAvailability = useCallback(async () => {
    const compatible = await LocalAuthentication.hasHardwareAsync();
    const enrolled = await LocalAuthentication.isEnrolledAsync();
    return { compatible, enrolled, available: compatible && enrolled };
  }, []);

  return {
    authenticate,
    authenticateIfEnabled,
    checkAvailability,
    isBiometricsEnabled,
  };
};
