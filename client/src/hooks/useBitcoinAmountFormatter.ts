import { useLocale } from "gt-react-native";
import { formatBitcoinAmount } from "~/lib/bitcoinAmount";
import { useProfileStore } from "~/store/profileStore";

export const useBitcoinAmountFormatter = () => {
  const locale = useLocale();
  const bitcoinAmountUnit = useProfileStore((state) => state.bitcoinAmountUnit);

  return (sats: number) => formatBitcoinAmount(sats, bitcoinAmountUnit, locale);
};

export const useBitcoinAmountUnit = () => useProfileStore((state) => state.bitcoinAmountUnit);
