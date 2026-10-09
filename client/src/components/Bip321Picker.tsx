import { T, useGT } from "gt-react-native";
import React from "react";
import { TouchableOpacity, View } from "react-native";
import { ParsedBip321 } from "../lib/sendUtils";
import { ArkIcon } from "~/lib/icons/Ark";
import { LightningIcon } from "~/lib/icons/Lightning";
import { OnchainIcon } from "~/lib/icons/Onchain";
import { Text } from "~/components/ui/text";
import { useThemeColors } from "~/hooks/useTheme";
import { COLORS } from "~/lib/styleConstants";

type PaymentMethod = "ark" | "lightning" | "onchain" | "offer";

type Bip321PickerProps = {
  bip321Data: ParsedBip321;
  selectedPaymentMethod: PaymentMethod;
  onSelect: (type: PaymentMethod) => void;
  showSectionHeader?: boolean;
  showSelectedDestination?: boolean;
};

type PaymentOptionConfig = {
  method: PaymentMethod;
  label: string;
  value: string;
  icon: React.ReactNode;
};

const truncateValue = (value: string) => {
  if (value.length <= 32) {
    return value;
  }

  return `${value.slice(0, 14)}...${value.slice(-10)}`;
};

export const Bip321Picker = ({
  bip321Data,
  selectedPaymentMethod,
  onSelect,
  showSectionHeader = true,
  showSelectedDestination = true,
}: Bip321PickerProps) => {
  const gt = useGT();
  const colors = useThemeColors();

  const options = [
    bip321Data.arkAddress
      ? {
          method: "ark" as const,
          label: gt("Ark"),
          value: bip321Data.arkAddress,
          icon: <ArkIcon className="h-4 w-4 text-foreground" />,
        }
      : null,
    bip321Data.lightningInvoice
      ? {
          method: "lightning" as const,
          label: gt("Lightning"),
          value: bip321Data.lightningInvoice,
          icon: <LightningIcon className="h-4 w-4 text-foreground" />,
        }
      : null,
    bip321Data.offer
      ? {
          method: "offer" as const,
          label: gt("Offer"),
          value: bip321Data.offer,
          icon: <LightningIcon className="h-4 w-4 text-foreground" />,
        }
      : null,
    bip321Data.onchainAddress
      ? {
          method: "onchain" as const,
          label: gt("On-chain"),
          value: bip321Data.onchainAddress,
          icon: <OnchainIcon className="h-4 w-4 text-foreground" />,
        }
      : null,
  ].filter(Boolean) as PaymentOptionConfig[];

  const selectedOption = options.find((option) => option.method === selectedPaymentMethod);

  return (
    <View className={showSectionHeader ? "mt-4 border-t border-border/60 pt-4" : "mt-4"}>
      {showSectionHeader ? (
        <View className="flex-row items-center justify-between">
          <T>
            <Text className="text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
              Payment route
            </Text>
          </T>
          <T>
            <Text className="text-xs font-medium uppercase tracking-[2px] text-muted-foreground">
              Parsed request
            </Text>
          </T>
        </View>
      ) : null}

      <View className={`${showSectionHeader ? "mt-4" : ""} flex-row flex-wrap gap-2`}>
        {options.map((option) => {
          const isSelected = option.method === selectedPaymentMethod;

          return (
            <TouchableOpacity
              key={option.method}
              onPress={() => onSelect(option.method)}
              className="rounded-full border px-4 py-2"
              style={{
                borderColor: isSelected ? COLORS.BITCOIN_ORANGE : `${colors.mutedForeground}26`,
                backgroundColor: isSelected ? "rgba(201, 138, 60, 0.14)" : `${colors.card}99`,
              }}
              activeOpacity={0.8}
            >
              <View className="flex-row items-center gap-2">
                {option.icon}
                <Text
                  className={`text-sm font-semibold ${
                    isSelected ? "text-foreground" : "text-muted-foreground"
                  }`}
                >
                  {option.label}
                </Text>
              </View>
            </TouchableOpacity>
          );
        })}
      </View>

      {showSelectedDestination && selectedOption?.value ? (
        <View
          className="mt-4 rounded-[20px] border px-4 py-4"
          style={{
            borderColor: `${colors.mutedForeground}24`,
            backgroundColor: `${colors.card}A6`,
          }}
        >
          <T>
            <Text className="text-xs font-semibold uppercase tracking-[2px] text-muted-foreground">
              Selected destination
            </Text>
          </T>
          <Text className="mt-2 text-base font-semibold text-foreground">
            {selectedOption.label}
          </Text>
          <Text className="mt-2 text-sm leading-6 text-muted-foreground">
            {truncateValue(selectedOption.value)}
          </Text>
        </View>
      ) : null}
    </View>
  );
};
