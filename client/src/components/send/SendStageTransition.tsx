import type { ReactNode } from "react";
import { View } from "react-native";
import Animated, {
  Easing,
  FadeIn,
  FadeOut,
  SlideInLeft,
  SlideInRight,
  useReducedMotion,
} from "react-native-reanimated";

import type { SendStage } from "~/lib/sendFlow";

type SendStageTransitionProps = {
  children: ReactNode;
  direction: "back" | "forward";
  stage: SendStage;
};

const TRANSITION_DURATION_MS = 240;
const REDUCED_MOTION_DURATION_MS = 140;

export function SendStageTransition({
  children,
  direction,
  stage,
}: SendStageTransitionProps) {
  const shouldReduceMotion = useReducedMotion();
  const entering = shouldReduceMotion
    ? FadeIn.duration(REDUCED_MOTION_DURATION_MS)
    : (direction === "forward" ? SlideInRight : SlideInLeft)
        .duration(TRANSITION_DURATION_MS)
        .easing(Easing.out(Easing.cubic));

  return (
    <View className="flex-1 overflow-hidden" testID="send-stage-transition">
      <Animated.View
        key={stage}
        className="absolute inset-0"
        entering={entering}
        exiting={FadeOut.duration(shouldReduceMotion ? REDUCED_MOTION_DURATION_MS : 120)}
      >
        {children}
      </Animated.View>
    </View>
  );
}
