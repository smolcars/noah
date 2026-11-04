import UIKit

/// The Appearance sheet's circle reveal (appearance.md, Picking): a frozen snapshot of the window goes over
/// everything, the app re-themes underneath, and a hole in the snapshot grows from the tapped tile until the
/// new appearance covers the window. The live UI stays interactive below it. Reduce Motion cross-fades instead.
@MainActor
enum AppearanceReveal {
    static let duration: CFTimeInterval = 0.56
    static let fade: TimeInterval = 0.2
    /// One at a time: a pick during a reveal waits, so snapshots never stack.
    private(set) static var running = false

    /// `change` re-themes the app; `center` is in `window`'s coordinates.
    static func run(in window: UIWindow, from center: CGPoint, reduceMotion: Bool, change: () -> Void) {
        guard !running, let image = freeze(window) else { change(); return }
        running = true
        let snapshot = UIImageView(image: image)
        snapshot.frame = window.bounds
        snapshot.isUserInteractionEnabled = false
        snapshot.accessibilityElementsHidden = true
        window.addSubview(snapshot)
        change()
        let finish = {
            snapshot.removeFromSuperview()
            running = false
        }
        if reduceMotion {
            UIView.animate(withDuration: fade, delay: 0, options: .curveEaseInOut) { snapshot.alpha = 0 } completion: { _ in finish() }
            return
        }
        let bounds = window.bounds
        let mask = CAShapeLayer()
        mask.frame = bounds
        mask.fillRule = .evenOdd
        let covered = hole(in: bounds, center: center, radius: radius(covering: bounds, from: center))
        mask.path = covered
        snapshot.layer.mask = mask
        let grow = CABasicAnimation(keyPath: "path")
        grow.fromValue = hole(in: bounds, center: center, radius: 0.5)
        grow.toValue = covered
        grow.duration = duration
        // The prototype's cubic-bezier(.3,.7,.2,1).
        grow.timingFunction = CAMediaTimingFunction(controlPoints: 0.3, 0.7, 0.2, 1)
        CATransaction.begin()
        CATransaction.setCompletionBlock { MainActor.assumeIsolated { finish() } }
        mask.add(grow, forKey: "reveal")
        CATransaction.commit()
    }

    /// The window as it is on screen. An image, not `snapshotView`: a snapshot view's Liquid Glass keeps
    /// sampling, so rows behind a glass sheet showed through it during the reveal.
    private static func freeze(_ window: UIWindow) -> UIImage? {
        var drawn = false
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: UIGraphicsImageRendererFormat(for: window.traitCollection)).image { _ in
            drawn = window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        return drawn ? image : nil
    }

    /// To the farthest corner: the circle that covers the window.
    nonisolated static func radius(covering bounds: CGRect, from center: CGPoint) -> CGFloat {
        hypot(max(center.x - bounds.minX, bounds.maxX - center.x), max(center.y - bounds.minY, bounds.maxY - center.y))
    }

    /// The window less a circle, filled even-odd so the circle is the hole.
    private static func hole(in bounds: CGRect, center: CGPoint, radius: CGFloat) -> CGPath {
        let path = CGMutablePath()
        path.addRect(bounds)
        path.addEllipse(in: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
        return path
    }
}
