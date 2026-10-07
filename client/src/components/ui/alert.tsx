import { useTheme } from "@react-navigation/native";
import { cva, type VariantProps } from "class-variance-authority";
import type { LucideIcon } from "lucide-react-native";
import * as React from "react";
import { View } from "react-native";
import { cn } from "~/lib/utils";
import { Text } from "~/components/ui/text";

const alertVariants = cva(
  "relative bg-background w-full rounded-lg border border-border p-4 shadow shadow-foreground/10",
  {
    variants: {
      variant: {
        default: "border-green-500/50 bg-green-500/10",
        destructive: "border-destructive",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

function Alert({
  className,
  variant,
  children,
  icon: Icon,
  iconSize = 16,

  ...props
}: React.ComponentProps<typeof View> &
  VariantProps<typeof alertVariants> & {
    icon: LucideIcon;
    iconSize?: number;
  }) {
  const { colors } = useTheme();
  return (
    <View role="alert" className={alertVariants({ variant, className })} {...props}>
      <View className="absolute left-3.5 top-4 -translate-y-0.5">
        <Icon
          size={iconSize}
          color={
            variant === "destructive"
              ? colors.notification
              : variant === "default"
                ? "#22c55e"
                : colors.text
          }
        />
      </View>
      {children}
    </View>
  );
}

function AlertTitle({ className, ...props }: React.ComponentProps<typeof Text>) {
  return (
    <Text
      className={cn(
        "pl-7 mb-1 font-medium text-base leading-none tracking-tight text-foreground",
        className,
      )}
      {...props}
    />
  );
}

function AlertDescription({ className, ...props }: React.ComponentProps<typeof Text>) {
  return (
    <Text className={cn("pl-7 text-sm leading-relaxed text-foreground", className)} {...props} />
  );
}

export { Alert, AlertDescription, AlertTitle };
