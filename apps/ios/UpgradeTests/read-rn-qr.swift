import Darwin
import Foundation
import Vision

// Emit only public addresses, never the complete URI/invoice payload.
do {
    guard CommandLine.arguments.count == 2 else { throw NSError(domain: "Expected QR image path", code: 1) }
    let request = VNDetectBarcodesRequest()
    request.symbologies = [.qr]
    try VNImageRequestHandler(url: URL(fileURLWithPath: CommandLine.arguments[1])).perform([request])
    let addresses = (request.results ?? []).flatMap { barcode -> [[String: String]] in
        guard let value = barcode.payloadStringValue, let uri = URLComponents(string: value),
              uri.scheme == "bitcoin", uri.path.hasPrefix("bcrt1"),
              let ark = uri.queryItems?.first(where: { $0.name == "ark" })?.value,
              ark.hasPrefix("tark1") else { return [] }
        return [["ark": ark, "onchain": uri.path]]
    }
    guard addresses.count == 1 else { throw NSError(domain: "Expected one regtest QR", code: 1) }
    let json = try JSONSerialization.data(withJSONObject: addresses[0], options: [.sortedKeys])
    guard let text = String(bytes: json, encoding: .utf8) else { throw NSError(domain: "Expected UTF-8 JSON", code: 1) }
    print(text)
} catch {
    fputs("Could not decode one RN regtest receive QR\n", stderr)
    exit(1)
}
