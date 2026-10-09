import { useGT } from "gt-react-native";
import { useMutation } from "@tanstack/react-query";
import { updateLightningIdentity } from "~/lib/api";
import logger from "~/lib/log";
import { useServerStore } from "~/store/serverStore";
import { useAlert } from "~/contexts/AlertProvider";

const log = logger("useUpdateLightningIdentity");

export const useUpdateLightningIdentity = (callbacks?: {
  onSuccess?: () => void;
  onError?: (error: Error) => void;
}) => {
  const gt = useGT();
  const updateLightningIdentityWrapper = async ({
    username,
    nostrPubkey,
  }: {
    username: string;
    nostrPubkey: string | null;
  }) => {
    const normalizedUsername = username.trim().toLowerCase();
    const normalizedNostrPubkey = nostrPubkey?.trim() || null;

    if (!normalizedUsername) {
      throw new Error(gt("Enter a username"));
    }
    if (normalizedNostrPubkey?.toLowerCase().startsWith("nsec1")) {
      throw new Error(gt("Never paste a Nostr private key. Enter an npub public key."));
    }
    if (normalizedNostrPubkey && !normalizedNostrPubkey.toLowerCase().startsWith("npub1")) {
      throw new Error(gt("Nostr public keys must use npub encoding"));
    }

    const result = await updateLightningIdentity({
      username: normalizedUsername,
      nostr_pubkey: normalizedNostrPubkey,
    });

    if (result.isErr()) {
      throw result.error;
    }

    return result.value;
  };
  const setLightningIdentity = useServerStore((state) => state.setLightningIdentity);
  const { showAlert } = useAlert();

  return useMutation({
    mutationFn: updateLightningIdentityWrapper,
    onSuccess: (identity) => {
      setLightningIdentity(identity.lightning_address, identity.nostr_pubkey);
      log.d("Successfully updated Lightning and NIP-05 settings");
      callbacks?.onSuccess?.();
    },
    onError: (error: Error) => {
      log.w("Failed to configure NIP-05 identity", [error]);
      callbacks?.onError?.(error);
      showAlert({
        title: gt("Error"),
        description: error.message,
      });
    },
  });
};
