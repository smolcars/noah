import { useMutation } from "@tanstack/react-query";
import { updateLightningAddress } from "~/lib/api";
import logger from "~/lib/log";
import { useServerStore } from "~/store/serverStore";
import { isValidEmail } from "~/lib/utils";
import { useAlert } from "~/contexts/AlertProvider";

const log = logger("useUpdateLightningAddress");

const updateLightningAddressWrapper = async (newAddress: string) => {
  const normalizedAddress = newAddress.trim().toLowerCase();

  if (!isValidEmail(normalizedAddress)) {
    throw new Error("Invalid lightning address format");
  }

  const result = await updateLightningAddress({ ln_address: normalizedAddress });

  if (result.isErr()) {
    throw result.error;
  }

  return normalizedAddress;
};

export const useUpdateLightningAddress = (callbacks?: {
  onSuccess?: () => void;
  onError?: (error: Error) => void;
}) => {
  const { setLightningAddress } = useServerStore();
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: updateLightningAddressWrapper,
    onSuccess: (newAddress) => {
      setLightningAddress(newAddress);
      log.d("Successfully updated lightning address");
      callbacks?.onSuccess?.();
    },
    onError: (error: Error) => {
      log.w("Failed to update lightning address", [error]);
      callbacks?.onError?.(error);
      showAlert({
        title: "Error",
        description: error.message,
      });
    },
  });
};
