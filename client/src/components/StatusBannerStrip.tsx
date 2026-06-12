import type React from "react";
import { Pressable, View } from "react-native";
import { Text } from "~/components/ui/text";

export type StatusBannerTone = "info" | "success" | "failed";

type StatusBannerStripProps = {
  title: string;
  message: string;
  icon: React.ReactNode;
  tone: StatusBannerTone;
  actionLabel?: string | null;
  actionBusyLabel?: string;
  isActionLoading?: boolean;
  onPress?: () => void;
  onActionPress?: () => void;
  className?: string;
};

export const StatusBannerStrip = ({
  title,
  message,
  icon,
  tone,
  actionLabel,
  actionBusyLabel = "Working",
  isActionLoading = false,
  onPress,
  onActionPress,
  className = "",
}: StatusBannerStripProps) => {
  const containerClassName =
    tone === "failed"
      ? "border-red-500/30 bg-red-500/5"
      : tone === "success"
        ? "border-green-500/25 bg-green-500/5"
        : "border-border/70 bg-card/70";

  const iconContainerClassName =
    tone === "failed" ? "bg-red-500/10" : tone === "success" ? "bg-green-500/10" : "bg-blue-500/10";

  const actionTextClassName = tone === "failed" ? "text-red-500" : "text-foreground";
  const content = (
    <>
      <View
        className={`mr-3 h-8 w-8 items-center justify-center rounded-full ${iconContainerClassName}`}
      >
        {icon}
      </View>

      <View className="min-w-0 flex-1">
        <Text className="text-sm font-semibold text-foreground" numberOfLines={1}>
          {title}
        </Text>
        <Text className="text-xs text-muted-foreground" numberOfLines={1}>
          {message}
        </Text>
      </View>

      {actionLabel && onActionPress ? (
        <Pressable
          onPress={onActionPress}
          disabled={isActionLoading}
          accessibilityRole="button"
          accessibilityLabel={actionLabel}
          className="ml-3 h-8 items-center justify-center rounded-full border border-border/70 bg-background/60 px-3 active:opacity-80 disabled:opacity-50"
        >
          <Text className={`text-xs font-semibold ${actionTextClassName}`}>
            {isActionLoading ? actionBusyLabel : actionLabel}
          </Text>
        </Pressable>
      ) : null}
    </>
  );
  const stripClassName = `min-h-[52px] flex-row items-center rounded-xl border px-3 py-2 ${containerClassName}`;

  return (
    <View className={className}>
      {onPress ? (
        <Pressable
          onPress={onPress}
          accessibilityRole="button"
          accessibilityLabel={title}
          className={`${stripClassName} active:opacity-80`}
        >
          {content}
        </Pressable>
      ) : (
        <View className={stripClassName}>{content}</View>
      )}
    </View>
  );
};
