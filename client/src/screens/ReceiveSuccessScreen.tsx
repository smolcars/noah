import React from "react";
import { useNavigation, useRoute } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { RouteProp } from "@react-navigation/native";
import type { HomeStackParamList } from "../Navigators";
import { ReceiveSuccess } from "../components/ReceiveSuccess";
import { useBtcToFiatRate } from "../hooks/useMarketData";
import { useBalance } from "~/hooks/useWallet";
import { calculateBalances } from "~/lib/balanceUtils";
import { useProfileStore } from "~/store/profileStore";

type NavigationProp = NativeStackNavigationProp<HomeStackParamList, "ReceiveSuccess">;
type ReceiveSuccessRouteProp = RouteProp<HomeStackParamList, "ReceiveSuccess">;

const ReceiveSuccessScreen = () => {
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<ReceiveSuccessRouteProp>();
  const { amountSat } = route.params;
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const { data: btcPrice } = useBtcToFiatRate();
  const { data: balance } = useBalance();
  const balances = balance ? calculateBalances(balance) : null;

  const handleDone = () => {
    navigation.navigate("HomeStack");
  };

  return (
    <ReceiveSuccess
      amountSat={amountSat}
      btcPrice={btcPrice}
      fiatCurrency={fiatCurrency}
      totalWalletBalanceSat={balances?.totalBalance}
      handleDone={handleDone}
    />
  );
};

export default ReceiveSuccessScreen;
