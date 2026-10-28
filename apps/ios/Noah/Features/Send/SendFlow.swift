import Foundation

enum SendRail: String, Codable, CaseIterable, Sendable { case ark, lightning, onchain
    var label: String { switch self { case .ark: "Ark"; case .lightning: "Lightning"; case .onchain: "On-chain" } }
}
enum SendSource: String, Codable, CaseIterable, Sendable { case offchain, onchain
    var label: String { self == .offchain ? "Ark balance" : "On-chain wallet" }
}
enum SendStage: String, Codable { case amount, recipient, method, source, review }
enum SendEntry: String, Codable { case amountFirst = "amount-first", recipientFirst = "recipient-first", max }
struct SendProgress: Codable {
    var entry: SendEntry = .amountFirst
    var amountConfirmed = false
    var recipientConfirmed = false
    var railConfirmed = false
    var sourceConfirmed = false
    var rails: [SendRail] = []
    var selectedRail: SendRail?
    // swiftlint:disable:next discouraged_optional_boolean - nil: unknown, or not a BIP-321 request (parse output)
    var selectedRailAvailable: Bool?
    var sourceOptions: [SendSource] = []
    var next: SendStage {
        if entry == .max { return !sourceConfirmed ? .source : recipientConfirmed ? .review : .recipient }
        for stage in entry == .amountFirst ? [SendStage.amount, .recipient] : [.recipient, .amount] {
            if stage == .amount && !amountConfirmed { return .amount }
            if stage == .recipient && !recipientConfirmed { return .recipient }
        }
        if (rails.count > 1 || selectedRailAvailable == false) && !railConfirmed { return .method }
        if selectedRail == .onchain && sourceOptions.count != 1 && !sourceConfirmed { return .source }
        return .review
    }
}
enum SendFlow {
    // swiftlint:disable:next discouraged_optional_collection - nil: unknown, or not a BIP-321 request (parse output)
    static func rails(type: String?, request: [String: String]?) -> [SendRail] {
        if type == "bip321", let request {
            return SendRail.allCases.filter { method(rail: $0, request: request) != nil }
        }
        switch type { case "ark": return [.ark]; case "lightning", "offer", "lnurl": return [.lightning]
        case "onchain": return [.onchain]; default: return [] }
    }
    static func method(rail: SendRail, request: [String: String]) -> String? {
        switch rail {
        case .ark: return request["arkAddress"] != nil ? "ark" : nil
        case .lightning: return request["lightningInvoice"] != nil ? "lightning" : request["offer"] != nil ? "offer" : nil
        case .onchain: return request["onchainAddress"] != nil ? "onchain" : nil
        }
    }
    static func label(type: String?) -> String? {
        switch type {
        case "ark": "Ark address"; case "lightning": "Lightning invoice"; case "offer": "Lightning offer"
        case "lnurl": "Lightning address"; case "onchain": "On-chain address"; case "bip321": "Bitcoin payment request"; default: nil
        }
    }
    static func recommend(_ rails: [SendRail], availability: [String: Bool]) -> SendRail? {
        rails.first { availability[$0.rawValue] != false } ?? rails.first
    }
    static func canNote(type: String?, allowed: Int) -> Bool { type == "lnurl" && allowed > 0 }
    // swiftlint:disable:next discouraged_optional_collection - nil: unknown, or not a BIP-321 request (parse output)
    static func canMax(type: String?, request: [String: String]?) -> Bool {
        type == "onchain" || (type == "bip321" && request?["onchainAddress"] != nil)
    }
    static func maxBalance(source: SendSource?, onchain: UInt64, offchain: UInt64) -> UInt64 {
        switch source { case .onchain: onchain; case .offchain: offchain; case nil: max(onchain, offchain) }
    }
    static func satsPerVByte(satsPerKwu: UInt64) -> Double { Double(satsPerKwu) * 4 / 1000 }
    // FFI accepts integer sat/vB. Round up, never truncate a fractional fee rate.
    static func sdkFeeRate(satsPerKwu: UInt64) -> UInt64 { satsPerKwu / 250 + (satsPerKwu.isMultiple(of: 250) ? 0 : 1) }
}
