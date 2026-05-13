import { useState, useMemo } from "react";
import { useBtcToUsdRate } from "./useMarketData";

export const useReceiveScreen = () => {
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState<"SATS" | "USD">("SATS");
  const { data: btcPrice } = useBtcToUsdRate();

  const toggleCurrency = () => {
    if (currency === "SATS") {
      if (btcPrice && amount) {
        const sats = parseInt(amount, 10);
        if (!isNaN(sats)) {
          setAmount(((sats * btcPrice) / 100000000).toFixed(2));
        }
      }
      setCurrency("USD");
    } else {
      if (btcPrice && amount) {
        const usd = parseFloat(amount);
        if (!isNaN(usd)) {
          setAmount(Math.round((usd / btcPrice) * 100000000).toString());
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

    return Math.round((amountFloat / btcPrice) * 100000000);
  }, [amount, currency, btcPrice]);

  return {
    amount,
    setAmount,
    currency,
    toggleCurrency,
    amountSat,
    btcPrice,
  };
};
