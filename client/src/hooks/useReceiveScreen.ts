import { useCallback, useMemo, useState } from "react";
import { useBtcToFiatRate } from "./useMarketData";
import { fiatToSats, satsToFiat } from "~/lib/fiatCurrency";
import { useProfileStore } from "~/store/profileStore";

export const useReceiveScreen = () => {
  const [amount, setAmountValue] = useState("");
  const [canonicalAmountSat, setCanonicalAmountSat] = useState<number | null>(null);
  const [currency, setCurrency] = useState<"SATS" | "FIAT">("SATS");
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const { data: btcPrice } = useBtcToFiatRate();

  const setAmount = useCallback((value: string) => {
    setAmountValue(value);
    setCanonicalAmountSat(null);
  }, []);

  const amountSat = useMemo(() => {
    if (canonicalAmountSat !== null) {
      return canonicalAmountSat;
    }

    const amountFloat = parseFloat(amount);
    if (isNaN(amountFloat)) return 0;

    if (currency === "SATS") {
      return Math.round(amountFloat);
    }

    if (!btcPrice) return 0;

    return fiatToSats(amountFloat, btcPrice);
  }, [amount, btcPrice, canonicalAmountSat, currency]);

  const toggleCurrency = () => {
    if (currency === "SATS") {
      if (btcPrice && amount) {
        if (amountSat > 0) {
          setAmountValue(satsToFiat(amountSat, btcPrice, fiatCurrency));
          setCanonicalAmountSat(amountSat);
        }
      }
      setCurrency("FIAT");
    } else {
      if (amount) {
        setAmountValue(amountSat.toString());
        setCanonicalAmountSat(amountSat);
      }
      setCurrency("SATS");
    }
  };

  return {
    amount,
    setAmount,
    currency,
    setCurrency,
    toggleCurrency,
    amountSat,
    btcPrice,
    fiatCurrency,
  };
};
