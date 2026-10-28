import Foundation

/// Owns public map data only. The disk format and path also read RN's existing cache.
actor BtcMapRepository {
    static let shared = BtcMapRepository()
    static let placeFields = ["id", "lat", "lon", "icon", "name", "comments", "boosted_until", "verified_at", "updated_at"]
    static let detailFields = placeFields + [
        "address", "opening_hours", "created_at", "osm_id", "osm_url", "phone", "website", "twitter",
        "facebook", "instagram", "telegram", "email", "required_app_url", "description", "image",
        "payment_provider", "osm:amenity", "osm:shop", "osm:tourism", "osm:cuisine",
        "osm:payment:lightning", "osm:payment:lightning_contactless", "osm:payment:onchain",
    ]
    typealias Fetch = @Sendable (URL) async throws -> Data
    private let cacheURL: URL
    private let bundleURL: URL?
    private let citiesURL: URL?
    private let fetch: Fetch
    private let clock: @Sendable () -> Date
    private var snapshot: BtcMapSnapshot?
    // swiftlint:disable:next discouraged_optional_collection - nil: not loaded yet / no location to bias by
    private var cities: [CitySearchEntry]?
    private var syncTask: Task<BtcMapSnapshot, Error>?
    private var lastSync: Date?
    private var oversizedSync: Date?
    private var oversizedDetails: [Int: Date] = [:]
    private var details: [Int: (Date, BtcMapDetail)] = [:]
    private var detailTasks: [Int: Task<BtcMapDetail, Error>] = [:]

    init(cacheURL: URL = URL.documentsDirectory.appending(path: "btcmap-places-v1.btcmap"),
         bundleURL: URL? = Bundle.main.url(forResource: "places", withExtension: "btcmap"),
         citiesURL: URL? = Bundle.main.url(forResource: "cities", withExtension: "geonames"),
         fetch: @escaping Fetch = BtcMapRepository.download,
         clock: @escaping @Sendable () -> Date = { .now }) {
        self.cacheURL = cacheURL
        self.bundleURL = bundleURL
        self.citiesURL = citiesURL
        self.fetch = fetch
        self.clock = clock
    }

    func load() throws -> BtcMapSnapshot {
        if let snapshot { return snapshot }
        if let data = try? BtcMapLimits.read(cacheURL), let cached = try? BtcMapSnapshot.parse(data) {
            snapshot = cached
            return cached
        }
        guard let bundleURL else { throw BtcMapError.invalidData }
        let bundled = try BtcMapSnapshot.parse(BtcMapLimits.read(bundleURL))
        snapshot = bundled
        return bundled
    }

    func sync(now: Date? = nil) async throws -> BtcMapSnapshot {
        let now = now ?? clock()
        if let syncTask { return try await syncTask.value }
        if let oversizedSync, (0..<300).contains(now.timeIntervalSince(oversizedSync)) { throw BtcMapError.resourceLimit }
        let current = try load()
        if let lastSync, (0..<300).contains(now.timeIntervalSince(lastSync)) { return current }
        let url = Self.syncURL(since: current.generatedAt)
        let fetch = fetch
        let cacheURL = cacheURL
        let task = Task {
            try await Self.retry {
                let data = try await fetch(url)
                let patches = try BtcMapLimits.decodeRecords(BtcMapPatch.self, from: data)
                let merged = current.merging(patches, at: Self.timestamp(now))
                guard merged.places.count <= BtcMapLimits.records else { throw BtcMapError.resourceLimit }
                _ = try BtcMapLimits.encodedSize(merged)
                let encoded = try JSONEncoder().encode(merged)
                try encoded.write(to: cacheURL, options: .atomic)
                return merged
            }
        }
        syncTask = task
        defer { syncTask = nil }
        let result: BtcMapSnapshot
        do { result = try await task.value }
        catch BtcMapError.resourceLimit { oversizedSync = now; throw BtcMapError.resourceLimit }
        snapshot = result
        oversizedSync = nil
        lastSync = now
        return result
    }

    // swiftlint:disable:next discouraged_optional_collection - nil: not loaded yet / no location to bias by
    func searchCities(_ query: String, bias: [Double]?) throws -> [CitySearchEntry] {
        if cities == nil {
            guard let citiesURL else { throw BtcMapError.invalidData }
            cities = try CitySearch.parse(Data(contentsOf: citiesURL))
        }
        return CitySearch.search(cities ?? [], query: query, bias: bias)
    }

    func detail(for place: BtcMapPlace, now: Date? = nil) async throws -> BtcMapDetail {
        let now = now ?? clock()
        if let failed = oversizedDetails[place.id], (0..<300).contains(now.timeIntervalSince(failed)) { throw BtcMapError.resourceLimit }
        if let cached = details[place.id], (0..<900).contains(now.timeIntervalSince(cached.0)) { return cached.1 }
        if let task = detailTasks[place.id] { return try await task.value }
        let fetch = fetch
        let task = Task {
            try await Self.retry {
                async let data = fetch(Self.url(
                    path: "places/\(place.id)",
                    query: [URLQueryItem(name: "fields", value: Self.detailFields.joined(separator: ","))]))
                async let comments = place.comments ?? 0 > 0
                    ? fetch(Self.url(path: "places/\(place.id)/comments")) : Data("[]".utf8)
                return try await BtcMapDetail.parse(place: data, comments: comments)
            }
        }
        detailTasks[place.id] = task
        defer { detailTasks[place.id] = nil }
        let result: BtcMapDetail
        do { result = try await task.value }
        catch BtcMapError.resourceLimit { oversizedDetails[place.id] = now; throw BtcMapError.resourceLimit }
        oversizedDetails[place.id] = nil
        details[place.id] = (now, result)
        return result
    }

    static func timestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    static func syncURL(since: String) -> URL {
        url(path: "places", query: [
            URLQueryItem(name: "fields", value: (placeFields + ["deleted_at"]).joined(separator: ",")),
            URLQueryItem(name: "updated_since", value: since),
            URLQueryItem(name: "include_deleted", value: "true"),
        ])
    }

    private static func url(path: String, query: [URLQueryItem] = []) -> URL {
        var url = URL(literal: "https://api.btcmap.org/v4").appending(path: path)
        if !query.isEmpty { url.append(queryItems: query) }
        return url
    }

    private static func retry<T: Sendable>(_ operation: @Sendable () async throws -> T) async throws -> T {
        do { return try await operation() }
        catch BtcMapError.resourceLimit { throw BtcMapError.resourceLimit }
        catch {
            try Task.checkCancellation()
            try await Task.sleep(for: .seconds(1))
            return try await operation()
        }
    }

    static func download(_ url: URL) async throws -> Data {
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        defer { bytes.task.cancel() }
        guard let response = response as? HTTPURLResponse else { throw BtcMapError.invalidData }
        guard (200..<300).contains(response.statusCode) else { throw BtcMapError.http(response.statusCode) }
        guard response.expectedContentLength <= BtcMapLimits.bytes else { throw BtcMapError.resourceLimit }
        return try await collect(bytes)
    }

    static func collect<S: AsyncSequence>(_ bytes: S, limit: Int = BtcMapLimits.bytes) async throws -> Data where S.Element == UInt8 {
        var data = Data()
        for try await byte in bytes {
            guard data.count < limit else { throw BtcMapError.resourceLimit }
            data.append(byte)
        }
        return data
    }
}
