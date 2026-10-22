import SwiftUI

/// "You’re all set": the orange ring draws, the check follows, two ripples.
struct DoneScreen: View {
    let layout: OnboardingLayout
    let address: String?
    let finishing: Bool
    let onOpen: () -> Void

    /// Compact: ring and words centred, the button at the bottom. Wide: the
    /// ring leads; the words and the button are centred together beside it.
    var body: some View {
        OnboardingPanes(wide: layout.wide, column: .trailingFits, sideInsets: (layout.insets.leading, layout.insets.trailing)) {
            VStack(spacing: 34) {
                DoneRing().scaleEffect(layout.wide ? 1.5 : 1).padding(layout.wide ? 24 : 0)
                if !layout.wide { headline }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onboardingPane()
            VStack(alignment: .leading, spacing: 0) {
                Spacer(minLength: 0).frame(maxHeight: layout.wide ? .infinity : 0)
                if layout.wide { headline.padding(.bottom, NoahTokens.Space.xxl) }
                Button(action: onOpen) {
                    ZStack {
                        Text("Open wallet").opacity(finishing ? 0 : 1)
                        if finishing { ProgressView().tint(NoahTokens.Colors.background) }
                    }
                }
                .buttonStyle(OnboardingPrimaryButtonStyle())
                .allowsHitTesting(!finishing)
                .accessibilityIdentifier("onboarding-open-wallet")
                .onboardingAppear(5)
                .padding(.bottom, NoahTokens.Space.l)
                Spacer(minLength: 0).frame(maxHeight: layout.wide ? .infinity : 0)
            }
            .onboardingPane()
        }
    }

    private var headline: some View {
        OnboardingHeadline(title: Text("You’re all set"),
                           subtitle: address.map { Text(verbatim: $0) } ?? Text("Your wallet is ready."),
                           large: layout.wide, centered: !layout.wide)
    }
}

private struct Ripple {
    var scale: CGFloat = 1
    var opacity: Double = 0
}

private struct DoneRing: View {
    @State private var ring = false
    @State private var check = false
    @State private var bounce = false
    @State private var ripples = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            ForEach(0..<2, id: \.self) { index in
                Circle().stroke(NoahTokens.Colors.orange, lineWidth: 1.5)
                    .keyframeAnimator(initialValue: Ripple(), trigger: ripples) { content, ripple in
                        content.scaleEffect(ripple.scale).opacity(ripple.opacity)
                    } keyframes: { _ in
                        KeyframeTrack(\.opacity) {
                            MoveKeyframe(0)
                            MoveKeyframe(0)
                            LinearKeyframe(0, duration: Double(index) * 0.26)
                            MoveKeyframe(0.7)
                            LinearKeyframe(0, duration: 1.4)
                        }
                        KeyframeTrack(\.scale) {
                            MoveKeyframe(1)
                            LinearKeyframe(1, duration: Double(index) * 0.26)
                            SpringKeyframe(2.3, duration: 1.4, spring: Spring(response: 0.5, dampingRatio: 1))
                        }
                    }
            }
            Circle().trim(from: 0, to: ring ? 1 : 0)
                .stroke(NoahTokens.Colors.orange, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(1.25)
            CheckShape(points: [CGPoint(x: 31, y: 49), CGPoint(x: 42, y: 60), CGPoint(x: 65, y: 36)], box: 96)
                .trim(from: 0, to: check ? 1 : 0)
                .stroke(NoahTokens.Colors.foreground, style: StrokeStyle(lineWidth: 3.2, lineCap: .round, lineJoin: .round))
        }
        .frame(width: 96, height: 96)
        .scaleEffect(bounce ? 1.08 : 1)
        .accessibilityHidden(true)
        .task {
            if reduceMotion { ring = true; check = true; return }
            withAnimation(NoahTokens.Motion.soft.delay(0.12)) { ring = true }
            withAnimation(NoahTokens.Motion.snappy.delay(0.84)) { check = true }
            try? await Task.sleep(for: .seconds(1))
            ripples.toggle()
            withAnimation(NoahTokens.Motion.bouncy) { bounce = true }
            try? await Task.sleep(for: .milliseconds(160))
            withAnimation(NoahTokens.Motion.bouncy) { bounce = false }
        }
    }
}
