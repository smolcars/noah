import SwiftUI

/// Fresh Home's empty state: your Lightning address as a styled QR on a white
/// card, Copy and Share below. When the first payment lands, the card turns
/// into its receipt (prototype `firstPayment`).
struct HomeGetPaidCard: View {
    let address: String?
    let qrSide: CGFloat
    var celebration: HomeActivityTracker.Celebration?
    var animateQR = false
    let unit: BitcoinAmountUnit
    let hidden: Bool
    let onCopy: () -> Void
    let onShare: () -> Void
    let onProfile: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 12) {
            if address != nil {
                Button(action: onCopy) {
                    HomeQRCard(address: address, qrSide: qrSide, celebration: celebration, animateQR: animateQR,
                               unit: unit, hidden: hidden)
                }
                .buttonStyle(HomePressStyle(scale: 0.985))
            } else {
                // No address yet: the card holds the Profile action, so it can't sit inside the copy button.
                HomeQRCard(address: nil, qrSide: qrSide, celebration: celebration, unit: unit, hidden: hidden,
                           onProfile: onProfile)
            }
            if let address {
                HomeCardActions(address: address, celebration: celebration, onCopy: onCopy, onShare: onShare)
            }
        }
    }
}

/// The white card itself; also what the QR decode test renders.
struct HomeQRCard: View {
    let address: String?
    let qrSide: CGFloat
    var celebration: HomeActivityTracker.Celebration?
    var animateQR = false
    let unit: BitcoinAmountUnit
    let hidden: Bool
    var onProfile: () -> Void = {}
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                if let address {
                    StyledQRCode(content: address, animate: animateQR)
                        .frame(width: qrSide, height: qrSide)
                    Text(verbatim: address)
                        .noahText(NoahTokens.Typography.button)
                        .foregroundStyle(NoahTokens.Colors.qrInk)
                        .lineLimit(1).truncationMode(.middle)
                        .padding(.top, 14)
                    Text("Scan with any Lightning wallet")
                        .noahText(HomeText.footnote)
                        .foregroundStyle(Color(hex: 0x6B6B6B))
                        .padding(.top, 2)
                } else {
                    VStack(alignment: .leading, spacing: NoahTokens.Space.s) {
                        Text("Lightning address unavailable").noahText(NoahTokens.Typography.button)
                        Text("Finish setting up your Lightning address before sharing your QR.")
                            .noahText(HomeText.footnote).foregroundStyle(Color(hex: 0x6B6B6B))
                        Button("Open Profile", action: onProfile)
                            .noahText(HomeText.strong)
                            .foregroundStyle(NoahTokens.Colors.orangeText)
                            .padding(.top, NoahTokens.Space.xs)
                    }
                    .foregroundStyle(NoahTokens.Colors.qrInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
            .modifier(HomeReceiptFade(celebration: celebration))
            if let celebration {
                HomeReceipt(celebration: celebration, unit: unit, hidden: hidden)
            }
        }
        .padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 18)
        .frame(maxWidth: .infinity)
        // Only the card casts the shadow, never the QR's dots.
        .background {
            RoundedRectangle(cornerRadius: NoahTokens.Radius.card, style: .continuous)
                .fill(NoahTokens.Colors.qrCard)
                .shadow(color: .black.opacity(colorScheme == .dark ? 0.6 : 0.08), radius: 20, y: 14)
        }
        .overlay {
            if colorScheme == .light {
                RoundedRectangle(cornerRadius: NoahTokens.Radius.card, style: .continuous)
                    .strokeBorder(Color.black.opacity(0.07), lineWidth: 1)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct HomeCardActions: View {
    let address: String
    let celebration: HomeActivityTracker.Celebration?
    let onCopy: () -> Void
    let onShare: () -> Void
    @State private var faded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GlassGroup(spacing: 10) {
            HStack(spacing: 10) {
                Button(action: onCopy) { Text("Copy").frame(maxWidth: .infinity) }
                    .buttonStyle(HomePillStyle(prominent: false, height: NoahTokens.Size.buttonCompact, font: NoahTokens.Typography.button))
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("home-copy-address")
                ShareLink(item: address) { Text("Share").frame(maxWidth: .infinity) }
                    .buttonStyle(HomePillStyle(height: NoahTokens.Size.buttonCompact, font: NoahTokens.Typography.button))
                    .simultaneousGesture(TapGesture().onEnded(onShare))
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("home-share-address")
            }
        }
        .opacity(faded ? 0 : 1)
        .allowsHitTesting(!faded)
        // Half a second into the first payment; a rebuilt card (pose change) starts faded.
        .task(id: celebration?.id) {
            guard let celebration, !faded else { return }
            let wait = 0.5 - Date().timeIntervalSince(celebration.start)
            guard wait > 0 else { faded = true; return }
            do { try await Task.sleep(for: .seconds(wait)) } catch { return }
            withAnimation(NoahTokens.Motion.travel(reduceMotion)) { faded = true }
        }
    }
}

/// The QR blurs out under the receipt.
private struct HomeReceiptFade: ViewModifier {
    let celebration: HomeActivityTracker.Celebration?
    @State private var out = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(out ? 0 : 1)
            .scaleEffect(out && !reduceMotion ? 0.86 : 1)
            .blur(radius: out && !reduceMotion ? 6 : 0)
            .onAppear { if celebration != nil { out = true } }
            .onChange(of: celebration?.id) { _, id in
                withAnimation(NoahTokens.Motion.travel(reduceMotion)) { out = id != nil }
            }
    }
}

/// Orange ring and check, the amount, then the sender when the record names one.
/// Steps follow the celebration's start time, so a rebuilt card (a pose change)
/// continues where it was instead of replaying.
private struct HomeReceipt: View {
    let celebration: HomeActivityTracker.Celebration
    let unit: BitcoinAmountUnit
    let hidden: Bool
    @State private var shown = false
    @State private var ring = false
    @State private var amount = false
    @State private var check = false
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                // Reduce Motion draws the complete ring and check and only fades them in.
                Circle().trim(from: 0, to: ring || reduceMotion ? 1 : 0)
                    .stroke(NoahTokens.Colors.orange, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(1.25)
                    .opacity(ring || !reduceMotion ? 1 : 0)
                CheckShape(points: [CGPoint(x: 27, y: 43), CGPoint(x: 37, y: 53), CGPoint(x: 58, y: 31)], box: 84)
                    .trim(from: 0, to: check || reduceMotion ? 1 : 0)
                    .stroke(NoahTokens.Colors.qrInk, style: StrokeStyle(lineWidth: 3.2, lineCap: .round, lineJoin: .round))
                    .opacity(check || !reduceMotion ? 1 : 0)
            }
            .frame(width: 84, height: 84)
            Text(verbatim: hidden ? "••••" : "+" + BitcoinAmount.format(sats: Int64(clamping: celebration.sats), unit: unit, locale: locale))
                .noahText(HomeText.received)
                .foregroundStyle(NoahTokens.Colors.qrInk)
                .lineLimit(1).minimumScaleFactor(0.5)
                .padding(.top, 12)
                .opacity(amount ? 1 : 0)
                .offset(y: amount || reduceMotion ? 0 : 8)
        }
        .opacity(shown ? 1 : 0)
        .accessibilityElement(children: .combine)
        .task(id: celebration.id) { await play() }
    }

    private func play() async {
        let steps: [(Double, Animation, () -> Void)] = [
            (0.12, NoahTokens.Motion.snappy, { shown = true }),
            (0.16, NoahTokens.Motion.soft, { ring = true }),
            (0.56, NoahTokens.Motion.bouncy, { amount = true }),
            (0.62, NoahTokens.Motion.soft, { check = true }),
        ]
        for (time, animation, change) in steps {
            let wait = time - Date().timeIntervalSince(celebration.start)
            if wait > 0 {
                do { try await Task.sleep(for: .seconds(wait)) } catch { return }
                withAnimation(reduceMotion ? NoahTokens.Motion.reduceMotionFade : animation) { change() }
            } else {
                change()
            }
        }
    }
}
