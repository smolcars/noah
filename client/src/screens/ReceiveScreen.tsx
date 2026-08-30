import Icon from "@react-native-vector-icons/ionicons";
import { useCallback, useState } from "react";
import { Pressable, ScrollView, Share, useWindowDimensions, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import QRCode from "react-native-qrcode-svg";

import logoImage from "../../assets/All_Files/light_dark_tinted/icon_clear_tinted_ios.png";
import type { TabParamList } from "~/Navigators";
import { BoardArkBottomSheet } from "~/components/BoardArkBottomSheet";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { ReceiveAmountBottomSheet } from "~/components/ReceiveAmountBottomSheet";
import { ReceiveCopyBottomSheet } from "~/components/ReceiveCopyBottomSheet";
import { AppBottomSheet } from "~/components/ui/AppBottomSheet";
import { NativeNoahButton } from "~/components/ui/NativeNoahButton";
import { NativeNoahIconButton } from "~/components/ui/NativeNoahIconButton";
import { NativeNoahSecondaryButton } from "~/components/ui/NativeNoahSecondaryButton";
import { NoahActivityIndicator } from "~/components/ui/NoahActivityIndicator";
import { Text } from "~/components/ui/text";
import { useBitcoinAmountFormatter } from "~/hooks/useBitcoinAmountFormatter";
import { useBtcToFiatRate } from "~/hooks/useMarketData";
import { useReceiveRequest } from "~/hooks/useReceiveRequest";
import { useThemeColors } from "~/hooks/useTheme";
import { formatFiatAmount, satsToFiat } from "~/lib/fiatCurrency";
import logger from "~/lib/log";
import { COLORS } from "~/lib/styleConstants";
import { useProfileStore } from "~/store/profileStore";

const log = logger("ReceiveScreen");
const DESTRUCTIVE_COLOR = "#dc2626";

const ReceiveScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<TabParamList>>();
  const colors = useThemeColors();
  const { width } = useWindowDimensions();
  const fiatCurrency = useProfileStore((state) => state.preferredCurrency);
  const formatBitcoinAmount = useBitcoinAmountFormatter();
  const { data: btcPrice } = useBtcToFiatRate();
  const [isAmountSheetOpen, setIsAmountSheetOpen] = useState(false);
  const [isCopySheetOpen, setIsCopySheetOpen] = useState(false);
  const [isActionsSheetOpen, setIsActionsSheetOpen] = useState(false);
  const [shouldOpenBoardArkSheet, setShouldOpenBoardArkSheet] = useState(false);
  const [isBoardArkSheetOpen, setIsBoardArkSheetOpen] = useState(false);

  const handleReceiveComplete = useCallback(
    (amountSat: number) => {
      navigation.navigate("Home", {
        screen: "ReceiveSuccess",
        params: { amountSat },
      });
    },
    [navigation],
  );

  const {
    baseError,
    generateBaseRequest,
    isGeneratingBase,
    isGeneratingLightning,
    removeAmount,
    request,
    saveAmount,
  } = useReceiveRequest(handleReceiveComplete);

  const qrSize = Math.min(270, width - 72);
  const formattedFiatAmount =
    request?.amountSat && btcPrice
      ? formatFiatAmount(satsToFiat(request.amountSat, btcPrice, fiatCurrency), fiatCurrency)
      : null;

  const shareRequest = () => {
    if (!request) {
      return;
    }

    void Share.share({ message: request.bip321Uri }).catch((error) => {
      log.w("Could not share receive request", [
        error instanceof Error ? error.message : String(error),
      ]);
    });
  };

  const submitAmount = async (nextAmount: { amountSat: number; description: string }) => {
    try {
      const didSave = await saveAmount(nextAmount);
      if (didSave) {
        setIsAmountSheetOpen(false);
      }
    } catch (error) {
      log.w("Could not add an amount to the receive request", [
        error instanceof Error ? error.message : String(error),
      ]);
    }
  };

  const removeRequestAmount = () => {
    removeAmount();
    setIsAmountSheetOpen(false);
  };

  const openBoardToArk = () => {
    setShouldOpenBoardArkSheet(true);
    setIsActionsSheetOpen(false);
  };

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <ScrollView
        className="flex-1"
        contentContainerStyle={{ flexGrow: 1, paddingBottom: 32 }}
        showsVerticalScrollIndicator={false}
      >
        <View className="flex-1 px-5 pb-8">
          <View className="flex-row items-center justify-between pt-1">
            <Text className="text-2xl font-bold text-foreground">Receive</Text>
            <View className="flex-row items-center gap-2">
              <NativeNoahIconButton
                icon="copy"
                accessibilityLabel="Show payment details"
                onPress={() => setIsCopySheetOpen(true)}
                disabled={!request}
                testID="receive-copy-button"
              />
              <NativeNoahIconButton
                icon="more"
                accessibilityLabel="More receive actions"
                onPress={() => setIsActionsSheetOpen(true)}
                testID="receive-more-button"
              />
            </View>
          </View>

          <View className="flex-1 items-center justify-center py-8">
            {request ? (
              <>
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="Show payment details"
                  accessibilityHint="Opens options to copy the unified request or an individual payment method"
                  onPress={() => setIsCopySheetOpen(true)}
                  className="items-center"
                  testID="receive-qr-button"
                >
                  <View className="overflow-hidden rounded-[28px] bg-white shadow-sm shadow-foreground/5">
                    <View className="p-4">
                      <QRCode
                        value={request.bip321Uri}
                        size={qrSize}
                        backgroundColor="white"
                        color="black"
                        logo={logoImage}
                        logoSize={44}
                        logoBackgroundColor="white"
                        logoMargin={4}
                        logoBorderRadius={11}
                        ecl="H"
                      />
                    </View>

                    {request.amountSat !== null ? (
                      <View
                        className="items-center px-5 py-5"
                        style={{ backgroundColor: COLORS.BITCOIN_ORANGE }}
                      >
                        <Text className="text-[38px] font-bold leading-[44px] text-[#1a1a1a]">
                          {formatBitcoinAmount(request.amountSat)}
                        </Text>
                        {formattedFiatAmount ? (
                          <Text className="mt-1 text-base font-semibold text-[#1a1a1a]/75">
                            {formattedFiatAmount}
                          </Text>
                        ) : null}
                      </View>
                    ) : null}
                  </View>
                </Pressable>

                <Text className="mt-6 text-center text-base font-medium text-foreground">
                  Scan to receive bitcoin with Noah
                </Text>
                {request.amountSat === null ? (
                  <View className="mt-3 flex-row items-center gap-2">
                    <Icon name="flash-outline" size={17} color={COLORS.BITCOIN_ORANGE} />
                    <Text className="text-center text-sm text-muted-foreground">
                      Add an amount to receive over Lightning
                    </Text>
                  </View>
                ) : null}
              </>
            ) : isGeneratingBase || !baseError ? (
              <View className="items-center">
                <View
                  className="items-center justify-center rounded-[28px] border border-border bg-card"
                  style={{ width: qrSize + 32, height: qrSize + 32 }}
                  testID="receive-qr-loading"
                >
                  <NoahActivityIndicator />
                </View>
                <Text className="mt-6 text-sm text-muted-foreground">
                  Creating your payment request…
                </Text>
              </View>
            ) : (
              <View className="w-full items-center rounded-[24px] border border-destructive/30 bg-destructive/10 px-6 py-8">
                <Icon name="warning-outline" size={34} color={DESTRUCTIVE_COLOR} />
                <Text className="mt-4 text-center text-xl font-bold text-foreground">
                  Couldn’t create a request
                </Text>
                <Text className="mt-2 max-w-[310px] text-center text-sm leading-5 text-muted-foreground">
                  {baseError?.message ?? "Try again to generate fresh Ark and on-chain addresses."}
                </Text>
                <NativeNoahButton
                  label="Retry"
                  loadingLabel="Retrying…"
                  onPress={() => void generateBaseRequest()}
                  isLoading={isGeneratingBase}
                  className="mt-6"
                  testID="receive-retry-button"
                />
              </View>
            )}
          </View>

          {request ? (
            <View className="gap-3">
              <NativeNoahButton
                label={request.amountSat === null ? "Add amount" : "Edit amount"}
                onPress={() => setIsAmountSheetOpen(true)}
                disabled={isGeneratingLightning}
                size="lg"
                fullWidth
                testID="receive-amount-button"
              />
              <NativeNoahSecondaryButton
                label="Share"
                onPress={shareRequest}
                size="lg"
                fullWidth
                testID="receive-share-button"
              />
            </View>
          ) : null}
        </View>
      </ScrollView>

      {request ? (
        <>
          <ReceiveAmountBottomSheet
            initialAmountSat={request.amountSat}
            initialDescription={request.description}
            isOpen={isAmountSheetOpen}
            isSubmitting={isGeneratingLightning}
            onClose={() => setIsAmountSheetOpen(false)}
            onRemove={removeRequestAmount}
            onSubmit={(nextAmount) => void submitAmount(nextAmount)}
          />
          <ReceiveCopyBottomSheet
            arkAddress={request.arkAddress}
            bip321Uri={request.bip321Uri}
            isOpen={isCopySheetOpen}
            lightningInvoice={request.lightningInvoice?.payment_request}
            onClose={() => setIsCopySheetOpen(false)}
            onchainAddress={request.onchainAddress}
          />
        </>
      ) : null}

      <AppBottomSheet
        isOpen={isActionsSheetOpen}
        onClose={() => setIsActionsSheetOpen(false)}
        onDismiss={() => {
          if (shouldOpenBoardArkSheet) {
            setShouldOpenBoardArkSheet(false);
            setIsBoardArkSheetOpen(true);
          }
        }}
        detents={[0, "content"]}
      >
        <View>
          <Text accessibilityRole="header" className="text-xl font-bold text-foreground">
            More receive actions
          </Text>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Board bitcoin to Ark"
            onPress={openBoardToArk}
            className="mt-5 flex-row items-center gap-4 rounded-[20px] border border-border bg-card px-4 py-4"
            testID="receive-board-ark-button"
          >
            <View className="h-11 w-11 items-center justify-center rounded-full bg-primary/10">
              <Icon name="boat-outline" size={21} color={colors.foreground} />
            </View>
            <View className="flex-1">
              <Text className="text-base font-semibold text-foreground">Board to Ark</Text>
              <Text className="mt-1 text-sm text-muted-foreground">
                Move on-chain bitcoin into Ark
              </Text>
            </View>
            <Icon name="chevron-forward" size={18} color={colors.mutedForeground} />
          </Pressable>
        </View>
      </AppBottomSheet>

      <BoardArkBottomSheet
        isOpen={isBoardArkSheetOpen}
        onClose={() => setIsBoardArkSheetOpen(false)}
      />
    </NoahSafeAreaView>
  );
};

export default ReceiveScreen;
