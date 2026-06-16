import { formatBitcoinAmount } from "~/lib/bitcoinAmount";
import { useProfileStore } from "~/store/profileStore";

export const useBitcoinAmountFormatter = () => {
  const bitcoinAmountUnit = useProfileStore((state) => state.bitcoinAmountUnit);

  return (sats: number) => formatBitcoinAmount(sats, bitcoinAmountUnit);
};

export const useBitcoinAmountUnit = () => useProfileStore((state) => state.bitcoinAmountUnit);
