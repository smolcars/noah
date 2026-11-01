import Darwin
import Foundation
import Synchronization

/// How long a launch takes to show the wallet, in the app log: each milestone once per process, in milliseconds since
/// the process started. Never logs wallet data.
final class LaunchTiming: Sendable {
    enum Milestone: String, Sendable {
        case walletOpened = "Wallet open done"
        case summaryShown = "First summary shown"
        case historyShown = "First history rows shown"
    }

    static let shared = LaunchTiming(log: { AppLog("launch").i($0) })

    private let log: @Sendable (String) -> Void
    private let started: Date
    private let logged = Mutex<Set<Milestone>>([])

    init(log: @escaping @Sendable (String) -> Void, started: Date = LaunchTiming.processStart) {
        self.log = log
        self.started = started
    }

    func mark(_ milestone: Milestone, detail: String = "", now: Date = Date()) {
        guard logged.withLock({ $0.insert(milestone).inserted }) else { return }
        log("\(milestone.rawValue) at +\(Self.milliseconds(now.timeIntervalSince(started))) ms\(detail)")
    }

    static func milliseconds(_ interval: TimeInterval) -> Int { Int((interval * 1000).rounded()) }

    /// When the kernel started this process; now if it can't say.
    static let processStart: Date = {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var name: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&name, u_int(name.count), &info, &size, nil, 0) == 0 else { return Date() }
        let start = info.kp_proc.p_starttime
        return Date(timeIntervalSince1970: TimeInterval(start.tv_sec) + TimeInterval(start.tv_usec) / 1_000_000)
    }()
}

/// The stages of a foreground pass after a launch or a return to the front, in the app log as each starts and ends, in
/// milliseconds since the pass started.
struct PassTiming: Sendable {
    private let log: @Sendable (String) -> Void
    private let started: ContinuousClock.Instant

    init(log: @escaping @Sendable (String) -> Void = { AppLog("wallet").i($0) }, started: ContinuousClock.Instant = .now) {
        self.log = log
        self.started = started
    }

    func run(_ stage: String, _ work: @Sendable () async throws -> Void) async throws {
        log("Foreground pass: \(stage) started at +\(elapsed()) ms")
        do { try await work() } catch {
            log("Foreground pass: \(stage) failed at +\(elapsed()) ms")
            throw error
        }
        log("Foreground pass: \(stage) done at +\(elapsed()) ms")
    }

    private func elapsed() -> Int {
        let duration = started.duration(to: .now)
        return Int(duration.components.seconds * 1000 + duration.components.attoseconds / 1_000_000_000_000_000)
    }
}
