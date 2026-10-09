import type { useGT } from "gt-react-native";

export type Translate = ReturnType<typeof useGT>;

// Pure presentation helpers can still render source text without a React provider.
export const sourceText: Translate = (text, options) =>
  text.replace(/\{(\w+)\}/g, (placeholder, key: string) =>
    options?.[key] == null ? placeholder : String(options[key]),
  );
