import React, { useEffect, useRef } from "react";
import { View } from "react-native";
import { Text } from "./ui/text";
import { useWalletStore } from "../store/walletStore";
import { useLoadWallet, useCloseWallet } from "../hooks/useWallet";
import { isWalletLoaded as isWalletLoadedNitro } from "react-native-nitro-ark";
import { NoahActivityIndicator } from "./ui/NoahActivityIndicator";
import { useBackgroundJobCoordination } from "~/hooks/useBackgroundJobCoordination";
import BiometricGate from "./BiometricGate";
import logger from "../lib/log";

const log = logger("WalletLoader");

interface WalletLoaderProps {
  children: React.ReactNode;
}

/**
 * WalletLoader gates the rest of the application behind wallet-initialisation.
 * It handles loading / unloading the wallet and shows a fallback spinner until
 * the wallet is ready.  By mounting the rest of the app only _after_ the
 * wallet is loaded we avoid unnecessary renders for components such as
 * `AppServices`.
 */
const WalletLoader: React.FC<WalletLoaderProps> = ({ children }) => {
  const { isInitialized, isWalletLoaded, walletError, isWalletSuspended } = useWalletStore();
  const { mutate: loadWallet, isPending: isWalletLoading } = useLoadWallet();
  const { mutate: closeWallet } = useCloseWallet();
  const { safelyExecuteWhenReady } = useBackgroundJobCoordination();
  const isLoadingRef = useRef(false);

  // kick-off the wallet load once onboarding is finished
  useEffect(() => {
    const checkAndLoadWallet = async () => {
      if (!isInitialized) return;
      if (isWalletSuspended) return;

      await safelyExecuteWhenReady(async () => {
        const actuallyLoaded = await isWalletLoadedNitro();

        // If the persisted state says loaded but wallet isn't actually loaded, fix the state
        if (isWalletLoaded && !actuallyLoaded) {
          useWalletStore.getState().setWalletUnloaded();
          return;
        }

        // If wallet isn't loaded, load it (prevent concurrent attempts)
        if (!actuallyLoaded && !isLoadingRef.current) {
          isLoadingRef.current = true;
          loadWallet(undefined, {
            onSettled: () => {
              isLoadingRef.current = false;
            },
          });
        }
      });
    };

    checkAndLoadWallet();
  }, [isInitialized, isWalletLoaded, isWalletSuspended, loadWallet, safelyExecuteWhenReady]);

  // tidy up by closing the wallet when the component unmounts
  useEffect(() => {
    return () => {
      // Fire and forget - don't block unmount
      // The wallet close is best-effort cleanup
      void (async () => {
        try {
          const actuallyLoaded = await isWalletLoadedNitro();
          if (actuallyLoaded) {
            closeWallet();
          }
        } catch (error) {
          // If we can't check, don't try to close to avoid errors
          log.w("Skipping wallet close due to check error:", [error]);
        }
      })();
    };
  }, [closeWallet]);

  if ((isWalletLoading || !isWalletLoaded) && !walletError && !isWalletSuspended) {
    return (
      <View className="flex-1 items-center justify-center bg-background">
        <NoahActivityIndicator size="large" />
        <Text className="mt-2.5 text-foreground">Loading Wallet...</Text>
      </View>
    );
  }

  return <BiometricGate>{children}</BiometricGate>;
};

export default WalletLoader;
