import Foundation
import Testing

/// Automatic boarding's deposit review runs only while `AutoBoardingService` is
/// mounted, and Home is its only host. The Home redesign once dropped it, so
/// this guards the mount itself: exactly one instance, on Home, under the
/// unsuspended gate, and outside the compact and wide layouts so a pose change
/// can't rebuild it mid-review.
struct HomeAutoBoardingTests {
    private static let sources = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "Noah")

    private static func swiftFiles() throws -> [URL] {
        let walker = try #require(FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil))
        return walker.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
    }

    @Test func homeMountsExactlyOneAutoBoardingService() throws {
        struct Mount { let file: String; let line: String; let previous: [String] }
        var mounts: [Mount] = []
        for file in try Self.swiftFiles() {
            let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: .newlines)
            for (index, line) in lines.enumerated() where line.contains("AutoBoardingService(")
                && !line.contains("struct AutoBoardingService") && !line.trimmingCharacters(in: .whitespaces).hasPrefix("//") {
                mounts.append(Mount(file: file.lastPathComponent, line: line, previous: Array(lines[max(0, index - 3)..<index])))
            }
        }
        #expect(mounts.count == 1, "\(mounts.map(\.file))")
        let mount = try #require(mounts.first)
        #expect(mount.file == "HomeView.swift")
        // Gated on the wallet not being suspended, as before the redesign.
        #expect((mount.previous + [mount.line]).contains { $0.contains("!model.isWalletSuspended") })
    }

    @Test func theMountSitsOutsideThePoseDependentLayouts() throws {
        let home = Self.sources.appending(path: "Features/Home/HomeView.swift")
        let source = try String(contentsOf: home, encoding: .utf8)
        let mount = try #require(source.range(of: "AutoBoardingService("))
        // Inside `root`, which both layouts share, not inside `compactColumn` or `widePanes`.
        let root = try #require(source.range(of: "private var root: some View {"))
        let compact = try #require(source.range(of: "private var compactColumn: some View {"))
        #expect(root.lowerBound < mount.lowerBound && mount.lowerBound < compact.lowerBound)
    }
}
