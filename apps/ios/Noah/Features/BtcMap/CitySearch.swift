import Foundation

struct CitySearchEntry: Identifiable, Sendable {
    let id: Int
    let name: String
    let asciiName: String
    let center: [Double]
    let countryCode: String
    let countryName: String
    let admin1Code: String
    let regionName: String?
    let population: Int
    let normalizedNames: [String]
    let searchTokens: [String]
    let searchText: String

    var secondaryLabel: String { [regionName, countryName].compactMap(\.self).joined(separator: ", ") }
    var displayLabel: String {
        let region = countryCode == #"US"# ? admin1Code : regionName ?? ""
        return "\(name), \(region.isEmpty ? countryName : region)"
    }
}

enum CitySearch {
    static func normalize(_ text: String) -> String {
        let decomposed = text.decomposedStringWithCompatibilityMapping
        let stripped = String(decomposed.unicodeScalars.filter { !(0x300...0x36F).contains($0.value) })
        return stripped.lowercased().replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    static func parse(_ data: Data) throws -> [CitySearchEntry] {
        let raw = try JSONDecoder().decode(Snapshot.self, from: data)
        guard raw.schemaVersion == 1, raw.license == "CC BY 4.0", !raw.generatedAt.isEmpty,
              raw.countries.allSatisfy({ !$0.key.isEmpty && !$0.value.isEmpty }),
              raw.admin1.allSatisfy({ !$0.key.isEmpty && !$0.value.isEmpty }) else { throw BtcMapError.invalidData }
        return try raw.cities.map { city in
            guard let country = raw.countries[city.countryCode], !city.name.isEmpty,
                  city.lat.isFinite, (-90...90).contains(city.lat), city.lon.isFinite,
                  (-180...180).contains(city.lon), city.population >= 0 else { throw BtcMapError.invalidData }
            let region = city.admin1Code.isEmpty ? nil : raw.admin1["\(city.countryCode).\(city.admin1Code)"]
            let names = [city.name, city.asciiName.isEmpty ? city.name : city.asciiName].map(normalize)
            let text = normalize([city.name, city.asciiName, region ?? "", city.admin1Code, country, city.countryCode].joined(separator: " "))
            return CitySearchEntry(id: city.id, name: city.name, asciiName: city.asciiName.isEmpty ? city.name : city.asciiName,
                                   center: [city.lon, city.lat], countryCode: city.countryCode, countryName: country,
                                   admin1Code: city.admin1Code, regionName: region, population: city.population,
                                   normalizedNames: names, searchTokens: text.components(separatedBy: " "), searchText: text)
        }
    }

    // swiftlint:disable:next discouraged_optional_collection - nil: no location to bias by
    static func search(_ cities: [CitySearchEntry], query: String, limit: Int = 3, bias: [Double]? = nil) -> [CitySearchEntry] {
        let query = normalize(query)
        guard query.count >= 2, limit > 0 else { return [] }
        let tokens = query.components(separatedBy: " ")
        struct Match { let city: CitySearchEntry; let score: Int; let distance: Double }
        return cities.compactMap { city -> Match? in
            let score: Int
            if city.normalizedNames.contains(query) { score = 0 }
            else if city.normalizedNames.contains(where: { $0.hasPrefix(query) }) { score = 1 }
            else if tokens.allSatisfy({ token in city.searchTokens.contains(where: { $0.hasPrefix(token) }) }) { score = 2 }
            else if city.searchText.contains(query) { score = 3 }
            else { return nil }
            return Match(city: city, score: score, distance: bias.map { BtcMapDistance.kilometers(from: $0, to: city.center) } ?? 0)
        }.sorted {
            if $0.score != $1.score { return $0.score < $1.score }
            if $0.distance != $1.distance { return $0.distance < $1.distance }
            if $0.city.population != $1.city.population { return $0.city.population > $1.city.population }
            return $0.city.name.localizedCompare($1.city.name) == .orderedAscending
        }.prefix(limit).map(\.city)
    }

    private struct Snapshot: Decodable {
        let schemaVersion: Int
        let license: String
        let generatedAt: String
        let countries: [String: String]
        let admin1: [String: String]
        let cities: [City]
    }

    private struct City: Decodable {
        let id: Int, name: String, asciiName: String, lat: Double, lon: Double
        let countryCode: String, admin1Code: String, population: Int
        init(from decoder: Decoder) throws {
            var c = try decoder.unkeyedContainer()
            guard c.count == 8 else { throw BtcMapError.invalidData }
            id = try c.decode(Int.self)
            name = try c.decode(String.self)
            asciiName = try c.decode(String.self)
            lat = try c.decode(Double.self)
            lon = try c.decode(Double.self)
            countryCode = try c.decode(String.self)
            admin1Code = try c.decode(String.self)
            population = try c.decode(Int.self)
        }
    }
}
