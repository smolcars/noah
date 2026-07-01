import React, { useState, useEffect, useRef } from "react";
import { View, Pressable, Image, Platform } from "react-native";
import { useNavigation } from "@react-navigation/native";
import type { NativeStackNavigationProp } from "@react-navigation/native-stack";
import type { SettingsStackParamList } from "../Navigators";
import Icon from "@react-native-vector-icons/ionicons";
import { useIconColor, useTheme } from "../hooks/useTheme";
import { NoahSafeAreaView } from "~/components/NoahSafeAreaView";
import { Text } from "~/components/ui/text";
import { NativeNoahSlider } from "~/components/ui/NativeNoahSlider";
import { Asset } from "expo-asset";
import {
  playAudio,
  pauseAudio,
  stopAudio,
  resumeAudio,
  seekAudio,
  getAudioDuration,
  getAudioPosition,
  isAudioPlaying,
} from "noah-tools";
import audioFile from "../../assets/noahs-ark-story.m4a";
import logoImageDark from "../../assets/1024_no_background.png";
import logoImageLight from "../../assets/All_Files/light_dark_tinted/icon_clear_tinted_ios.png";
import logger from "~/lib/log";

const log = logger("NoahStoryScreen");

const NoahStoryScreen = () => {
  const navigation = useNavigation<NativeStackNavigationProp<SettingsStackParamList>>();
  const iconColor = useIconColor();
  const { isDark } = useTheme();
  const logoImage = isDark ? logoImageDark : logoImageLight;
  const [isPlaying, setIsPlaying] = useState(false);
  const [duration, setDuration] = useState(0);
  const [position, setPosition] = useState(0);
  const [dragPosition, setDragPosition] = useState<number | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const intervalRef = useRef<NodeJS.Timeout | null>(null);
  const isDraggingRef = useRef(false);

  const startPositionUpdates = () => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
    }
    intervalRef.current = setInterval(() => {
      let currentPosition = 0;
      let currentDuration = 0;
      let playing = false;
      let hasError = false;

      try {
        currentPosition = getAudioPosition();
        currentDuration = getAudioDuration();
        playing = isAudioPlaying();
      } catch (error) {
        log.e("Error updating position:", [error]);
        hasError = true;
      }

      if (!hasError) {
        if (!isDraggingRef.current) {
          setPosition(currentPosition);
        }
        setDuration(currentDuration);
        setIsPlaying(playing);

        if (!playing && currentPosition >= currentDuration && currentDuration > 0) {
          stopPositionUpdates();
          setIsPlaying(false);
        }
      }
    }, 500);
  };

  const stopPositionUpdates = () => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  };

  const handlePlayPause = async () => {
    const currentlyPlaying = isPlaying;
    const currentPosition = position;
    const currentDuration = duration;

    if (currentlyPlaying) {
      pauseAudio();
      setIsPlaying(false);
      stopPositionUpdates();
    } else {
      const shouldLoadNew = currentPosition === 0 || currentPosition >= currentDuration;

      const normalizeAudioPath = (uri: string | null) => {
        if (Platform.OS === "ios") {
          return uri;
        }
        return uri?.replace("file://", "");
      };

      if (shouldLoadNew) {
        try {
          setIsLoading(true);
          const asset = Asset.fromModule(audioFile);
          await asset.downloadAsync();
          const audioPath = normalizeAudioPath(asset.localUri);
          await playAudio(audioPath!);
          setIsLoading(false);
          setIsPlaying(true);
          startPositionUpdates();
        } catch (error) {
          log.e("Error playing audio:", [error]);
          setIsLoading(false);
          setIsPlaying(false);
        }
      } else {
        try {
          resumeAudio();
          setIsPlaying(true);
          startPositionUpdates();
        } catch (error) {
          log.e("Error playing audio:", [error]);
          setIsPlaying(false);
        }
      }
    }
  };

  const handleStop = () => {
    try {
      stopAudio();
      setIsPlaying(false);
      setPosition(0);
      setDragPosition(null);
      isDraggingRef.current = false;
      stopPositionUpdates();
    } catch (error) {
      log.e("Error stopping audio:", [error]);
    }
  };

  const handleSliderValueChange = (value: number) => {
    isDraggingRef.current = true;
    setDragPosition(value);
  };

  const handleSeek = (value: number) => {
    seekAudio(value);
    setPosition(value);
    setDragPosition(null);
    isDraggingRef.current = false;
  };

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  };
  const displayPosition = dragPosition ?? position;

  useEffect(() => {
    return () => {
      stopPositionUpdates();
      try {
        stopAudio();
      } catch (error) {
        log.e("Error cleaning up audio:", [error]);
      }
    };
  }, []);

  return (
    <NoahSafeAreaView className="flex-1 bg-background">
      <View className="p-4 flex-1">
        <View className="flex-row items-center mb-4">
          <Pressable onPress={() => navigation.goBack()} className="mr-4">
            <Icon name="arrow-back-outline" size={24} color={iconColor} />
          </Pressable>
          <Text className="text-2xl font-bold text-foreground">Noah's Ark Story</Text>
        </View>

        <View className="flex-1 items-center justify-center px-4">
          <View className="items-center mb-8">
            <Image
              source={logoImage}
              style={{ width: 200, height: 200, borderRadius: 20 }}
              resizeMode="contain"
            />
          </View>

          <View className="w-full mb-8">
            <Text className="text-center text-foreground text-lg mb-4">
              The Story of Noah's Ark
            </Text>
            <Text className="text-center text-muted-foreground text-sm">
              Listen to the biblical story that inspired our wallet's name
            </Text>
          </View>

          <View className="w-full px-4">
            <NativeNoahSlider
              style={{ width: "100%", height: 40 }}
              minimumValue={0}
              maximumValue={duration || 1}
              value={displayPosition}
              onValueChange={handleSliderValueChange}
              onSlidingComplete={handleSeek}
              minimumTrackTintColor="#F7931A"
              maximumTrackTintColor="#666666"
              thumbTintColor="#F7931A"
              disabled={!duration}
            />

            <View className="flex-row justify-between mb-6">
              <Text className="text-muted-foreground">{formatTime(displayPosition)}</Text>
              <Text className="text-muted-foreground">{formatTime(duration)}</Text>
            </View>

            <View className="flex-row justify-center items-center gap-6">
              <Pressable
                onPress={handleStop}
                disabled={!duration}
                className={`p-4 rounded-full active:opacity-50 ${!duration ? "bg-gray-700" : "bg-secondary"}`}
              >
                <Icon name="stop" size={32} color={!duration ? "#666666" : "#F7931A"} />
              </Pressable>

              <Pressable
                onPress={handlePlayPause}
                disabled={isLoading}
                className={`p-4 rounded-full active:opacity-50 ${isLoading ? "bg-gray-700" : "bg-secondary"}`}
              >
                {isLoading ? (
                  <Icon name="hourglass-outline" size={48} color="#666666" />
                ) : (
                  <Icon name={isPlaying ? "pause" : "play"} size={48} color="#F7931A" />
                )}
              </Pressable>
            </View>
          </View>
        </View>
      </View>
    </NoahSafeAreaView>
  );
};

export default NoahStoryScreen;
