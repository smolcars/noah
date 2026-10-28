import CoreLocation

@MainActor
final class BtcMapLocation: NSObject, @preconcurrency CLLocationManagerDelegate {
    enum Failure: Error { case denied, unavailable }
    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<[Double], Error>?
    private var requestingPermission = false
    private var timeout: Task<Void, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    static func mayLocate(status: CLAuthorizationStatus, requestingPermission: Bool) -> Bool {
        status == .authorizedAlways || status == .authorizedWhenInUse || (status == .notDetermined && requestingPermission)
    }

    func locate(requestingPermission: Bool = true) async throws -> [Double] {
        guard Self.mayLocate(status: manager.authorizationStatus, requestingPermission: requestingPermission) else { throw Failure.denied }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard self.continuation == nil else {
                    continuation.resume(throwing: Failure.unavailable)
                    return
                }
                self.requestingPermission = requestingPermission
                self.continuation = continuation
                timeout = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(30)) }
                    catch { return }
                    self?.finish(.failure(Failure.unavailable))
                }
                request()
            }
        } onCancel: {
            Task { @MainActor [weak self] in self?.finish(.failure(CancellationError())) }
        }
    }

    private func request() {
        guard continuation != nil else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            if requestingPermission { manager.requestWhenInUseAuthorization() }
            else { finish(.failure(Failure.denied)) }
        case .authorizedAlways, .authorizedWhenInUse:
            if let location = manager.location, location.horizontalAccuracy >= 0,
               abs(location.timestamp.timeIntervalSinceNow) < 300 {
                finish(.success([location.coordinate.longitude, location.coordinate.latitude]))
            } else { manager.requestLocation() }
        case .denied, .restricted: finish(.failure(Failure.denied))
        @unknown default: finish(.failure(Failure.unavailable))
        }
    }

    func locationManagerDidChangeAuthorization(_: CLLocationManager) { request() }
    func locationManager(_: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, location.horizontalAccuracy >= 0 else { return }
        finish(.success([location.coordinate.longitude, location.coordinate.latitude]))
    }
    func locationManager(_: CLLocationManager, didFailWithError _: Error) {
        finish(.failure(Failure.unavailable))
    }
    private func finish(_ result: Result<[Double], Error>) {
        let pending = continuation
        continuation = nil
        timeout?.cancel()
        timeout = nil
        manager.stopUpdatingLocation()
        pending?.resume(with: result)
    }
}
