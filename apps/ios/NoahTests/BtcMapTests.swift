import Foundation
import Testing
@testable import Noah

struct BtcMapFixtureTests {
    typealias JSON = HistoryFixtureTests.JSON
    struct Input: Decodable {
        let operation: String
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
        #expect(try await restored.load().generatedAt == now.addingTimeInterval(300).ISO8601Format(.iso8601(timeZone: .gmt, includingFractionalSeconds: true)))
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
