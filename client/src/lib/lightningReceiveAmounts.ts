import { mmkv } from "./mmkv";

const KEY_PREFIX = "lightning_receive_amount:";
const ENTRY_TTL_MS = 24 * 60 * 60 * 1000;

type LightningReceiveAmountEntry = {
  amountSat: number;
  createdAt: number;
};

function keyForPaymentHash(paymentHash: string) {
  return `${KEY_PREFIX}${paymentHash}`;
}

function parseEntry(value: string | undefined): LightningReceiveAmountEntry | null {
  if (!value) {
    return null;
  }

  try {
    const parsed = JSON.parse(value) as Partial<LightningReceiveAmountEntry>;
    if (typeof parsed.amountSat !== "number" || typeof parsed.createdAt !== "number") {
      return null;
    }

    return {
      amountSat: parsed.amountSat,
      createdAt: parsed.createdAt,
    };
  } catch {
    return null;
  }
}

function isExpired(entry: LightningReceiveAmountEntry, now = Date.now()) {
  return now - entry.createdAt > ENTRY_TTL_MS;
}

export function pruneStoredLightningReceiveAmounts() {
  const now = Date.now();
  for (const key of mmkv.getAllKeys()) {
    if (!key.startsWith(KEY_PREFIX)) {
      continue;
    }

    const entry = parseEntry(mmkv.getString(key));
    if (!entry || isExpired(entry, now)) {
      mmkv.remove(key);
    }
  }
}

export function storeLightningReceiveAmount(paymentHash: string, amountSat: number) {
  pruneStoredLightningReceiveAmounts();
  mmkv.set(
    keyForPaymentHash(paymentHash),
    JSON.stringify({
      amountSat,
      createdAt: Date.now(),
    } satisfies LightningReceiveAmountEntry),
  );
}

export function getStoredLightningReceiveAmount(paymentHash: string): number | null {
  const key = keyForPaymentHash(paymentHash);
  const entry = parseEntry(mmkv.getString(key));

  if (!entry || isExpired(entry)) {
    mmkv.remove(key);
    return null;
  }

  return entry.amountSat;
}

export function removeStoredLightningReceiveAmount(paymentHash: string) {
  mmkv.remove(keyForPaymentHash(paymentHash));
}
