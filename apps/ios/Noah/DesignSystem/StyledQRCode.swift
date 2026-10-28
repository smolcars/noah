import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// QR modules for a payload, without the quiet zone. Short payloads use error
/// correction H; long ones (a payment request with an invoice) use M, which
/// still leaves room for the logo hole (prototype `buildQR`).
struct QRMatrix: Equatable, Sendable {
    let count: Int
    private let modules: [Bool]

    static func correction(for text: String) -> String { text.utf8.count > 90 ? #"M"# : #"H"# }

    init?(_ text: String) {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = Self.correction(for: text)
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        let width = image.width, height = image.height
        var pixels = [UInt8](repeating: 255, count: width * height)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(),
                                          bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        // The finder patterns fill three corners, so the dark bounds are the symbol.
        var minX = width, minY = height, maxX = -1, maxY = -1
        for y in 0..<height {
            for x in 0..<width where pixels[y * width + x] < 128 {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        let count = maxX - minX + 1
        guard maxX >= 0, count == maxY - minY + 1, count >= 21 else { return nil }
        self.count = count
        modules = (0..<count * count).map { pixels[(minY + $0 / count) * width + minX + $0 % count] < 128 }
    }

    func isDark(_ row: Int, _ column: Int) -> Bool {
        row >= 0 && column >= 0 && row < count && column < count && modules[row * count + column]
    }
}

/// The prototype's `buildQR` geometry: round dots, three rounded finder eyes
/// and a hole in the middle for the logo tile.
struct QRGeometry {
    let count: Int
    let side: CGFloat

    var module: CGFloat { side / CGFloat(count) }
    /// Odd, about 22% of the symbol.
    var logoModules: Int { Int((Double(count) * 0.22).rounded()) | 1 }
    var logoStart: Int { (count - logoModules) / 2 }
    var eyes: [CGPoint] {
        [.zero, CGPoint(x: CGFloat(count - 7) * module, y: 0), CGPoint(x: 0, y: CGFloat(count - 7) * module)]
    }

    func isEye(_ row: Int, _ column: Int) -> Bool {
        (row < 7 && column < 7) || (row < 7 && column >= count - 7) || (row >= count - 7 && column < 7)
    }

    func inLogoHole(_ row: Int, _ column: Int) -> Bool {
        let range = (logoStart - 1)..<(logoStart + logoModules + 1)
        return range.contains(row) && range.contains(column)
    }

    /// The white tile behind the logo, and the logo inside it.
    var logoTile: CGRect {
        let pad = module * 0.6
        return logoRect.insetBy(dx: -pad, dy: -pad)
    }
    var logoRect: CGRect {
        CGRect(x: CGFloat(logoStart) * module, y: CGFloat(logoStart) * module,
               width: CGFloat(logoModules) * module, height: CGFloat(logoModules) * module)
    }
}

/// The styled receive QR: always near-black on white, with the longship app
/// icon in the centre. Draws in once when `animate` is set (prototype `playQR`);
/// `reveal` only redraws the dots from the centre (a format change, prototype `buildQR(ripple)`).
struct StyledQRCode: View {
    let content: String
    private let animate: Bool
    private let reveal: Bool
    @State private var dots: Bool
    @State private var eyes: [Bool]
    @State private var logo: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(content: String, animate: Bool = false) {
        self.content = content
        self.animate = animate
        reveal = false
        _dots = State(initialValue: !animate)
        _eyes = State(initialValue: Array(repeating: !animate, count: 3))
        _logo = State(initialValue: !animate)
    }

    init(content: String, reveal: Bool) {
        self.content = content
        animate = false
        self.reveal = reveal
        _dots = State(initialValue: !reveal)
        _eyes = State(initialValue: [true, true, true])
        _logo = State(initialValue: true)
    }

    var body: some View {
        if let matrix = QRMatrixCache.matrix(content) {
            GeometryReader { proxy in
                let side = min(proxy.size.width, proxy.size.height)
                let geometry = QRGeometry(count: matrix.count, side: side)
                ZStack(alignment: .topLeading) {
                    QRDots(matrix: matrix, geometry: geometry)
                        .fill(NoahTokens.Colors.qrInk)
                        .mask {
                            // CSS circle(72% at 50% 50%) on a square: a radius of 0.72 × side.
                            Circle()
                                .frame(width: side * 1.44, height: side * 1.44)
                                .scaleEffect(dots || reduceMotion ? 1 : 0.001)
                                .position(x: side / 2, y: side / 2)
                        }
                        .opacity(dots || !reduceMotion ? 1 : 0)
                    ForEach(0..<3, id: \.self) { index in
                        let shown = eyes[index] || reduceMotion
                        QREye(module: geometry.module)
                            .fill(NoahTokens.Colors.qrInk, style: FillStyle(eoFill: true))
                            .frame(width: geometry.module * 7, height: geometry.module * 7)
                            .scaleEffect(shown ? 1 : 0.4)
                            .rotationEffect(.degrees(shown ? 0 : -45))
                            .opacity(shown ? 1 : 0)
                            .offset(x: geometry.eyes[index].x, y: geometry.eyes[index].y)
                    }
                    QRLogoTile(geometry: geometry)
                        .scaleEffect(logo || reduceMotion ? 1 : 0.3, anchor: UnitPoint(x: 0.5, y: 0.5))
                        .rotationEffect(.degrees(logo || reduceMotion ? 0 : -12))
                        .opacity(logo || reduceMotion ? 1 : 0)
                }
                .frame(width: side, height: side)
            }
            .aspectRatio(1, contentMode: .fit)
            .accessibilityHidden(true)
            .onAppear(perform: drawIn)
        } else {
            Text("QR code unavailable. Use payment details to copy your request.")
                .foregroundStyle(NoahTokens.Colors.qrInk).padding()
        }
    }

    private func drawIn() {
        if reveal, !dots {
            withAnimation(reduceMotion ? NoahTokens.Motion.reduceMotionFade : NoahTokens.Motion.soft) { dots = true }
            return
        }
        guard animate, !dots else { return }
        if reduceMotion {
            withAnimation(NoahTokens.Motion.reduceMotionFade.delay(0.2)) { dots = true }
            eyes = [true, true, true]
            logo = true
            return
        }
        withAnimation(NoahTokens.Motion.soft.delay(0.32)) { dots = true }
        withAnimation(NoahTokens.Motion.bouncy.delay(0.38)) { logo = true }
        for index in 0..<3 {
            withAnimation(NoahTokens.Motion.bouncy.delay(0.56 + Double(index) * 0.11)) { eyes[index] = true }
        }
    }
}

/// Matrices are built once per payload (CoreImage work is too slow for every body pass).
@MainActor
enum QRMatrixCache {
    private static var values: [String: QRMatrix] = [:]

    static func matrix(_ content: String) -> QRMatrix? {
        if let cached = values[content] { return cached }
        guard let matrix = QRMatrix(content) else { return nil }
        if values.count > 8 { values.removeAll() }
        values[content] = matrix
        return matrix
    }
}

private struct QRDots: Shape {
    let matrix: QRMatrix
    let geometry: QRGeometry

    func path(in _: CGRect) -> Path {
        var path = Path()
        let module = geometry.module, radius = module * 0.44
        for row in 0..<matrix.count {
            for column in 0..<matrix.count where matrix.isDark(row, column)
                && !geometry.isEye(row, column) && !geometry.inLogoHole(row, column) {
                let center = CGPoint(x: (CGFloat(column) + 0.5) * module, y: (CGFloat(row) + 0.5) * module)
                path.addEllipse(in: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
            }
        }
        return path
    }
}

/// A finder eye: a 7-module ring around a 3-module centre, all corners rounded. Fill even-odd.
private struct QREye: Shape {
    let module: CGFloat

    func path(in _: CGRect) -> Path {
        var path = Path()
        func add(_ inset: CGFloat, _ size: CGFloat, _ radius: CGFloat) {
            path.addRoundedRect(in: CGRect(x: inset * module, y: inset * module, width: size * module, height: size * module),
                                cornerSize: CGSize(width: radius * module, height: radius * module), style: .circular)
        }
        add(0, 7, 2.4)
        add(1, 5, 1.5)
        add(2, 3, 1.1)
        return path
    }
}

private struct QRLogoTile: View {
    let geometry: QRGeometry

    var body: some View {
        let tile = geometry.logoTile, logo = geometry.logoRect
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: tile.width * 0.26, style: .circular)
                .fill(Color.white)
                .frame(width: tile.width, height: tile.height)
                .offset(x: tile.minX, y: tile.minY)
            Image(.qrLogo)
                .resizable()
                .accessibilityHidden(true)
                .scaledToFill()
                .frame(width: logo.width, height: logo.height)
                .clipShape(RoundedRectangle(cornerRadius: logo.width * 0.23, style: .circular))
                .offset(x: logo.minX, y: logo.minY)
        }
        .frame(width: geometry.side, height: geometry.side, alignment: .topLeading)
    }
}
