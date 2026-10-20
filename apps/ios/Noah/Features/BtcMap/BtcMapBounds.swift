import Foundation

struct BtcMapBounds: Codable, Equatable, Sendable {
    let west: Double
    let south: Double
    let east: Double
    let north: Double

    func contains(lon: Double, lat: Double) -> Bool {
        (south...north).contains(lat) && (west <= east ? (west...east).contains(lon) : lon >= west || lon <= east)
    }
}
