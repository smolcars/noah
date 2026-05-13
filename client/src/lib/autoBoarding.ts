import type { BarkArkInfo } from "react-native-nitro-ark";

export const AUTO_BOARD_FLOOR_AMOUNT = 20_000;

export const getAutoBoardThreshold = (arkInfo: Pick<BarkArkInfo, "min_board_amount">): number =>
  Math.max(arkInfo.min_board_amount, AUTO_BOARD_FLOOR_AMOUNT);

export const formatAutoBoardThreshold = (amountSat: number): string =>
  `${amountSat.toLocaleString()} sats`;
