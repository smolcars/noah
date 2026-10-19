import SwiftUI

/// Picks the first screen from `LaunchState`. Onboarding, restore and the
/// recovery-phrase prompt are placeholders until M1.
struct LaunchGateView: View {
    @State private var state: LaunchState?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch state {
            case nil:
                Color.clear
            case .existingWallet:
                RootView()
            case .onboarding:
                LaunchMessageView(
                    title: "Welcome to Noah",
                    message: "Onboarding lands in M1.")
            case .seedWithoutWalletData:
                LaunchMessageView(
                    title: "Recovery phrase found",
                    message: "A recovery phrase from a previous install is stored on this device. Restoring it lands in M1.")
            case .walletDataWithoutSeed:
                LaunchMessageView(
                    title: "Recovery phrase needed",
                    message: "Wallet data exists on this device but its recovery phrase could not be found.")
            case .secureStorageUnavailable:
                LaunchMessageView(
                    title: "Unlock your device",
                    message: "Noah can't read its secure storage yet. Unlock your device to continue.")
            }
        }
        .task { refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, state == .secureStorageUnavailable { refresh() }
        }
    }

    private func refresh() {
        state = LaunchState.current()
    }
}

private struct LaunchMessageView: View {
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Text(title)
                .font(.title.bold())
            Text(message)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
    }
}
