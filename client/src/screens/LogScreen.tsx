import React, { useState, useEffect, useCallback, useRef } from "react";
import { View, Pressable } from "react-native";
import { useNavigation } from "@react-navigation/native";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor } from "../hooks/useTheme";
import { Text } from "../components/ui/text";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { getAppLogs } from "noah-tools";
import { COLORS } from "~/lib/styleConstants";
import { NativeNoahIconButton } from "~/components/ui/NativeNoahIconButton";
import { NoahActivityIndicator } from "../components/ui/NoahActivityIndicator";
import { useBottomTabBarHeight } from "react-native-bottom-tabs";
import RNFSTurbo from "react-native-fs-turbo";
import Share from "react-native-share";
import { CACHES_DIRECTORY_PATH, PLATFORM } from "~/constants";
import { Result, ResultAsync } from "neverthrow";
import logger from "~/lib/log";
import { FlashList, FlashListRef } from "@shopify/flash-list";

const log = logger("LogScreen");

const LogScreen = () => {
  const navigation = useNavigation();
  const iconColor = useIconColor();
  const [logs, setLogs] = useState<string[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);
  const bottomTabBarHeight = useBottomTabBarHeight();
  const isMountedRef = useRef(true);
  const listRef = useRef<FlashListRef<string>>(null);

  useEffect(() => {
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  const fetchLogs = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    const result = await ResultAsync.fromPromise(getAppLogs(), (e) => e as Error);

    if (isMountedRef.current) {
      if (result.isOk()) {
        setLogs(result.value);
        setLastUpdatedAt(new Date());
        setTimeout(() => {
          if (result.value.length > 0) {
            listRef.current?.scrollToIndex({ index: result.value.length - 1, animated: true });
          }
        }, 100);
      } else {
        setError(result.error.message || "Failed to fetch logs.");
      }
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  const handleShare = async () => {
    const path = `${CACHES_DIRECTORY_PATH}/noah_logs.txt`;
    const url = PLATFORM === "android" ? `file://${path}` : path;

    const writeFileResult = Result.fromThrowable(
      () => {
        return RNFSTurbo.writeFile(path, logs.join("\n"), "utf8");
      },
      (e) => e as Error,
    )();

    if (writeFileResult.isErr()) {
      log.e("Error writing log file:", [writeFileResult.error]);
      return;
    }

    const options = {
      title: "Share your file",
      message: "Noah App Logs",
      url,
      type: "text/plain",
    };

    const shareResult = await ResultAsync.fromPromise(Share.open(options), (e) => e as Error);

    if (shareResult.isErr()) {
      if (!shareResult.error.message.includes("User did not share")) {
        log.e("Error sharing Logs:", [shareResult.error]);
      }
    }

    // Clean up: Delete the temporary file after sharing
    Result.fromThrowable(
      () => {
        return RNFSTurbo.unlink(path);
      },
      (e) => e as Error,
    )();
  };

  const renderLogLine = useCallback(
    ({ item }: { item: string }) => (
      <Text
        selectable
        selectionColor={COLORS.BITCOIN_ORANGE}
        className="text-sm text-foreground font-mono p-2"
      >
        {item}
      </Text>
    ),
    [],
  );

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="p-4 flex-1">
        <View className="flex-row items-center justify-between mb-4">
          <View className="flex-row items-center">
            <Pressable onPress={() => navigation.goBack()} className="mr-4">
              <Icon name="arrow-back-outline" size={24} color={iconColor} />
            </Pressable>
            <Text className="text-2xl font-bold text-foreground">App Logs</Text>
          </View>
          <View className="flex-row space-x-2">
            <NativeNoahIconButton
              iconName="refresh-outline"
              onPress={fetchLogs}
              disabled={isLoading}
              style={{ marginRight: 12 }}
            />
            <NativeNoahIconButton
              iconName="share-outline"
              onPress={handleShare}
              disabled={logs.length === 0}
            />
          </View>
        </View>
        {isLoading ? (
          <View className="flex-1 justify-center items-center">
            <NoahActivityIndicator size="large" />
          </View>
        ) : error ? (
          <View className="flex-1 justify-center items-center">
            <Text className="text-destructive text-center">{error}</Text>
          </View>
        ) : (
          <View className="flex-1 bg-card rounded-lg p-2">
            {logs.length > 0 ? (
              <>
                {lastUpdatedAt ? (
                  <Text className="text-muted-foreground text-xs ml-2 mb-1">
                    Updated at {lastUpdatedAt.toLocaleTimeString()}
                  </Text>
                ) : null}
                <FlashList
                  ref={listRef}
                  data={logs}
                  renderItem={renderLogLine}
                  keyExtractor={(_, index) => `log-${index}`}
                  showsVerticalScrollIndicator
                  contentContainerStyle={{ paddingBottom: bottomTabBarHeight }}
                />
              </>
            ) : (
              <View className="flex-1 justify-center items-center">
                <Text className="text-center text-muted-foreground">No logs found.</Text>
              </View>
            )}
          </View>
        )}
      </View>
    </NoahSafeAreaView>
  );
};

export default LogScreen;
