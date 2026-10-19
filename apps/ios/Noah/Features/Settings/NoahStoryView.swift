import AVFAudio
import SwiftUI

struct NoahStoryView: View {
    @State private var player: AVAudioPlayer?
    @State private var position: Double = 0
    @State private var playing = false
    @State private var seeking = false
    @State private var error: String?
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Image("ReceiveLogo").resizable().scaledToFit().frame(height: 200).accessibilityHidden(true)
                Text("The Story of Noah's Ark").font(NoahFont.title)
                Text("Listen to the biblical story that inspired our wallet's name")
                    .foregroundStyle(NoahColor.mutedForeground).multilineTextAlignment(.center)
                Slider(value: $position, in: 0...max(player?.duration ?? 1, 1), onEditingChanged: { editing in
                    seeking = editing
                    if !editing { player?.currentTime = position }
                }).accessibilityLabel("Playback position").disabled(player == nil)
                HStack {
                    Text(Duration.seconds(position).formatted(.time(pattern: .minuteSecond)))
                    Spacer()
                    Text(Duration.seconds(player?.duration ?? 0).formatted(.time(pattern: .minuteSecond)))
                }.monospacedDigit().font(NoahFont.footnote)
                NoahButton(title: playing ? "Pause" : "Play") {
                    if playing { player?.pause() } else {
                        if position >= (player?.duration ?? 0) { player?.currentTime = 0 }
                        player?.play()
                    }
                    playing = player?.isPlaying == true
                }.disabled(player == nil)
                NoahButton(title: "Stop", kind: .secondary) { player?.stop(); player?.currentTime = 0; position = 0; playing = false }
                    .disabled(player == nil)
                if let error { Text(error).foregroundStyle(NoahColor.destructive) }
            }.padding(28).frame(maxWidth: 560).frame(maxWidth: .infinity)
        }.settingsStyle("Noah's Ark Story")
        .task {
            do {
                guard let url = Bundle.main.url(forResource: "noahs-ark-story", withExtension: "m4a") else { throw CocoaError(.fileNoSuchFile) }
                try AVAudioSession.sharedInstance().setCategory(.playback)
                player = try AVAudioPlayer(contentsOf: url)
                while !Task.isCancelled {
                    if !seeking { position = player?.currentTime ?? 0 }
                    playing = player?.isPlaying == true
                    try await Task.sleep(for: .milliseconds(500))
                }
            } catch is CancellationError {} catch { self.error = "Couldn't play the story. Please try again." }
        }
        .onDisappear { player?.stop(); player = nil; try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }
}
