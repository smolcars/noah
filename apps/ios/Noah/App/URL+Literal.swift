import Foundation

extension URL {
    /// A URL written in the source. A malformed one is a programming error, caught on first use.
    init(literal: StaticString) {
        guard let url = URL(string: "\(literal)") else { preconditionFailure("malformed URL literal: \(literal)") }
        self = url
    }
}
