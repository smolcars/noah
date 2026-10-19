import Foundation
import Testing
@testable import Noah

struct BtcMapFixtureTests {
    typealias JSON = HistoryFixtureTests.JSON
    struct Input: Decodable {
        let operation: String
        var epochMilliseconds: Double?
        var icon: String?
        var from: [Double]?
        var to: [Double]?
        var km: Double?
        var value: JSON?
        var snapshot: BtcMapSnapshot?
        var patches: [BtcMapPatch]?
        var generatedAt: String?
        var query: String?
        var limit: Int?
        var bias: [Double]?
    }

    @Test func btcMapParity() throws {
        for fixture in try Fixtures.load("btc-map", input: Input.self, expected: JSON.self) {
            let input = fixture.input
            let actual: JSON
            switch input.operation {
            case "syncTimestamp": actual = try .value(BtcMapRepository.timestamp(Date(timeIntervalSince1970: #require(input.epochMilliseconds) / 1000)))
            case "category": actual = try .value(PlaceCategory.classify(#require(input.icon)).rawValue)
            case "distance":
                let value = BtcMapDistance.kilometers(from: try #require(input.from), to: try #require(input.to))
                #expect(abs(value - (try fixture.expected.decode(Double.self))) < 0.000000001)
                continue
            case "format": actual = try .value(BtcMapDistance.format(#require(input.km), locale: Locale(identifier: "en_US")))
            case "viewport": actual = try .value(BtcMapViewport.parse(JSONEncoder().encode(input.value)))
            case "parse": actual = try .value(try? BtcMapSnapshot.parse(JSONEncoder().encode(input.value)))
            case "merge": actual = try .value(#require(input.snapshot).merging(#require(input.patches), at: #require(input.generatedAt)))
            case "bundle":
                let data = try Data(contentsOf: #require(Bundle.main.url(forResource: "places", withExtension: "btcmap")))
                let snapshot = try BtcMapSnapshot.parse(data)
                let ids = snapshot.places.map(\.id)
                actual = try .object([
                    "count": .value(ids.count), "unique": .value(Set(ids).count == ids.count),
                    "sorted": .value(ids == ids.sorted()), "bounded": .value(snapshot.places.allSatisfy { (-90...90).contains($0.lat) && (-180...180).contains($0.lon) }),
                    "schemaVersion": .value(snapshot.schemaVersion), "source": .value(snapshot.source),
                ])
            default: Issue.record("Unknown BTC Map fixture"); continue
            }
            #expect(actual == fixture.expected, "\(fixture.name)")
        }
    }

    @Test func citySearchParity() throws {
        let data = try Data(contentsOf: #require(Bundle.main.url(forResource: "cities", withExtension: "geonames")))
        let cities = try CitySearch.parse(data)
        for fixture in try Fixtures.load("city-search", input: Input.self, expected: JSON.self) {
            let input = fixture.input
            let actual: JSON
            switch input.operation {
            case "normalize": actual = try .value(CitySearch.normalize(#require(input.value).decode(String.self)))
            case "search":
                let results = CitySearch.search(cities, query: try #require(input.query), limit: input.limit ?? 3, bias: input.bias)
                actual = .array(try results.map { city in
                    try .object(["id": .value(city.id), "name": .value(city.name), "countryCode": .value(city.countryCode),
                                 "displayLabel": .value(city.displayLabel), "secondaryLabel": .value(city.secondaryLabel), "center": .value(city.center)])
                })
            case "parse": actual = try .value((try? CitySearch.parse(JSONEncoder().encode(input.value))) != nil)
            case "bundle":
                let ids = cities.map(\.id)
                actual = try .object([
                    "count": .value(ids.count), "unique": .value(Set(ids).count == ids.count), "sorted": .value(ids == ids.sorted()),
                    "bounded": .value(cities.allSatisfy { (-180...180).contains($0.center[0]) && (-90...90).contains($0.center[1]) }),
                    "schemaVersion": .value(1), "license": .value("CC BY 4.0"), "under5MiB": .value(data.count < 5 * 1024 * 1024),
                    "hasAtlanta": .value(cities.contains { $0.name == "Atlanta" && $0.countryCode == "US" && $0.admin1Code == "GA" }),
                ])
            default: Issue.record("Unknown city fixture"); continue
            }
            #expect(actual == fixture.expected, "\(fixture.name)")
        }
    }
}

struct BtcMapRepositoryTests {
    actor Requests {
        var urls: [URL] = []
        let response: Data
        init(_ response: String) { self.response = Data(response.utf8) }
        func fetch(_ url: URL) -> Data { urls.append(url); return response }
    }

    private func directory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test func syncPreservesRNPathContractAndCadence() async throws {
        let folder = try directory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = folder.appending(path: "btcmap-places-v1.btcmap")
        let requests = Requests("[]")
        let repository = BtcMapRepository(cacheURL: cache, fetch: { await requests.fetch($0) })
        let bundled = try await repository.load()
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        async let a = repository.sync(now: now)
        async let b = repository.sync(now: now)
        let (first, second) = try await (a, b)
        #expect(first.generatedAt == second.generatedAt)
        #expect(first.places == bundled.places)
        _ = try await repository.sync(now: now.addingTimeInterval(299))
        #expect(await requests.urls.count == 1)
        _ = try await repository.sync(now: now.addingTimeInterval(300))
        #expect(await requests.urls.count == 2)
        let url = try #require(await requests.urls.first)
        let query = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        #expect(url.host == "api.btcmap.org" && url.path == "/v4/places")
        #expect(query.contains(URLQueryItem(name: "updated_since", value: bundled.generatedAt)))
        #expect(query.contains(URLQueryItem(name: "include_deleted", value: "true")))
        let restored = BtcMapRepository(cacheURL: cache, bundleURL: nil)
        #expect(try await restored.load().generatedAt == "2027-01-15T08:05:00.000Z")
    }

    @Test func malformedPatchDoesNotAdvanceOrOverwriteCache() async throws {
        let folder = try directory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = folder.appending(path: "btcmap-places-v1.btcmap")
        let original = Data("corrupt cache".utf8)
        try original.write(to: cache)
        let repository = BtcMapRepository(cacheURL: cache, fetch: { _ in Data("[{\"id\":1}]".utf8) })
        let local = try await repository.load()
        #expect(local.places.count > 10_000)
        await #expect(throws: (any Error).self) { try await repository.sync() }
        #expect(try Data(contentsOf: cache) == original)
        #expect(try await repository.load().generatedAt == local.generatedAt)
    }

    @Test func invalidViewportsNeverReachCamera() {
        for text in ["null", "{\"center\":[0,0],\"zoom\":0}", "{\"center\":[0,0,0],\"zoom\":12}", "{\"center\":[true,0],\"zoom\":12}"] {
            #expect(BtcMapViewport.parse(Data(text.utf8)) == nil)
        }
    }
}

@MainActor
struct BtcMapModelTests {
    @Test func selectingCityKeepsMerchantsWhenTextFieldEchoesItsLabel() async throws {
        SettingsStorage.bootstrap()
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let settings = try SettingsStorage(rootPath: folder.path, instanceID: UUID().uuidString)
        defer { settings.removeAll(); try? FileManager.default.removeItem(at: folder) }
        let repository = BtcMapRepository(cacheURL: folder.appending(path: "places"), fetch: { _ in Data("[]".utf8) })
        let model = BtcMapModel(settings: settings, repository: repository)
        await model.load()
        model.updateQuery("Atlanta")
        await model.searchCities()
        let city = try #require(model.cities.first)
        model.select(city)
        model.updateQuery(city.displayLabel)
        #expect(model.selectedCity?.id == city.id)
        #expect(model.filtered.count == model.places.count)
        #expect(model.filtered.count > 10_000)
        model.category = .food
        #expect(!model.filtered.isEmpty && model.filtered.allSatisfy { $0.category == .food })
        model.updateQuery("pizza")
        #expect(model.selectedCity == nil)
        #expect(model.filtered.allSatisfy { CitySearch.normalize($0.name).contains("pizza") })
    }

    @Test func viewportSurvivesReopenAndDefaultIsNotPersisted() throws {
        SettingsStorage.bootstrap()
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let settings = try SettingsStorage(rootPath: folder.path, instanceID: UUID().uuidString)
        defer { settings.removeAll(); try? FileManager.default.removeItem(at: folder) }
        let model = BtcMapModel(settings: settings)
        model.record(.initial, userInteraction: false)
        #expect(settings.string(forKey: BtcMapViewport.storageKey) == nil)
        let viewport = BtcMapViewport(center: [-73.9857, 40.7484], zoom: 14.5)
        model.move(to: viewport)
        #expect(BtcMapModel(settings: settings).viewport == viewport)
        model.record(BtcMapViewport(center: [181, 0], zoom: 12), userInteraction: true)
        #expect(BtcMapModel(settings: settings).viewport == viewport)
        settings.set("{\"center\":[0,91],\"zoom\":13}", forKey: BtcMapViewport.storageKey)
        #expect(BtcMapModel(settings: settings).viewport == .initial)
        #expect(settings.string(forKey: BtcMapViewport.storageKey) == nil)
    }
}

extension BtcMapRepositoryTests {
    actor DetailRequests {
        var urls: [URL] = []
        let place: Data
        init(_ place: Data) { self.place = place }
        func fetch(_ url: URL) -> Data {
            urls.append(url)
            return url.path.hasSuffix("comments")
                ? Data("[{\"id\":1,\"text\":\"Paid with bitcoin\",\"created_at\":\"2026-01-01\"}]".utf8) : place
        }
    }

    @Test func detailsFetchCommentsOnlyWhenNeededAndCacheForFifteenMinutes() async throws {
        let raw = Data("{\"id\":1,\"name\":\"Cafe\",\"icon\":\"cafe\",\"lat\":1,\"lon\":2,\"updated_at\":\"2026-01-01\",\"comments\":1,\"website\":\"https://example.com\",\"address\":\"Example Street\",\"osm:payment:lightning\":\"yes\"}".utf8)
        let place = try JSONDecoder().decode(BtcMapPlace.self, from: raw)
        let requests = DetailRequests(raw)
        let repository = BtcMapRepository(fetch: { await requests.fetch($0) })
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        async let a = repository.detail(for: place, now: now)
        async let b = repository.detail(for: place, now: now)
        let (detail, _) = try await (a, b)
        #expect(detail.fields["osm:payment:lightning"] == "yes")
        #expect(detail.comments.count == 1)
        #expect(await requests.urls.count == 2)
        _ = try await repository.detail(for: place, now: now.addingTimeInterval(899))
        #expect(await requests.urls.count == 2)
        _ = try await repository.detail(for: place, now: now.addingTimeInterval(900))
        #expect(await requests.urls.count == 4)
        var noComments = place
        noComments.comments = 0
        let fresh = BtcMapRepository(fetch: { await requests.fetch($0) })
        #expect(try await fresh.detail(for: noComments).comments.isEmpty)
        #expect(await requests.urls.count == 5)
    }
}
