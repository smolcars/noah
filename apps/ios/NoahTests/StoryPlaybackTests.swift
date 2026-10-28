import AVFAudio
import Foundation
@testable import Noah
import Testing

@MainActor
struct StoryPlaybackTests {
    @Test func playbackBelongsToTheScreenLifetime() throws {
        let url = try #require(Bundle.main.url(forResource: "noahs-ark-story", withExtension: "m4a"))
        let player = try AVAudioPlayer(contentsOf: url)
        var screen: StoryPlayback? = StoryPlayback(player: player)
        weak let retained = screen
        player.currentTime = 12
        #expect(screen?.player === player)
        #expect(screen?.player.currentTime == 12)
        screen = nil
        #expect(retained == nil)
        #expect(!player.isPlaying)
        #expect(player.currentTime == 0)
    }
}
