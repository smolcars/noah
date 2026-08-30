import Icon from "@react-native-vector-icons/ionicons";
import type { ReactNode } from "react";
import { AccessibilityInfo, Pressable, View } from "react-native";

import { ArkIcon } from "~/lib/icons/Ark";
import { LightningIcon } from "~/lib/icons/Lightning";
import { OnchainIcon } from "~/lib/icons/Onchain";
import { useCopyToClipboard } from "~/lib/clipboardUtils";
import { COLORS } from "~/lib/styleConstants";
import { useThemeColors } from "~/hooks/useTheme";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { Text } from "~/components/ui/text";

type ReceiveCopyBottomSheetProps = {
  arkAddress: string;
  bip321Uri: string;
  isOpen: boolean;
  lightningInvoice?: string;
  onClose: () => void;
  onchainAddress: string;
};

type CopyOption = {
  id: "request" | "ark" | "lightning" | "onchain";
  icon: ReactNode;
  label: string;
  value: string;
};

const truncateValue = (value: string) => {
  if (value.length <= 42) {
    return value;
  }

  return `${value.slice(0, 18)}…${value.slice(-14)}`;
};

export function ReceiveCopyBottomSheet({
  arkAddress,
  bip321Uri,
  isOpen,
  lightningInvoice,
  onClose,
  onchainAddress,
}: ReceiveCopyBottomSheetProps) {
  const colors = useThemeColors();
  const { copyWithState, isCopied } = useCopyToClipboard();
  const options: CopyOption[] = [
    {
      id: "request",
      icon: <Icon name="qr-code-outline" size={20} color={colors.foreground} />,
      label: "Payment request",
      value: bip321Uri,
    },
    {
      id: "ark",
      icon: <ArkIcon className="h-5 w-5 text-foreground" />,
      label: "Ark",
      value: arkAddress,
    },
    ...(lightningInvoice
      ? [
          {
            id: "lightning" as const,
            icon: <LightningIcon className="h-5 w-5 text-foreground" />,
            label: "Lightning",
            value: lightningInvoice,
          },
        ]
      : []),
    {
      id: "onchain",
      icon: <OnchainIcon className="h-5 w-5 text-foreground" />,
      label: "On-chain",
      value: onchainAddress,
    },
  ];

  const copyOption = (option: CopyOption) => {
    void copyWithState(option.value, option.id, {
      onCopy: () => {
        AccessibilityInfo.announceForAccessibility(`${option.label} copied`);
      },
    });
  };

  return (
    <AppBottomSheet isOpen={isOpen} onClose={onClose} detents={[0, "content"]}>
      <View className="pb-4">
        <View className="flex-row items-start justify-between gap-4">
          <View className="flex-1">
            <Text accessibilityRole="header" className="text-2xl font-bold text-foreground">
              Copy payment details
            </Text>
            <Text className="mt-2 text-sm leading-5 text-muted-foreground">
              Copy the unified request or a specific payment method.
            </Text>
          </View>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Close payment details"
            onPress={onClose}
            className="h-11 w-11 items-center justify-center rounded-full border border-border"
          >
            <Icon name="close" size={22} color={colors.foreground} />
          </Pressable>
        </View>

        <View className="mt-5 overflow-hidden rounded-[22px] border border-border bg-card">
          {options.map((option, index) => {
            const copied = isCopied(option.id);

            return (
              <Pressable
                key={option.id}
                accessibilityRole="button"
                accessibilityLabel={copied ? `${option.label} copied` : `Copy ${option.label}`}
                onPress={() => copyOption(option)}
                className={`flex-row items-center gap-4 px-4 py-4 ${
                  index < options.length - 1 ? "border-b border-border" : ""
                }`}
                testID={`receive-copy-${option.id}`}
              >
                <View className="h-11 w-11 items-center justify-center rounded-full bg-primary/10">
                  {option.icon}
                </View>
                <View className="flex-1">
                  <Text className="text-base font-semibold text-foreground">{option.label}</Text>
                  <Text
                    className="mt-1 text-sm text-muted-foreground"
                    numberOfLines={1}
                    ellipsizeMode="middle"
                  >
                    {truncateValue(option.value)}
                  </Text>
                </View>
                <View className="items-center gap-1">
                  <Icon
                    name={copied ? "checkmark-circle" : "copy-outline"}
                    size={21}
                    color={copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE}
                  />
                  <Text
                    accessibilityLiveRegion="polite"
                    className="text-[11px] font-semibold"
                    style={{ color: copied ? COLORS.SUCCESS : COLORS.BITCOIN_ORANGE }}
                  >
                    {copied ? "Copied" : "Copy"}
                  </Text>
                </View>
              </Pressable>
            );
          })}
        </View>
      </View>
    </AppBottomSheet>
  );
}
