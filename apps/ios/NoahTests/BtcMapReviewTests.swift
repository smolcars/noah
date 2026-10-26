import CoreLocation
import Foundation
import Testing
@testable import Noah

struct BtcMapReviewTests {
    struct Input: Decodable {
        let operation: String
        var text: String?
        var bounds: BtcMapBounds?
        var lon: Double?
        var lat: Double?
    }

    @Test func mapSafetyContract() throws {
        for fixture in try Fixtures.load("btc-map-review", input: Input.self, expected: HistoryFixtureTests.JSON.self) {
            let result: HistoryFixtureTests.JSON
            switch fixture.input.operation {
            case "web": result = try .value(BtcMapLinks.web(#require(fixture.input.text))?.absoluteString)
            case "phone": result = try .value(BtcMapLinks.phone(#require(fixture.input.text))?.absoluteString)
            case "bounds": result = try .value(#require(fixture.input.bounds).contains(lon: #require(fixture.input.lon), lat: #require(fixture.input.lat)))
            default: Issue.record("Unknown map review fixture"); continue
            }
            #expect(result == fixture.expected, "\(fixture.name)")
        }
    }

    @MainActor @Test func locationPermissionRequiresAnExplicitAction() {
        #expect(!BtcMapLocation.mayLocate(status: .notDetermined, requestingPermission: false))
        #expect(BtcMapLocation.mayLocate(status: .notDetermined, requestingPermission: true))
        for status: CLAuthorizationStatus in [.authorizedAlways, .authorizedWhenInUse] {
            #expect(BtcMapLocation.mayLocate(status: status, requestingPermission: false))
        }
        for status: CLAuthorizationStatus in [.denied, .restricted] {
            #expect(!BtcMapLocation.mayLocate(status: status, requestingPermission: true))
        }
    }

    @MainActor @Test func cityRankingChangesWithViewportAndVisiblePlacesOpenDetails() async throws {
        SettingsStorage.bootstrap()
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let settings = try SettingsStorage(rootPath: folder.path, instanceID: UUID().uuidString)
        defer { settings.removeAll(); try? FileManager.default.removeItem(at: folder) }
        let model = BtcMapModel(settings: settings, repository: try BtcMapRepositoryTests.smallRepository(in: folder))
        await model.load()
        model.updateQuery("london")
        model.move(to: .init(center: [-81.23, 42.98], zoom: 11))
        await model.searchCities()
        #expect(model.cities.first?.countryCode == "CA")
        model.move(to: .init(center: [-0.12, 51.5], zoom: 11))
        await model.searchCities()
        #expect(model.cities.first?.countryCode == "GB")
        model.updateQuery("")
        model.visibleBounds = BtcMapBounds(west: -180, south: -90, east: 180, north: 90)
        let place = try #require(model.placesInView.first)
        model.select(place)
        #expect(model.selected?.id == place.id)
        #expect(model.viewport.center == place.center)
    }

}
