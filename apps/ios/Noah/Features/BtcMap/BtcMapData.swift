import Foundation

enum PlaceCategory: String, CaseIterable, Codable, Sendable {
    case all, food, shop, stay, atm, services

    var label: String {
        switch self {
        case .all: "All"
        case .food: "Food & drink"
        case .shop: "Shops"
        case .stay: "Stay"
        case .atm: "ATM"
        case .services: "Services"
        }
    }

    var symbol: String {
        switch self {
        case .all: "mappin.and.ellipse"
        case .food: "fork.knife"
        case .shop: "storefront"
        case .stay: "bed.double"
        case .atm: "banknote"
        case .services: "wrench.and.screwdriver"
        }
    }

    static func classify(_ icon: String) -> Self {
        if ["local_atm", "currency_exchange", "attach_money"].contains(icon) { return .atm }
        if ["restaurant", "cafe", "dining", "bar", "bakery", "icecream", "liquor", "pizza", "wine"].contains(where: icon.contains) { return .food }
        if ["storefront", "grocery", "mall", "florist", "gift", "hardware"].contains(where: icon.contains) { return .shop }
        if ["hotel", "chalet", "camp", "luggage", "bed"].contains(where: icon.contains) { return .stay }
        return .services
    }
}

struct BtcMapViewport: Codable, Equatable, Sendable {
    var center: [Double]
    var zoom: Double
    static let initial = Self(center: [0, 20], zoom: 1.5)
    static let storageKey = "btc-map-viewport-v1"

    var isValid: Bool {
        center.count == 2 && center.allSatisfy(\.isFinite)
            && (-180...180).contains(center[0]) && (-90...90).contains(center[1])
            && zoom.isFinite && (1...19).contains(zoom)
    }

    static func parse(_ data: Data) -> Self? {
        guard let value = try? JSONDecoder().decode(Self.self, from: data), value.isValid else { return nil }
        return value
    }
}

enum BtcMapDistance {
    static func kilometers(from: [Double], to: [Double]) -> Double {
        let radians = Double.pi / 180
        let a = pow(sin((to[1] - from[1]) * radians / 2), 2)
            + cos(from[1] * radians) * cos(to[1] * radians)
            * pow(sin((to[0] - from[0]) * radians / 2), 2)
        return 6371 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    static func format(_ km: Double, locale: Locale = .current) -> String {
        if km < 1 { return "\(max(1, Int((km * 1000).rounded()))) m (\(max(1, Int((km * 1093.61).rounded()))) yd)" }
        let style = FloatingPointFormatStyle<Double>.number.locale(locale)
            .precision(.fractionLength(1)).grouping(.never)
        return "\(km.formatted(style)) km (\((km * 0.621371).formatted(style)) mi)"
    }
}

struct BtcMapPlace: Codable, Identifiable, Equatable, Sendable {
    let id: Int
    let lat: Double
    let lon: Double
    let icon: String
    let name: String
    let updated_at: String
    var comments: Int?
    var boosted_until: String?
    var verified_at: String?
    var center: [Double] { [lon, lat] }
    var category: PlaceCategory { .classify(icon) }

    enum CodingKeys: String, CodingKey { case id, lat, lon, icon, name, updated_at, comments, boosted_until, verified_at }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        lat = try c.decode(Double.self, forKey: .lat)
        lon = try c.decode(Double.self, forKey: .lon)
        icon = try c.decode(String.self, forKey: .icon)
        name = try c.decode(String.self, forKey: .name)
        updated_at = try c.decode(String.self, forKey: .updated_at)
        guard lat.isFinite, lon.isFinite, !icon.isEmpty, !name.isEmpty, !updated_at.isEmpty else {
            throw BtcMapError.invalidData
        }
        try [icon, name, updated_at].forEach(BtcMapLimits.check)
        comments = (try? c.decode(Int.self, forKey: .comments)).flatMap { $0 >= 0 ? $0 : nil }
        boosted_until = (try? c.decode(String.self, forKey: .boosted_until)).flatMap { $0.isEmpty ? nil : $0 }
        verified_at = (try? c.decode(String.self, forKey: .verified_at)).flatMap { $0.isEmpty ? nil : $0 }
        try [boosted_until, verified_at].compactMap(\.self).forEach(BtcMapLimits.check)
    }
}

struct BtcMapSnapshot: Codable, Sendable {
    var schemaVersion: Int
    var generatedAt: String
    var source: String
    var places: [BtcMapPlace]

    static func parse(_ data: Data) throws -> Self {
        try BtcMapLimits.checkJSON(data)
        let value = try JSONDecoder().decode(Self.self, from: data)
        guard value.schemaVersion == 1, !value.generatedAt.isEmpty, !value.source.isEmpty else { throw BtcMapError.invalidData }
        return value
    }

    func merging(_ patches: [BtcMapPatch], at date: String) -> Self {
        var placesByID = [Int: BtcMapPlace]()
        for place in places { placesByID[place.id] = place }
        for patch in patches { placesByID[patch.id] = patch.place }
        return Self(schemaVersion: schemaVersion, generatedAt: date, source: source,
                    places: placesByID.values.sorted { $0.id < $1.id })
    }
}

struct BtcMapPatch: Decodable, Sendable {
    let id: Int
    let place: BtcMapPlace?
    enum CodingKeys: String, CodingKey { case id, deleted_at }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        let deleted = (try? c.decode(String.self, forKey: .deleted_at)) ?? ""
        try BtcMapLimits.check(deleted)
        place = deleted.isEmpty ? try BtcMapPlace(from: decoder) : nil
    }
}

struct BtcMapDetail: Sendable {
    let place: BtcMapPlace
    let fields: [String: String]
    let comments: [Comment]

    struct Comment: Decodable, Identifiable, Sendable {
        let id: Int
        let text: String
        let created_at: String
    }

    static func parse(place data: Data, comments: Data) throws -> Self {
        try BtcMapLimits.checkJSON(data)
        let place = try JSONDecoder().decode(BtcMapPlace.self, from: data)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw BtcMapError.invalidData }
        let fields = object.compactMapValues { ($0 as? String).flatMap { $0.isEmpty ? nil : $0 } }
        try fields.values.forEach(BtcMapLimits.check)
        let comments = try BtcMapLimits.decodeRecords(Comment.self, from: comments)
        for comment in comments { try BtcMapLimits.check(comment.text); try BtcMapLimits.check(comment.created_at) }
        guard comments.allSatisfy({ !$0.text.isEmpty && !$0.created_at.isEmpty }) else { throw BtcMapError.invalidData }
        return Self(place: place, fields: fields, comments: comments)
    }
}

enum BtcMapError: Error, Equatable { case invalidData, resourceLimit, http(Int) }

extension BtcMapSnapshot {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try c.decode(Int.self, forKey: .schemaVersion)
        generatedAt = try c.decode(String.self, forKey: .generatedAt)
        source = try c.decode(String.self, forKey: .source)
        try BtcMapLimits.check(generatedAt)
        try BtcMapLimits.check(source)
        places = try c.decode(BtcMapLimits.Records<BtcMapPlace>.self, forKey: .places).values
    }
}
