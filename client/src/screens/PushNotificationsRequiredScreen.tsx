import { T, useGT } from "gt-react-native";
import React from "react";
import { View, Linking, ScrollView } from "react-native";
import { BellRing, Zap, RefreshCcw } from "lucide-react-native";
import { Text } from "~/components/ui/text";
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

export const PushNotificationsRequiredScreen = ({
  isRequesting,
  onRequestPermission,
  onRetryStatus,
}: PushNotificationsRequiredScreenProps) => {
  const gt = useGT();
  const highlights = [
    {
      title: gt("Refresh expiring VTXOs"),
      description: gt(
        "We attempt to refresh VTXOs in the background to prevent them from expiring.",
      ),
      icon: RefreshCcw,
    },
    {
      title: gt("Receive while app is closed"),
      description: gt("Lightning and Ark payments keep flowing, even in the background."),
      icon: Zap,
    },
  ];
  return (
    <NoahSafeAreaView className="flex-1 bg-background" maxContentWidth={640}>
      <ScrollView contentContainerClassName="grow px-6 py-10">
        <View className="items-center">
          <View className="h-24 w-24 items-center justify-center rounded-3xl bg-card border border-border shadow-lg shadow-black/30">
            <BellRing size={48} color="#f97316" />
          </View>
          <T>
            <Text className="mt-6 text-3xl font-bold text-center">Turn on push notifications</Text>
          </T>
          <T>
            <Text className="mt-3 text-center text-muted-foreground">
              Push notifications are critical for the app to function and enabling them helps
              prevent VTXOs from expiring.
            </Text>
          </T>
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
            label={gt("Enable notifications")}
            onPress={onRequestPermission}
            isLoading={isRequesting}
            loadingLabel={gt("Requesting...")}
            size="lg"
            fullWidth
          />
          <View className="space-y-3 mt-3">
            <NativeNoahSecondaryButton
              label={gt("I turned them on - check again")}
              onPress={onRetryStatus}
              disabled={isRequesting}
              fullWidth
            />
            <NativeNoahSecondaryButton
              label={gt("Open settings to allow notifications")}
              emphasis="ghost"
              onPress={() => Linking.openSettings()}
              disabled={isRequesting}
              fullWidth
            />
          </View>
        </View>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default PushNotificationsRequiredScreen;
