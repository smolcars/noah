import Foundation
import Observation

@MainActor @Observable
final class BtcMapModel {
    private(set) var places: [BtcMapPlace] = []
    private(set) var filtered: [BtcMapPlace] = []
    private(set) var nearby: [BtcMapPlace] = []
    private(set) var cities: [CitySearchEntry] = []
    private(set) var merchants: [BtcMapPlace] = []
    var visibleBounds: BtcMapBounds?
    var placesInView: [BtcMapPlace] {
        guard let visibleBounds else { return [] }
        return filtered.filter { visibleBounds.contains(lon: $0.lon, lat: $0.lat) }
            .sorted { $0.name == $1.name ? $0.id < $1.id : $0.name < $1.name }
    }
    private(set) var revision = 0
    private(set) var cameraRevision = 0
    private(set) var viewport: BtcMapViewport
    private(set) var userLocation: [Double]?
    private(set) var isLoading = true
    private(set) var isSyncing = false
    private(set) var isLocating = false
    private(set) var loadError: String?
    private(set) var syncUnavailable = false
    private(set) var cityError = false
    var basemapUnavailable = false
    var styleRevision = 0
    var alert: String?
    var selected: BtcMapPlace?
    var category = PlaceCategory.all { didSet { filter() } }
    var query = "" { didSet { filter() } }
    var selectedCity: CitySearchEntry?
    private var names: [Int: String] = [:]
    private var canPersist: Bool
    private var attemptedInitialLocation = false
    private let settings: SettingsStorage
    let repository: BtcMapRepository
    private let location = BtcMapLocation()

    init(settings: SettingsStorage = .shared, repository: BtcMapRepository = .shared) {
        self.settings = settings
        self.repository = repository
        let serialized = settings.string(forKey: BtcMapViewport.storageKey)
        let saved = serialized.flatMap { BtcMapViewport.parse(Data($0.utf8)) }
        if serialized != nil && saved == nil { settings.remove(forKey: BtcMapViewport.storageKey) }
        viewport = saved ?? .initial
        canPersist = saved != nil
    }

    func load() async {
        do {
            setPlaces(try await repository.load().places)
            isLoading = false
            loadError = nil
        } catch {
            isLoading = false
            loadError = "The bundled places file could not be read."
            return
        }
        await refresh()
    }

    func initialLocation() async {
        guard !canPersist, !attemptedInitialLocation, !isLoading, loadError == nil else { return }
        attemptedInitialLocation = true
        await locate(initial: true)
    }

    func refresh() async {
        guard !isSyncing, !isLoading, loadError == nil else { return }
        isSyncing = true
        defer { isSyncing = false }
        do {
            setPlaces(try await repository.sync().places)
            syncUnavailable = false
        } catch { syncUnavailable = true }
    }

    private func setPlaces(_ values: [BtcMapPlace]) {
        places = values
        names = Dictionary(values.map { ($0.id, CitySearch.normalize($0.name)) }, uniquingKeysWith: { _, last in last })
        if let selected { self.selected = places.first { $0.id == selected.id } }
        filter()
    }

    private func filter() {
        let query = CitySearch.normalize(query)
        let matches = places.filter { category == .all || $0.category == category }
        filtered = matches.filter { selectedCity != nil || query.isEmpty || (names[$0.id] ?? "").contains(query) }
        if query.count >= 2 {
            merchants = matches.compactMap { place -> (BtcMapPlace, Int)? in
                let name = names[place.id] ?? ""
                let score = name == query ? 0 : name.hasPrefix(query) ? 1 : name.contains(query) ? 2 : 3
                return score == 3 ? nil : (place, score)
            }.sorted { $0.1 != $1.1 ? $0.1 < $1.1 : $0.0.name.localizedCompare($1.0.name) == .orderedAscending }
                .prefix(3).map(\.0)
        } else { merchants = [] }
        if let userLocation {
            nearby = filtered.map { ($0, BtcMapDistance.kilometers(from: userLocation, to: $0.center)) }
                .sorted { $0.1 < $1.1 }.prefix(8).map(\.0)
        } else { nearby = [] }
        revision += 1
    }

    func updateQuery(_ value: String) {
        // TextField can echo its binding when focus is dismissed after choosing a city.
        guard value != query else { return }
        selectedCity = nil
        query = value
    }

    func searchCities() async {
        let query = query
        let center = viewport.center
        do {
            let results = try await repository.searchCities(query, bias: center)
            guard !Task.isCancelled, self.query == query, viewport.center == center else { return }
            cities = results
            cityError = false
        } catch {
            guard !Task.isCancelled, self.query == query, viewport.center == center else { return }
            cities = []
            cityError = true
        }
    }

    func select(_ place: BtcMapPlace) {
        selected = place
        move(to: BtcMapViewport(center: place.center, zoom: 15))
    }

    func select(_ city: CitySearchEntry) {
        selectedCity = city
        userLocation = nil
        selected = nil
        query = city.displayLabel
        move(to: BtcMapViewport(center: city.center, zoom: 11))
    }

    func move(to viewport: BtcMapViewport) {
        record(viewport, userInteraction: true)
        cameraRevision += 1
    }

    func record(_ viewport: BtcMapViewport, userInteraction: Bool) {
        guard (canPersist || userInteraction), viewport.isValid else { return }
        canPersist = true
        self.viewport = viewport
        if let data = try? JSONEncoder().encode(viewport) {
            settings.set(String(decoding: data, as: UTF8.self), forKey: BtcMapViewport.storageKey)
        }
    }

    func locate(initial: Bool = false) async {
        guard !isLocating else { return }
        isLocating = true
        defer { isLocating = false }
        do {
            let coordinates = try await location.locate(requestingPermission: !initial)
            guard !Task.isCancelled, !initial || !canPersist else { return }
            userLocation = coordinates
            selectedCity = nil
            query = ""
            move(to: BtcMapViewport(center: coordinates, zoom: 13))
        } catch {
            guard !initial, !Task.isCancelled else { return }
            alert = error as? BtcMapLocation.Failure == .denied
                ? "You can still browse the full map. Enable location in Settings to see nearby places."
                : "Location is unavailable. You can still search for a city and browse the full map."
        }
    }
}
