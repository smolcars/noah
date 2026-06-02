import { useEffect } from "react";
import { CheckCircle } from "lucide-react-native";
import { StatusBannerStrip } from "~/components/StatusBannerStrip";
import { formatAutoBoardThreshold } from "~/lib/autoBoarding";
import { useTransactionStore } from "~/store/transactionStore";

const AUTO_BOARD_SUCCESS_BANNER_MS = 5_000;

export const AutoBoardingStatusBanner = () => {
  const { autoBoardSuccessBanner, clearAutoBoardSuccessBanner } = useTransactionStore();

  useEffect(() => {
    if (!autoBoardSuccessBanner) {
      return undefined;
    }

    const elapsedMs = Date.now() - autoBoardSuccessBanner.createdAt;
    const remainingMs = AUTO_BOARD_SUCCESS_BANNER_MS - elapsedMs;

    if (remainingMs <= 0) {
      clearAutoBoardSuccessBanner();
      return undefined;
    }

    const timeoutId = setTimeout(() => {
      clearAutoBoardSuccessBanner();
    }, remainingMs);

    return () => clearTimeout(timeoutId);
  }, [autoBoardSuccessBanner, clearAutoBoardSuccessBanner]);

  if (!autoBoardSuccessBanner) {
    return null;
  }

  if (Date.now() - autoBoardSuccessBanner.createdAt > AUTO_BOARD_SUCCESS_BANNER_MS) {
    return null;
  }

  return (
    <StatusBannerStrip
      className="mx-4 mt-3 mb-1"
      title="Boarding completed"
      message={`${formatAutoBoardThreshold(autoBoardSuccessBanner.netBoardAmountSat)} available in Ark`}
      icon={<CheckCircle size={16} color="#22c55e" />}
      tone="success"
    />
  );
};
