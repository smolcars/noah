import interBold from "../../assets/fonts/Inter-Bold.ttf";
import interMedium from "../../assets/fonts/Inter-Medium.ttf";
import interRegular from "../../assets/fonts/Inter-Regular.ttf";
import interSemiBold from "../../assets/fonts/Inter-SemiBold.ttf";

export const FONT_FAMILY = {
  regular: "Inter-Regular",
  medium: "Inter-Medium",
  semibold: "Inter-SemiBold",
  bold: "Inter-Bold",
} as const;

export const appFonts = {
  [FONT_FAMILY.regular]: interRegular,
  [FONT_FAMILY.medium]: interMedium,
  [FONT_FAMILY.semibold]: interSemiBold,
  [FONT_FAMILY.bold]: interBold,
};
