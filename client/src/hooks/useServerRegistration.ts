import { useGT } from "gt-react-native";
import { useEffect } from "react";
import { useMutation } from "@tanstack/react-query";
import { useServerStore } from "~/store/serverStore";
import { performServerRegistration } from "~/lib/server";
import { useAppVersionCheck } from "./useAppVersionCheck";
import { useAlert } from "~/contexts/AlertProvider";
import logger from "~/lib/log";

const log = logger("useServerRegistration");

export const useServerRegistrationMutation = () => {
  const gt = useGT();
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: async () => {
      const result = await performServerRegistration(null);
      if (result.isErr()) {
        throw result.error;
      }
      return result.value;
    },
    onError: (error: Error) => {
      log.e("Failed to register with server", [error]);
      showAlert({
        title: gt("Registration Error"),
        description: gt("Failed to register with server. Please try again."),
      });
    },
  });
};

export const useServerRegistration = (isReady: boolean) => {
  const { isRegisteredWithServer } = useServerStore();
  const { isChecking } = useAppVersionCheck();

  useEffect(() => {
    const register = async () => {
      if (!isReady || isRegisteredWithServer || isChecking) {
        return;
      }

      await performServerRegistration(null);
    };

    register();
  }, [isRegisteredWithServer, isReady, isChecking]);

  return {
    isChecking,
  };
};
