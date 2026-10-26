import SwiftUI
import Testing
import CoreImage
import CoreML
import Vision
@testable import Noah

/// The styled QR (dot modules, rounded eyes, logo hole) must still decode.
@MainActor
struct StyledQRCodeTests {
    static let addresses = ["a@noahwallet.io", "maya@noahwallet.io", "satoshi.nakamoto_21@signet.noahwallet.io",
                            "lightning-address-with-a-long-name@localhost.com"]

    /// Home's get-paid card as drawn: logo in the middle, at every symbol size the addresses take;
    /// white in both themes, so the dark theme checks one.
    @Test func getPaidCardDecodes() throws {
        let cards: [(String, ColorScheme)] = Self.addresses.map { ($0, ColorScheme.light) } + [(Self.addresses[Self.addresses.count - 1], .dark)]
        for (address, scheme) in cards {
            let card = HomeQRCard(address: address, qrSide: 212, unit: .bip177, hidden: false)
                .frame(width: 345)
                .padding(24)
                .background(NoahTokens.Colors.background)
                .environment(\.colorScheme, scheme)
            #expect(try Self.decode(card) == [address], "\(address) in \(scheme)")
        }
    }

    @Test func matrixMatchesTheSymbolSizeAndKeepsFinderEyes() throws {
        let matrix = try #require(QRMatrix("maya@noahwallet.io"))
        #expect((matrix.count - 21) % 4 == 0)
        // Finder corners are dark, the separator next to them light.
        for (row, column) in [(0, 0), (0, matrix.count - 1), (matrix.count - 1, 0)] { #expect(matrix.isDark(row, column)) }
        #expect(!matrix.isDark(7, 7))
        let geometry = QRGeometry(count: matrix.count, side: 212)
        #expect(geometry.logoModules % 2 == 1)
        #expect(geometry.logoStart * 2 + geometry.logoModules == matrix.count)
        #expect(!geometry.inLogoHole(geometry.logoStart - 2, geometry.logoStart))
        #expect(geometry.inLogoHole(geometry.logoStart - 1, geometry.logoStart + geometry.logoModules))
    }

    /// Payloads found in a rendered view: Vision on the CPU, or CoreImage's
    /// detector where the simulator has no Vision inference context.
    static func decode(_ view: some View, scale: CGFloat = 3) throws -> [String] {
        let renderer = ImageRenderer(content: view)
        renderer.scale = scale
        let image = try #require(renderer.cgImage)
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        if let cpu = MLComputeDevice.allComputeDevices.first(where: { if case .cpu = $0 { true } else { false } }) {
            request.setComputeDevice(cpu, for: .main)
        }
        let vision = (try? VNImageRequestHandler(cgImage: image).perform([request])) != nil
            ? (request.results ?? []).compactMap(\.payloadStringValue) : []
        guard vision.isEmpty else { return vision }
        let detector = try #require(CIDetector(ofType: CIDetectorTypeQRCode, context: nil,
                                               options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]))
        return detector.features(in: CIImage(cgImage: image)).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }
}
