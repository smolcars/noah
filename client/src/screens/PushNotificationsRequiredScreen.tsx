import React from "react";
import { View, Linking } from "react-native";
import { BellRing, Zap, RefreshCcw } from "lucide-react-native";
import { Text } from "~/components/ui/text";
import { Button } from "~/components/ui/button";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { type PushPermissionStatus } from "~/lib/pushNotifications";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";

type PermissionStatus = PushPermissionStatus["status"] | "checking";

type PushNotificationsRequiredScreenProps = {
  status: PermissionStatus;
  isRequesting: boolean;
  onRequestPermission: () => Promise<void>;
  onRetryStatus: () => Promise<void>;
};

const highlights = [
  {
    title: "Refresh expiring VTXOs",
    description: "We attempt to refresh VTXOs in the background to prevent them from expiring.",
    icon: RefreshCcw,
  },
  {
    title: "Receive while app is closed",
    description: "Lightning and Ark payments keep flowing, even in the background.",
    icon: Zap,
  },
];

export const PushNotificationsRequiredScreen = ({
  isRequesting,
  onRequestPermission,
  onRetryStatus,
}: PushNotificationsRequiredScreenProps) => {
  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="flex-1 px-6 py-10">
        <View className="items-center">
          <View className="h-24 w-24 items-center justify-center rounded-3xl bg-card border border-border shadow-lg shadow-black/30">
            <BellRing size={48} color="#f97316" />
          </View>
          <Text className="mt-6 text-3xl font-bold text-center">Turn on push notifications</Text>
          <Text className="mt-3 text-center text-muted-foreground">
            Push notifications are critical for the app to function and enabling them helps prevent
            VTXOs from expiring.
          </Text>
        </View>

        <View className="mt-10 space-y-4">
          {highlights.map((item) => (
            <View
              key={item.title}
              className="flex-row items-center rounded-2xl border border-border bg-card px-4 py-4 mb-2"
            >
              <View className="mr-4 h-11 w-11 items-center justify-center rounded-xl bg-orange-500/15">
                <item.icon size={22} color="#f97316" />
              </View>
              <View className="flex-1">
                <Text className="text-base font-semibold">{item.title}</Text>
                <Text className="text-sm text-muted-foreground">{item.description}</Text>
              </View>
            </View>
          ))}
        </View>

        <View className="mt-10 space-y-4">
          <NativeNoahButton
            label="Enable notifications"
            onPress={onRequestPermission}
            isLoading={isRequesting}
            loadingLabel="Requesting..."
            size="lg"
            fullWidth
          />
          <View className="space-y-3 mt-3">
            <NativeNoahSecondaryButton
              label="I turned them on - check again"
              onPress={onRetryStatus}
              disabled={isRequesting}
              fullWidth
            />
            <Button
              variant="ghost"
              className="flex-row items-center justify-center"
              onPress={() => Linking.openSettings()}
              disabled={isRequesting}
            >
              <Text className="text-sm text-muted-foreground">
                Open settings to allow notifications
              </Text>
            </Button>
          </View>
        </View>
      </View>
    </NoahSafeAreaView>
  );
};

export default PushNotificationsRequiredScreen;
