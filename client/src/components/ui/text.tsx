import * as Slot from "@rn-primitives/slot";
import * as React from "react";
import { Platform, Text as RNText } from "react-native";
import { FONT_FAMILY } from "~/lib/fonts";
import { cn } from "~/lib/utils";

const TextClassContext = React.createContext<string | undefined>(undefined);

function getFontFamily(className: string) {
  if (className.match(/\bfont-mono\b/)) {
    return undefined;
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

  return FONT_FAMILY.regular;
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
  const Component = asChild ? Slot.Text : RNText;
  const resolvedClassName = cn("text-base text-foreground web:select-text", textClass, className);
  const fontFamily = getFontFamily(resolvedClassName);
  const fontStyle = fontFamily ? { fontFamily, fontWeight: "400" as const } : undefined;

  return (
    <Component
      className={resolvedClassName}
      textBreakStrategy={textBreakStrategy ?? (Platform.OS === "android" ? "simple" : undefined)}
      style={[fontStyle, style]}
      {...props}
    />
  );
}

export { Text, TextClassContext };
