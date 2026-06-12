import { useState, useMemo } from "react";
import { useBtcToFiatRate } from "./useMarketData";
import { fiatToSats, satsToFiat } from "~/lib/fiatCurrency";
import { useProfileStore } from "~/store/profileStore";

export const useReceiveScreen = () => {
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState<"SATS" | "FIAT">("SATS");
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const { data: btcPrice } = useBtcToFiatRate();

  const toggleCurrency = () => {
    if (currency === "SATS") {
      if (btcPrice && amount) {
        const sats = parseInt(amount, 10);
        if (!isNaN(sats)) {
          setAmount(satsToFiat(sats, btcPrice, fiatCurrency));
        }
      }
      setCurrency("FIAT");
    } else {
      if (btcPrice && amount) {
        const fiatAmount = parseFloat(amount);
        if (!isNaN(fiatAmount)) {
          setAmount(fiatToSats(fiatAmount, btcPrice).toString());
        }
      }
      setCurrency("SATS");
    }
  };

  const amountSat = useMemo(() => {
    const amountFloat = parseFloat(amount);
    if (isNaN(amountFloat)) return 0;

    if (currency === "SATS") {
      return Math.round(amountFloat);
    }

    if (!btcPrice) return 0;

    return fiatToSats(amountFloat, btcPrice);
  }, [amount, currency, btcPrice, fiatCurrency]);

  return {
    amount,
    setAmount,
    currency,
    toggleCurrency,
    amountSat,
    btcPrice,
    fiatCurrency,
  };
};
