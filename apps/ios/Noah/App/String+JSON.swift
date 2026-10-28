import Foundation

extension String {
    /// The text of JSON that JSONEncoder or JSONSerialization just wrote, which is always UTF-8.
    init(json data: Data) throws {
        guard let text = String(bytes: data, encoding: .utf8) else { throw CocoaError(.fileReadInapplicableStringEncoding) }
        self = text
    }
}
