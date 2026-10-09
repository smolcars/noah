import { T, useGT, useLocale, Var } from "gt-react-native";
import { useState } from "react";
import { Keyboard, ScrollView, View } from "react-native";
import { useNavigation, useRoute, type RouteProp } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import * as Haptics from "expo-haptics";

import type { SettingsStackParamList } from "~/Navigators";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { AdaptiveColumns } from "~/components/AdaptiveColumns";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahBackButton } from "~/components/ui/NativeNoahIconButton";
import {
  NativeNoahSegmentedControl,
  type NativeNoahSegmentedControlOption,
} from "~/components/ui/NativeNoahSegmentedControl";
import { Input } from "~/components/ui/input";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import {
  createRecurringPaymentSchedule,
  resolveRecurringDestination,
} from "~/lib/recurringPayments";
import {
  describeInterval,
  MAX_CUSTOM_INTERVAL_DAYS,
  nextRunAtForIndex,
} from "~/lib/recurringSchedule";
import type { RecurringInterval } from "~/types/recurringPayment";
import { isRecurringPaymentsSupported } from "~/constants";

type Frequency = "weekly" | "monthly" | "custom";
type EndMode = "never" | "date" | "count";

const pad = (value: number) => String(value).padStart(2, "0");
const formatDateInput = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
const formatTimeInput = (date: Date) => `${pad(date.getHours())}:${pad(date.getMinutes())}`;

/** Parses `YYYY-MM-DD` + `HH:MM` as local time. Returns null when invalid. */
const parseLocalDateTime = (dateValue: string, timeValue: string): number | null => {
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateValue.trim());
  const timeMatch = /^(\d{1,2}):(\d{2})$/.exec(timeValue.trim());
  if (!dateMatch || !timeMatch) return null;
  const [year, month, day] = [Number(dateMatch[1]), Number(dateMatch[2]), Number(dateMatch[3])];
  const [hours, minutes] = [Number(timeMatch[1]), Number(timeMatch[2])];
  if (hours > 23 || minutes > 59) return null;
  const date = new Date(year, month - 1, day, hours, minutes, 0, 0);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
    return null;
  }
  return date.getTime();
};

const SectionLabel = ({ children }: { children: string }) => (
  <Text className="mb-2 mt-5 text-sm font-semibold uppercase tracking-[2px] text-muted-foreground">
    {children}
  </Text>
);

const inputClassName = "rounded-2xl border-border bg-card px-4 py-4 text-foreground";

const RecurringPaymentEditorScreen = () => {
  const gt = useGT();
  const locale = useLocale();
  const FREQUENCY_OPTIONS: readonly NativeNoahSegmentedControlOption<Frequency>[] = [
    { label: gt("Weekly"), value: "weekly" },
    { label: gt("Monthly"), value: "monthly" },
    { label: gt("Custom"), value: "custom" },
  ];
  const END_OPTIONS: readonly NativeNoahSegmentedControlOption<EndMode>[] = [
    { label: gt("Never"), value: "never" },
    { label: gt("On date"), value: "date" },
    { label: gt("After"), value: "count" },
  ];
  const FIELD_ERRORS: Record<string, string> = {
    label: gt("Give this payment a short name (max 64 characters)."),
    amount: gt("Enter a whole amount in sats."),
    destination: gt("Enter an Ark address, Lightning address or BOLT12 offer."),
    interval: gt("Custom intervals must be between 1 and {count} days.", {
      count: MAX_CUSTOM_INTERVAL_DAYS,
    }),
    start: gt("The first payment can't be in the past."),
    end: gt("The end date must be after the first payment."),
    occurrences: gt("Enter how many payments should be made."),
  };
  const UNSUPPORTED_MESSAGE = gt(
    "Recurring payments aren't available on devices that use UnifiedPush yet.",
  );
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const route = useRoute<RouteProp<SettingsStackParamList, "RecurringPaymentEditor">>();
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const tabBarHeight = useBottomTabBarHeight();
  const { bottom: safeBottomInset } = useSafeAreaInsets();

  const [defaultStart] = useState(() => {
    const date = new Date();
    date.setDate(date.getDate() + 1);
    date.setHours(9, 0, 0, 0);
    return date;
  });

  const [label, setLabel] = useState("");
  const [recipient, setRecipient] = useState(route.params?.destination ?? "");
  const [amount, setAmount] = useState(
    route.params?.amountSat ? String(route.params.amountSat) : "",
  );
  const [comment, setComment] = useState(route.params?.comment ?? "");
  const [frequency, setFrequency] = useState<Frequency>("monthly");
  const [customDays, setCustomDays] = useState("14");
  const [startDate, setStartDate] = useState(formatDateInput(defaultStart));
  const [startTime, setStartTime] = useState(formatTimeInput(defaultStart));
  const [endMode, setEndMode] = useState<EndMode>("never");
  const [endDate, setEndDate] = useState("");
  const [endCount, setEndCount] = useState("12");
  const [error, setError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  const resolvedDestination = resolveRecurringDestination(recipient, gt);
  const amountSat = /^\d+$/.test(amount.trim()) ? Number(amount.trim()) : Number.NaN;
  const interval: RecurringInterval =
    frequency === "weekly"
      ? { unit: "week", every: 1 }
      : frequency === "monthly"
        ? { unit: "month", every: 1 }
        : { unit: "day", every: Number(customDays) };
  const startAt = parseLocalDateTime(startDate, startTime);
  const endAt = endMode === "date" ? parseLocalDateTime(endDate, "23:59") : null;
  const maxOccurrences = endMode === "count" ? Number(endCount) : null;

  const preview =
    startAt === null || !Number.isInteger(interval.every) || interval.every < 1
      ? []
      : [0, 1, 2]
          .map((index) => nextRunAtForIndex({ startAt, interval, endAt, maxOccurrences }, index))
          .filter((at) => at !== null)
          .map((at) => new Date(at));

  const handleSave = async () => {
    Keyboard.dismiss();
    setError(null);

    if (resolvedDestination.isErr()) {
      setError(resolvedDestination.error);
      return;
    }
    if (startAt === null) {
      setError(gt("Enter the first payment date as YYYY-MM-DD and time as HH:MM."));
      return;
    }

    if (endMode === "date" && endAt === null) {
      setError(gt("Enter the end date as YYYY-MM-DD."));
      return;
    }

    setIsSaving(true);
    const result = await createRecurringPaymentSchedule({
      label: label.trim() || resolvedDestination.value.destination.slice(0, 32),
      destination: resolvedDestination.value.destination,
      destinationType: resolvedDestination.value.destinationType,
      amountSat,
      comment: resolvedDestination.value.destinationType === "lnurl" ? comment.trim() : "",
      interval,
      startAt,
      endAt,
      maxOccurrences,
    });
    setIsSaving(false);

    if (result.isErr()) {
      await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
      setError(
        result.error.kind === "limit"
          ? gt("You've reached the maximum number of recurring payments.")
          : result.error.kind === "unsupported"
            ? UNSUPPORTED_MESSAGE
            : (FIELD_ERRORS[result.error.field] ?? gt("Invalid recurring payment")),
      );
      return;
    }

    await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    navigation.navigate("RecurringPayments");
  };

  const destinationHint = resolvedDestination.isOk()
    ? resolvedDestination.value.destinationType === "ark"
      ? gt("Ark address")
      : resolvedDestination.value.destinationType === "lnurl"
        ? gt("Lightning address")
        : gt("BOLT12 offer")
    : recipient.trim()
      ? resolvedDestination.error
      : gt("Ark address, Lightning address or BOLT12 offer");

  if (!isRecurringPaymentsSupported()) {
    return (
      <NoahSafeAreaView className="flex-1 bg-background">
        <View className="flex-row items-center mb-4 mt-4 px-4">
          <NativeNoahBackButton
            onPress={() => navigation.goBack()}
            className="mr-3"
            testID="recurring-editor-back-button"
          />
          <T>
            <Text className="flex-1 text-2xl font-bold text-foreground">Recurring payments</Text>
          </T>
        </View>
        <Text className="px-4 text-muted-foreground" testID="recurring-editor-unsupported">
          {UNSUPPORTED_MESSAGE}
        </Text>
      </NoahSafeAreaView>
    );
  }

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <ScrollView
        className="flex-1 px-4"
        keyboardShouldPersistTaps="handled"
        automaticallyAdjustKeyboardInsets
        contentContainerStyle={{ paddingBottom: safeBottomInset + tabBarHeight + 24 }}
      >
        <View className="flex-row items-center mb-4 mt-4">
          <NativeNoahBackButton
            onPress={() => navigation.goBack()}
            className="mr-3"
            testID="recurring-editor-back-button"
          />
          <T>
            <Text className="flex-1 text-2xl font-bold text-foreground">New recurring payment</Text>
          </T>
        </View>

        <T>
          <Text className="text-muted-foreground">
            Noah sends this payment automatically from your Ark balance. Only this recipient and
            amount are ever allowed, and you can pause or cancel at any time.
          </Text>
        </T>

        <AdaptiveColumns>
          <View>
            <SectionLabel>{gt("Name")}</SectionLabel>
            <Input
              value={label}
              onChangeText={setLabel}
              placeholder={gt("e.g. Rent, Donation, VPN")}
              maxLength={64}
              className={inputClassName}
              testID="recurring-label-input"
            />

            <SectionLabel>{gt("Recipient")}</SectionLabel>
            <Input
              value={recipient}
              onChangeText={setRecipient}
              placeholder={gt("name@domain.com, ark1… or lno1…")}
              autoCapitalize="none"
              autoCorrect={false}
              className={inputClassName}
              testID="recurring-recipient-input"
            />
            <Text
              className={`mt-2 text-sm ${
                recipient.trim() && resolvedDestination.isErr()
                  ? "text-destructive"
                  : "text-muted-foreground"
              }`}
            >
              {destinationHint}
            </Text>

            <SectionLabel>{gt("Amount (sats)")}</SectionLabel>
            <Input
              value={amount}
              onChangeText={setAmount}
              placeholder={gt("Amount in sats")}
              keyboardType="number-pad"
              className={inputClassName}
              testID="recurring-amount-input"
            />
            {Number.isFinite(amountSat) && amountSat > 0 ? (
              <T>
                <Text className="mt-2 text-sm text-muted-foreground">
                  <Var>{formatBitcoinAmount(amountSat)}</Var> per payment, plus network fees
                </Text>
              </T>
            ) : null}

            {resolvedDestination.isOk() && resolvedDestination.value.destinationType === "lnurl" ? (
              <>
                <SectionLabel>{gt("Note (optional)")}</SectionLabel>
                <Input
                  value={comment}
                  onChangeText={setComment}
                  placeholder={gt("Shared with the recipient")}
                  maxLength={140}
                  className={inputClassName}
                  testID="recurring-comment-input"
                />
              </>
            ) : null}

            <SectionLabel>{gt("Repeat")}</SectionLabel>
            <NativeNoahSegmentedControl
              value={frequency}
              options={FREQUENCY_OPTIONS}
              onValueChange={setFrequency}
              testID="recurring-frequency"
            />
            {frequency === "custom" ? (
              <View className="mt-3 flex-row items-center gap-3">
                <T>
                  <Text className="text-foreground">Every</Text>
                </T>
                <Input
                  value={customDays}
                  onChangeText={setCustomDays}
                  keyboardType="number-pad"
                  className={`${inputClassName} w-24`}
                  testID="recurring-custom-days-input"
                />
                <T>
                  <Text className="text-foreground">days</Text>
                </T>
              </View>
            ) : null}

            <SectionLabel>{gt("First payment")}</SectionLabel>
            <View className="flex-row gap-3">
              <Input
                value={startDate}
                onChangeText={setStartDate}
                placeholder={gt("YYYY-MM-DD")}
                autoCorrect={false}
                className={`${inputClassName} flex-1`}
                testID="recurring-start-date-input"
              />
              <Input
                value={startTime}
                onChangeText={setStartTime}
                placeholder={gt("HH:MM")}
                autoCorrect={false}
                className={`${inputClassName} w-28`}
                testID="recurring-start-time-input"
              />
            </View>

            <SectionLabel>{gt("Ends")}</SectionLabel>
            <NativeNoahSegmentedControl
              value={endMode}
              options={END_OPTIONS}
              onValueChange={setEndMode}
              testID="recurring-end-mode"
            />
            {endMode === "date" ? (
              <Input
                value={endDate}
                onChangeText={setEndDate}
                placeholder={gt("YYYY-MM-DD")}
                autoCorrect={false}
                className={`${inputClassName} mt-3`}
                testID="recurring-end-date-input"
              />
            ) : endMode === "count" ? (
              <View className="mt-3 flex-row items-center gap-3">
                <Input
                  value={endCount}
                  onChangeText={setEndCount}
                  keyboardType="number-pad"
                  className={`${inputClassName} w-24`}
                  testID="recurring-end-count-input"
                />
                <T>
                  <Text className="text-foreground">payments</Text>
                </T>
              </View>
            ) : null}
          </View>
          <View>
            {preview.length > 0 ? (
              <View className="mt-6 rounded-2xl border border-border bg-card p-4">
                <Text className="font-semibold text-foreground">
                  {describeInterval(interval, gt)}
                </Text>
                {preview.map((date) => (
                  <Text key={date.getTime()} className="mt-1 text-muted-foreground">
                    {date.toLocaleString(locale)}
                  </Text>
                ))}
                <T>
                  <Text className="mt-3 text-sm text-muted-foreground">
                    You'll get a reminder the day before and a notification after each payment. If a
                    payment is missed while your phone is off, Noah skips it instead of paying
                    twice.
                  </Text>
                </T>
              </View>
            ) : null}

            {error ? <Text className="mt-4 text-destructive">{error}</Text> : null}

            <NativeNoahButton
              label={gt("Schedule payment")}
              onPress={handleSave}
              isLoading={isSaving}
              disabled={isSaving}
              className="mt-6"
              fullWidth
              testID="recurring-save-button"
            />
          </View>
        </AdaptiveColumns>
      </ScrollView>
    </NoahSafeAreaView>
  );
};

export default RecurringPaymentEditorScreen;
