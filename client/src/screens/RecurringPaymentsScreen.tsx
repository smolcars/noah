import { useState } from "react";
import { Linking, ScrollView, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import type { SettingsStackParamList } from "~/Navigators";
import { ConfirmationDialog } from "~/components/ConfirmationDialog";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import {
  cancelRecurringPayment,
  executeDueRecurringPayments,
  pauseRecurringPayment,
  resumeRecurringPaymentById,
} from "~/lib/recurringPayments";
import { isRecurringBackgroundTaskSupported } from "~/lib/recurringBackgroundTask";
import { describeInterval } from "~/lib/recurringSchedule";
import { useRecurringPaymentStore } from "~/store/recurringPaymentStore";
import type { RecurringPayment, RecurringPaymentStatus } from "~/types/recurringPayment";
import { useAdaptiveLayout } from "~/hooks/useAdaptiveLayout";
import { PANE_GAP } from "~/lib/adaptiveLayout";

const STATUS_LABELS: Record<RecurringPaymentStatus, string> = {
  active: "Active",
  paused: "Paused",
  needs_attention: "Needs attention",
  completed: "Completed",
};

const STATUS_CLASSES: Record<RecurringPaymentStatus, string> = {
  active: "text-green-500",
  paused: "text-muted-foreground",
  needs_attention: "text-destructive",
  completed: "text-muted-foreground",
};

const DESTINATION_LABELS: Record<RecurringPayment["destinationType"], string> = {
  ark: "Ark",
  lnurl: "Lightning address",
  offer: "BOLT12 offer",
};

const truncate = (value: string) =>
  value.length > 28 ? `${value.slice(0, 14)}…${value.slice(-10)}` : value;

const RecurringPaymentCard = ({ schedule }: { schedule: RecurringPayment }) => {
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const [isBusy, setIsBusy] = useState(false);
  const lastRun = schedule.runs[0];

  const run = async (action: () => Promise<void>) => {
    setIsBusy(true);
    try {
      await action();
    } finally {
      setIsBusy(false);
    }
  };

  return (
    <View
      className="mb-3 rounded-2xl border border-border bg-card p-4"
      testID={`recurring-payment-${schedule.id}`}
    >
      <View className="flex-row items-start justify-between">
        <View className="flex-1 pr-3">
          <Text className="text-lg font-semibold text-foreground">{schedule.label}</Text>
          <Text className="mt-1 text-muted-foreground">
            {DESTINATION_LABELS[schedule.destinationType]} · {truncate(schedule.destination)}
          </Text>
        </View>
        <Text className={`font-semibold ${STATUS_CLASSES[schedule.status]}`}>
          {STATUS_LABELS[schedule.status]}
        </Text>
      </View>

      <Text className="mt-3 text-2xl font-bold text-foreground">
        {formatBitcoinAmount(schedule.amountSat)}
      </Text>
      <Text className="text-muted-foreground">
        {describeInterval(schedule.interval)}
        {schedule.maxOccurrences !== null
          ? ` · ${schedule.occurrencesPaid} paid of ${schedule.maxOccurrences}`
          : schedule.occurrencesPaid > 0
            ? ` · ${schedule.occurrencesPaid} paid`
            : ""}
      </Text>

      {schedule.status === "active" && schedule.nextRunAt !== null ? (
        <Text className="mt-2 text-foreground">
          Next payment: {new Date(schedule.nextRunAt).toLocaleString()}
        </Text>
      ) : null}
      {schedule.endAt !== null ? (
        <Text className="mt-1 text-muted-foreground">
          Ends: {new Date(schedule.endAt).toLocaleDateString()}
        </Text>
      ) : null}
      {lastRun ? (
        <Text className="mt-1 text-muted-foreground">
          Last attempt: {new Date(lastRun.attemptedAt).toLocaleString()} ({lastRun.status})
        </Text>
      ) : null}
      {schedule.lastError ? (
        <Text className="mt-2 text-destructive">{schedule.lastError}</Text>
      ) : null}

      <View className="mt-4 flex-row gap-3">
        {schedule.status === "active" ? (
          <NativeNoahSecondaryButton
            label="Pause"
            disabled={isBusy}
            onPress={() => run(() => pauseRecurringPayment(schedule.id))}
            testID={`recurring-pause-${schedule.id}`}
          />
        ) : schedule.status !== "completed" ? (
          <NativeNoahButton
            label="Resume"
            disabled={isBusy}
            onPress={() => run(() => resumeRecurringPaymentById(schedule.id))}
            testID={`recurring-resume-${schedule.id}`}
          />
        ) : null}
        <ConfirmationDialog
          trigger={
            <NativeNoahButton
              label={schedule.status === "completed" ? "Remove" : "Cancel"}
              variant="destructive"
              disabled={isBusy}
              testID={`recurring-cancel-${schedule.id}`}
            />
          }
          title="Cancel recurring payment?"
          description={`No further payments will be sent to ${schedule.label}. Payments already sent are not affected.`}
          confirmText="Cancel payment"
          cancelText="Keep"
          onConfirm={() => run(() => cancelRecurringPayment(schedule.id))}
        />
      </View>
    </View>
  );
};

const RecurringPaymentsScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const schedules = useRecurringPaymentStore((state) => state.schedules);
  const tabBarHeight = useBottomTabBarHeight();
  const { bottom: safeBottomInset } = useSafeAreaInsets();
  const [isRunning, setIsRunning] = useState(false);
  const [runMessage, setRunMessage] = useState<string | null>(null);
  const { width, isExpanded, onLayout } = useAdaptiveLayout();

  const list = Object.values(schedules).sort(
    (a, b) => (a.nextRunAt ?? Number.MAX_SAFE_INTEGER) - (b.nextRunAt ?? Number.MAX_SAFE_INTEGER),
  );

  const handleRunNow = async () => {
    setIsRunning(true);
    setRunMessage(null);
    const summary = await executeDueRecurringPayments("manual");
    setIsRunning(false);
    setRunMessage(
      summary.paid + summary.failed + summary.needsAttention === 0
        ? "No payments are due right now."
        : `Sent ${summary.paid}, failed ${summary.failed}, needs attention ${summary.needsAttention}.`,
    );
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <ScrollView
        className="flex-1 px-4"
        contentContainerStyle={{ paddingBottom: safeBottomInset + tabBarHeight + 24 }}
      >
        <View className="flex-row items-center mb-4 mt-4">
          <NativeNoahBackButton
            onPress={() => navigation.goBack()}
            className="mr-3"
            testID="recurring-payments-back-button"
          />
          <Text className="text-2xl font-bold text-foreground">Recurring Payments</Text>
        </View>

        <Text className="mb-4 text-muted-foreground">
          Scheduled payments are signed by this device only. Noah's server just wakes your phone
          when a payment is due and never sees amounts or recipients. Keep notifications enabled so
          payments can go through while the app is closed.
        </Text>

        <NativeNoahButton
          label="New recurring payment"
          onPress={() => navigation.navigate("RecurringPaymentEditor")}
          fullWidth
          className="mb-4"
          testID="recurring-new-button"
        />

        {isRecurringBackgroundTaskSupported && list.some((s) => s.status === "active") ? (
          <View className="mb-4 rounded-2xl border border-border bg-card p-4">
            <Text className="font-semibold text-foreground">Runs in the background</Text>
            <Text className="mt-1 text-sm text-muted-foreground">
              Android checks for due payments about once a day, even when Noah is closed. Opening
              Noah sends a due payment right away. For reliable timing, set Noah's battery usage to
              "Unrestricted" in the app settings.
            </Text>
            <View className="mt-3">
              <NativeNoahSecondaryButton
                label="Open app settings"
                onPress={() => void Linking.openSettings()}
                fullWidth
                testID="recurring-open-app-settings"
              />
            </View>
          </View>
        ) : null}

        {list.length === 0 ? (
          <View className="items-center rounded-2xl border border-border bg-card p-6">
            <Text className="text-center text-muted-foreground">
              No recurring payments yet. Schedule rent, subscriptions, allowances or donations to an
              Ark address, Lightning address or BOLT12 offer.
            </Text>
          </View>
        ) : (
          <View
            onLayout={onLayout}
            className="flex-row flex-wrap"
            style={{ columnGap: isExpanded ? PANE_GAP : 0 }}
          >
            {list.map((schedule) => (
              <View
                key={schedule.id}
                style={{ width: isExpanded ? (width - PANE_GAP) / 2 : "100%" }}
              >
                <RecurringPaymentCard schedule={schedule} />
              </View>
            ))}
          </View>
        )}

        {list.some((s) => s.status === "active") ? (
          <View className="mt-2">
            <NativeNoahSecondaryButton
              label="Run due payments now"
              onPress={handleRunNow}
              disabled={isRunning}
              fullWidth
              testID="recurring-run-now-button"
            />
            {runMessage ? (
              <Text className="mt-2 text-center text-muted-foreground">{runMessage}</Text>
            ) : null}
          </View>
        ) : null}
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default RecurringPaymentsScreen;
