import AVFAudio
import Foundation
import Testing

@testable import Noah

@MainActor
struct StoryPlaybackTests {
    @Test func playbackBelongsToTheScreenLifetime() throws {
        let url = try #require(Bundle.main.url(forResource: "noahs-ark-story", withExtension: "m4a"))
        let player = try AVAudioPlayer(contentsOf: url)
        var screen: StoryPlayback? = StoryPlayback(player: player)
        weak var retained = screen
        player.currentTime = 12
        #expect(screen?.player === player)
        #expect(screen?.player.currentTime == 12)
        screen = nil
        #expect(retained == nil)
        #expect(!player.isPlaying)
        #expect(player.currentTime == 0)
    }
}
