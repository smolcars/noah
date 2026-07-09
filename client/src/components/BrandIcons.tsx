import Svg, { Circle, Path, type SvgProps } from "react-native-svg";

export const TELEGRAM_SUPPORT_URL = "https://t.me/blixtwallet/605026";
export const GITHUB_URL = "https://github.com/smolcars/noah";

type BrandIconProps = SvgProps & {
  size?: number;
};

export const TelegramBrandIcon = ({ size = 24, ...props }: BrandIconProps) => (
  <Svg width={size} height={size} viewBox="0 0 240 240" {...props}>
    <Circle cx="120" cy="120" r="120" fill="#26A5E4" />
    <Path
      fill="#ffffff"
      d="M178.2 77.4 157 177.3c-1.6 7.1-5.8 8.9-11.7 5.5l-32.4-23.9-15.6 15c-1.7 1.7-3.2 3.2-6.6 3.2l2.3-33 60.1-54.3c2.6-2.3-.6-3.6-4-1.3l-74.3 46.8-32-10c-7-2.2-7.1-7 1.5-10.3l125.1-48.2c5.8-2.1 10.9 1.4 8.8 10.6Z"
    />
  </Svg>
);

export const GitHubBrandIcon = ({
  size = 24,
  color = "#181717",
  ...props
}: BrandIconProps & { color?: string }) => (
  <Svg width={size} height={size} viewBox="0 0 24 24" {...props}>
    <Path
      fill={color}
      d="M12 .5C5.7.5.8 5.4.8 11.7c0 5 3.2 9.2 7.7 10.7.6.1.8-.2.8-.5v-1.9c-3.1.7-3.8-1.5-3.8-1.5-.5-1.3-1.2-1.6-1.2-1.6-1-.7.1-.7.1-.7 1.1.1 1.7 1.2 1.7 1.2 1 .1.7 2.6 2.6 1.9.1-.7.4-1.2.7-1.5-2.5-.3-5.1-1.2-5.1-5.6 0-1.2.4-2.2 1.2-3-.1-.3-.5-1.4.1-3 0 0 .9-.3 3.1 1.2.9-.2 1.9-.4 2.8-.4 1 0 1.9.1 2.8.4 2.2-1.5 3.1-1.2 3.1-1.2.6 1.6.2 2.7.1 3 .7.8 1.2 1.8 1.2 3 0 4.3-2.6 5.3-5.1 5.6.4.3.8 1 .8 2.1v3.1c0 .3.2.6.8.5 4.5-1.5 7.7-5.7 7.7-10.7C23.2 5.4 18.3.5 12 .5Z"
    />
  </Svg>
);
