import * as Slot from "@rn-primitives/slot";
import * as React from "react";
import { Platform, Text as RNText } from "react-native";
import { FONT_FAMILY } from "~/lib/fonts";
import { cn } from "~/lib/utils";

const TextClassContext = React.createContext<string | undefined>(undefined);
const TextFontFamilyContext = React.createContext<string | "native" | undefined>(undefined);

function getFontFamily(className: string, inheritedFontFamily?: string | "native") {
  if (className.match(/\bfont-mono\b/)) {
    return "native";
  }

  if (className.match(/\bfont-(black|extrabold|bold)\b/)) {
    return FONT_FAMILY.bold;
  }

  if (className.match(/\bfont-semibold\b/)) {
    return FONT_FAMILY.semibold;
  }

  if (className.match(/\bfont-medium\b/)) {
    return FONT_FAMILY.medium;
  }

  return inheritedFontFamily ?? FONT_FAMILY.regular;
}

function Text({
  className,
  asChild = false,
  textBreakStrategy,
  style,
  ...props
}: React.ComponentProps<typeof RNText> & {
  ref?: React.RefObject<RNText>;
  asChild?: boolean;
}) {
  const textClass = React.useContext(TextClassContext);
  const inheritedFontFamily = React.useContext(TextFontFamilyContext);
  const Component = asChild ? Slot.Text : RNText;
  const resolvedClassName = cn("text-base text-foreground web:select-text", textClass, className);
  const fontFamily = getFontFamily(resolvedClassName, inheritedFontFamily);
  const fontStyle =
    fontFamily && fontFamily !== "native" ? { fontFamily, fontWeight: "400" as const } : undefined;

  return (
    <TextFontFamilyContext.Provider value={fontFamily}>
      <Component
        className={resolvedClassName}
        textBreakStrategy={textBreakStrategy ?? (Platform.OS === "android" ? "simple" : undefined)}
        style={[fontStyle, style]}
        {...props}
      />
    </TextFontFamilyContext.Provider>
  );
}

export { Text, TextClassContext };
