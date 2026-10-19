import SwiftUI

/// Main tab shell. Tabs mirror the React Native app: Home, Receive, Send,
/// History. Each tab owns its own navigation stack.
struct RootView: View {
    enum Tab: Hashable {
        case home, receive, send, history
    }

    @State private var selection: Tab = .home

    var body: some View {
        TabView(selection: $selection) {
            SwiftUI.Tab("Home", systemImage: "house", value: .home) {
                PlaceholderScreen(title: "Home")
            }
            SwiftUI.Tab("Receive", systemImage: "arrow.down.left", value: .receive) {
                PlaceholderScreen(title: "Receive")
            }
            SwiftUI.Tab("Send", systemImage: "arrow.up.right", value: .send) {
                PlaceholderScreen(title: "Send")
            }
            SwiftUI.Tab("History", systemImage: "clock", value: .history) {
                PlaceholderScreen(title: "History")
            }
        }
        .tabBarMinimizeOnScrollIfAvailable()
    }
}

private struct PlaceholderScreen: View {
    let title: String

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    Text(title)
                        .font(.largeTitle.bold())
                    Text(AppVariant.current.rawValue.capitalized)
                        .font(.subheadline)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .glassCompat(in: Capsule())
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 80)
            }
            .navigationTitle(title)
            .toolbarTitleDisplayMode(.inline)
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

#Preview {
    RootView()
}
