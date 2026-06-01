import { fromByteArray, toByteArray } from "react-native-quick-base64";

const decodeUtf8Fallback = (bytes: Uint8Array): string => {
  let result = "";

  for (let i = 0; i < bytes.length; ) {
    const byte1 = bytes[i++];

    if (byte1 < 0x80) {
      result += String.fromCodePoint(byte1);
      continue;
    }

    if ((byte1 & 0xe0) === 0xc0) {
      const byte2 = bytes[i++];
      result += String.fromCodePoint(((byte1 & 0x1f) << 6) | (byte2 & 0x3f));
      continue;
    }

    if ((byte1 & 0xf0) === 0xe0) {
      const byte2 = bytes[i++];
      const byte3 = bytes[i++];
      result += String.fromCodePoint(
        ((byte1 & 0x0f) << 12) | ((byte2 & 0x3f) << 6) | (byte3 & 0x3f),
      );
      continue;
    }

    if ((byte1 & 0xf8) === 0xf0) {
      const byte2 = bytes[i++];
      const byte3 = bytes[i++];
      const byte4 = bytes[i++];
      result += String.fromCodePoint(
        ((byte1 & 0x07) << 18) |
          ((byte2 & 0x3f) << 12) |
          ((byte3 & 0x3f) << 6) |
          (byte4 & 0x3f),
      );
      continue;
    }

    throw new Error("Invalid UTF-8 sequence");
  }

  return result;
};

const encodeUtf8Fallback = (value: string): Uint8Array => {
  const bytes: number[] = [];

  for (let i = 0; i < value.length; i += 1) {
    const codePoint = value.codePointAt(i);
    if (codePoint === undefined) {
      continue;
    }

    if (codePoint > 0xffff) {
      i += 1;
    }

    if (codePoint <= 0x7f) {
      bytes.push(codePoint);
    } else if (codePoint <= 0x7ff) {
      bytes.push(0xc0 | (codePoint >> 6), 0x80 | (codePoint & 0x3f));
    } else if (codePoint <= 0xffff) {
      bytes.push(
        0xe0 | (codePoint >> 12),
        0x80 | ((codePoint >> 6) & 0x3f),
        0x80 | (codePoint & 0x3f),
      );
    } else {
      bytes.push(
        0xf0 | (codePoint >> 18),
        0x80 | ((codePoint >> 12) & 0x3f),
        0x80 | ((codePoint >> 6) & 0x3f),
        0x80 | (codePoint & 0x3f),
      );
    }
  }

  return Uint8Array.from(bytes);
};

export const decodeBase64ToUtf8 = (value: string): string => {
  const bytes = toByteArray(value);

  if (typeof TextDecoder !== "undefined") {
    return new TextDecoder().decode(bytes);
  }

  return decodeUtf8Fallback(bytes);
};

export const encodeUtf8ToBase64 = (value: string): string => {
  const bytes = typeof TextEncoder !== "undefined" ? new TextEncoder().encode(value) : encodeUtf8Fallback(value);
  return fromByteArray(bytes);
};
