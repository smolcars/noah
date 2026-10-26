import Foundation

/// Review deadlines (send, board and exit reviews). A review this process opened is timed on the continuous clock,
/// which keeps counting while the phone sleeps and which changing the device clock doesn't move, so a clock change
/// can't lengthen it. That reading is process-local and never persisted: a review read back from storage is timed by
/// its wall-clock `createdAt`, which stays the audit record either way.
enum ReviewClock {
    /// How long a review has been open.
    static func elapsed(since start: ContinuousClock.Instant?, createdAt: Date,
                        now: ContinuousClock.Instant = .now, wall: Date = Date()) -> Duration {
        start.map { $0.duration(to: now) } ?? .seconds(wall.timeIntervalSince(createdAt))
    }
}
