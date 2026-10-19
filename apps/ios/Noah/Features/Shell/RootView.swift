import SwiftUI

/// Picks onboarding, the wallet tabs or a blocking message from `AppModel`.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            switch model.phase {
            case .launching:
                NoahColor.background.ignoresSafeArea()
            case .onboarding:
                OnboardingFlow()
            case .wallet:
                WalletTabs()
                    // Server registration (and with it the push token) lands with
                    // the registration work; ask for permission once in the wallet.
                    .task { _ = await PushRegistration.shared.requestPermission() }
            case .blocked(let state):
                LaunchMessageView(state: state)
            }
        }
        .task { await model.start() }
    }
}

/// Home, Receive, Send, History, each with its own navigation stack.
struct WalletTabs: View {
    enum Tab: Hashable {
        case home, receive, send, history
    }

    @State private var selection: Tab = .home

    var body: some View {
        TabView(selection: $selection) {
            SwiftUI.Tab("Home", systemImage: "house", value: .home) {
                HomeView()
            }
            SwiftUI.Tab("Receive", systemImage: "arrow.down.left", value: .receive) {
                NavigationStack { PendingFeatureView(title: "Receive") }
            }
            SwiftUI.Tab("Send", systemImage: "arrow.up.right", value: .send) {
                NavigationStack { PendingFeatureView(title: "Send") }
            }
            SwiftUI.Tab("History", systemImage: "clock.arrow.circlepath", value: .history) {
                NavigationStack { PendingFeatureView(title: "History") }
            }
        }
        .tint(NoahColor.brand)
        .tabBarMinimizeOnScrollIfAvailable()
    }
}

private struct LaunchMessageView: View {
    let state: LaunchState

    var body: some View {
        VStack(spacing: 12) {
            Text(title)
                .font(NoahFont.title)
                .foregroundStyle(NoahColor.foreground)
            Text(message)
                .font(NoahFont.body)
                .foregroundStyle(NoahColor.mutedForeground)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(NoahColor.background.ignoresSafeArea())
    }

    private var title: LocalizedStringKey {
        switch state {
        case .seedWithoutWalletData: "Recovery phrase found"
        case .walletDataWithoutSeed: "Recovery phrase needed"
        case .secureStorageUnavailable: "Unlock your device"
        case .existingWallet, .onboarding: ""
        }
    }

    private var message: LocalizedStringKey {
        switch state {
        case .seedWithoutWalletData:
            "A recovery phrase from a previous install is stored on this device. Restoring it lands soon."
        case .walletDataWithoutSeed:
            "Wallet data exists on this device but its recovery phrase could not be found."
        case .secureStorageUnavailable:
            "Noah can't read its secure storage yet. Unlock your device to continue."
        case .existingWallet, .onboarding:
            ""
        }
    }
}

private extension View {
    @ViewBuilder
    func tabBarMinimizeOnScrollIfAvailable() -> some View {
        if #available(iOS 26, *) {
            tabBarMinimizeBehavior(.onScrollDown)
        } else {
            self
        }
    }
}
