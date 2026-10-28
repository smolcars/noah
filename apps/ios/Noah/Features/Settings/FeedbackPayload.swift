import Foundation

struct FeedbackDraft {
    static let maximumScreenshotBytes = 5 * 1024 * 1024
    var name = ""
    var email = ""
    var subject = ""
    var body = ""
    var screenshot: Data?

    func payload(device: Components.Schemas.DeviceInfo) throws -> Components.Schemas.SubmitSupportTicketPayload {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let email = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let subject = subject.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !subject.isEmpty, !body.isEmpty else {
            throw IdentityValidationError("Name, subject, and message are required.")
        }
        // The Rust server limits UTF-8 bytes, not Swift grapheme counts.
        guard name.utf8.count <= 150, subject.utf8.count <= 150, body.utf8.count <= 60_000 else {
            throw IdentityValidationError("Name and subject must be at most 150 bytes, and message at most 60,000 bytes.")
        }
        let attachment = try screenshot.map(Self.attachment)
        return .init(attachment: attachment, body: body, deviceInfo: .init(
            appBuild: device.appBuild, appVersion: device.appVersion, deviceManufacturer: device.deviceManufacturer,
            deviceModel: device.deviceModel, osName: device.osName, osVersion: device.osVersion),
        email: email.isEmpty ? nil : email, name: name, subject: subject)
    }

    static func attachment(_ data: Data) throws -> Components.Schemas.SubmitSupportTicketPayload.AttachmentPayload {
        guard data.count <= maximumScreenshotBytes else { throw IdentityValidationError("Screenshot must be 5 MB or smaller.") }
        let png = data.starts(with: [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        let jpeg = data.starts(with: [0xFF, 0xD8, 0xFF])
        guard png || jpeg else { throw IdentityValidationError("Screenshot must be a JPEG or PNG image.") }
        return .init(base64Data: data.base64EncodedString(), contentType: png ? "image/png" : "image/jpeg", filename: png ? "screenshot.png" : "screenshot.jpg")
    }
}
