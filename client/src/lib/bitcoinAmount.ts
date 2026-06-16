export const BITCOIN_AMOUNT_UNITS = ["bip177", "sats"] as const;

export type BitcoinAmountUnit = (typeof BITCOIN_AMOUNT_UNITS)[number];

type BitcoinAmountUnitInfo = {
  unit: BitcoinAmountUnit;
  title: string;
  value: string;
  description: string;
};

const BITCOIN_AMOUNT_UNIT_INFO: Record<BitcoinAmountUnit, BitcoinAmountUnitInfo> = {
  bip177: {
    unit: "bip177",
    title: "BIP177",
    value: "₿\u00A01,234",
    description: "Show bitcoin amounts with the Bitcoin symbol.",
  },
  sats: {
    unit: "sats",
    title: "Sats",
    value: "1,234 sats",
    description: "Show bitcoin amounts in satoshis.",
  },
};

export const isBitcoinAmountUnit = (value: unknown): value is BitcoinAmountUnit =>
  typeof value === "string" && BITCOIN_AMOUNT_UNITS.includes(value as BitcoinAmountUnit);

export const getBitcoinAmountUnitInfo = (unit: BitcoinAmountUnit): BitcoinAmountUnitInfo =>
  BITCOIN_AMOUNT_UNIT_INFO[unit];

export const formatBip177 = (sats: number): string => {
  return `₿\u00A0${sats.toLocaleString()}`;
};

export const formatSatsAmount = (sats: number): string => {
  return `${sats.toLocaleString()} sats`;
};

export const formatBitcoinAmount = (sats: number, unit: BitcoinAmountUnit): string => {
  if (unit === "sats") {
    return formatSatsAmount(sats);
  }

  return formatBip177(sats);
};
