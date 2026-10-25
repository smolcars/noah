import AVFAudio
import SwiftUI

/// SwiftUI retains State across tab switches; release audio only when this screen is removed.
final class StoryPlayback {
    let player: AVAudioPlayer
    init(player: AVAudioPlayer) { self.player = player }
    deinit {
        player.stop()
        player.currentTime = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// Help → About Noah: the wordmark and version, the story of Noah's Ark, and the links.
struct NoahStoryView: View {
    static let telegram = URL(string: "https://t.me/blixtwallet/605026")!
    static let github = URL(string: "https://github.com/smolcars/noah")!
    static let privacy = URL(string: "https://github.com/smolcars/noah/blob/master/PRIVACY.md")!

    @Environment(SettingsSummary.self) private var summary: SettingsSummary?
    @Environment(\.settings) private var settings
    @Environment(\.openURL) private var openURL
    @Environment(\.locale) private var locale
    @State private var playback: StoryPlayback?
    @State private var position: Double = 0
    @State private var playing = false
    @State private var error: String?
    @State private var unlock = DebugUnlock()
    @State private var haptics = OnboardingHaptics()

    private var player: AVAudioPlayer? { playback?.player }
    private var language: String { locale.identifier }

    var body: some View {
        SettingsScroll {
            VStack(alignment: .leading, spacing: 10) {
                Text(verbatim: "Noah").font(NoahTokens.Typography.wordmark.font(size: 64)).tracking(-0.045 * 64)
                    .padding(.top, 18)
                    .accessibilityAddTraits(.isHeader)
                Button(action: versionTapped) {
                    Text(verbatim: L10n.text("Version {version}", SettingsList.version, locale: language)
                         + (summary?.debug == true ? " · " + L10n.text("debug", locale: language) : ""))
                        .noahText(HomeText.amount).monospacedDigit().foregroundStyle(NoahTokens.Colors.muted)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("about-version")
            }
            player(duration: player?.duration ?? 0).padding(.top, 22)
            SettingsFine(text: L10n.text("The story that gave Noah its name.", locale: language))
            if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
            SettingsGroup {
                link("TelegramMark", "Telegram", Self.telegram)
                link("GitHubMark", "Source code", Self.github)
                link("hand.raised", "Privacy policy", Self.privacy, last: true)
            }
        }
        .settingsChrome(nil)
        .onboardingHaptics(haptics)
        .task {
            do {
                if playback == nil {
                    guard let url = Bundle.main.url(forResource: "noahs-ark-story", withExtension: "m4a") else { throw CocoaError(.fileNoSuchFile) }
                    try AVAudioSession.sharedInstance().setCategory(.playback)
                    playback = StoryPlayback(player: try AVAudioPlayer(contentsOf: url))
                }
                while !Task.isCancelled {
                    position = player?.currentTime ?? 0
                    playing = player?.isPlaying == true
                    try await Task.sleep(for: .milliseconds(500))
                }
            } catch is CancellationError {} catch { self.error = "Couldn't play the story. Please try again." }
        }
    }

    /// Play or pause, the story's progress and its times.
    private func player(duration: Double) -> some View {
        HStack(spacing: 14) {
            Button {
                haptics.play(.light)
                if playing { player?.pause() } else {
                    if position >= duration { player?.currentTime = 0 }
                    player?.play()
                }
                playing = player?.isPlaying == true
            } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill").font(.system(size: 17))
                    .foregroundStyle(NoahTokens.Colors.background)
                    .frame(width: 44, height: 44)
                    .background(NoahTokens.Colors.foreground, in: Circle())
            }
            .buttonStyle(HomePressStyle())
            .disabled(player == nil)
            .accessibilityLabel(Text(playing ? "Pause" : "Play"))
            VStack(alignment: .leading, spacing: 0) {
                Text(L10n.text("The story of Noah’s Ark", locale: language)).noahText(NoahTokens.Typography.control)
                Slider(value: Binding(get: { position }, set: { position = $0; player?.currentTime = $0 }), in: 0...max(duration, 1))
                    .tint(NoahTokens.Colors.foreground)
                    .disabled(player == nil)
                    .accessibilityLabel(Text("Playback position"))
                HStack {
                    Text(Duration.seconds(position).formatted(.time(pattern: .minuteSecond)))
                    Spacer()
                    Text(Duration.seconds(duration).formatted(.time(pattern: .minuteSecond)))
                }
                .font(NoahTokens.Typography.caption.font(size: 11)).monospacedDigit().foregroundStyle(NoahTokens.Colors.muted)
            }
        }
        .padding(.leading, 14).padding(.trailing, 16).padding(.vertical, 14)
        .glassCompat(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func link(_ icon: String, _ title: String, _ url: URL, last: Bool = false) -> some View {
        SettingsRow(icon: icon, title: L10n.text(title, locale: language), trailing: .external, hairline: !last) {
            haptics.play(.light)
            openURL(url)
        }
    }

    private func versionTapped() {
        haptics.play(.selection)
        guard summary?.debug != true else { settings.toast("Debug is on"); return }
        if unlock.tap() {
            try? summary?.setDebug(true)
            haptics.play(.success)
            settings.toast("Debug on")
        } else if unlock.count > 1 {
            settings.toast(L10n.text("{n} more to turn on debug", unlock.remaining, locale: language))
        }
    }
}
