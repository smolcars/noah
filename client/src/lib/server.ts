import { registerWithServer } from "~/lib/api";
import * as Device from "expo-device";
import logger from "~/lib/log";
import { useServerStore } from "~/store/serverStore";
import { useProfileStore } from "~/store/profileStore";
import { type Result, err } from "neverthrow";
import { RegisterResponse } from "~/types/serverTypes";
import Constants from "expo-constants";
import { peakAddress } from "~/lib/paymentsApi";

const log = logger("server");

export const performServerRegistration = async (
  ln_address: string | null,
): Promise<Result<RegisterResponse, Error>> => {
  const { setRegisteredWithServer, setEmailVerified } = useServerStore.getState();
  const { setDisplayName } = useProfileStore.getState();

  const addressResult = await peakAddress(0);
  if (addressResult.isErr()) {
    log.e("Failed to generate Ark address for registration", [addressResult.error]);
    return err(addressResult.error);
  }
  const ark_address = addressResult.value.address;

  // Register with server and pass user device information.
  const result = await registerWithServer({
    device_info: {
      app_version: Constants.expoConfig?.version || null,
      os_name: Device.osName,
      os_version: Device.osVersion,
      device_model: Device.modelName,
      device_manufacturer: Device.manufacturer,
    },
    ln_address,
    ark_address,
  });

  if (result.isErr()) {
    log.w("Failed to register with server", [result.error]);
    return result;
  }

  const { lightning_address, display_name, is_email_verified } = result.value;
  log.d("Successfully registered with server", [is_email_verified]);
  setRegisteredWithServer(true, lightning_address, true);
  setDisplayName(display_name ?? "");
  setEmailVerified(is_email_verified);
  return result;
};
