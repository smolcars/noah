import BarkBoltFFI
import Foundation

/// Explicit public result fields; never serialize a wallet handle or an error object.
enum DebugJSON {
    static func ark(_ info: ArkInfo?) throws -> String { try format(info.map { value($0) as Any } ?? NSNull()) }
    static func decoded(_ vtxo: Vtxo) throws -> String { try format(value(vtxo)) }
    static func imported(_ ids: [String]) throws -> String { try format(ids) }
    /// The VTXO xpub reveals every VTXO public key of the wallet: display only, never log.
    static func debugInfo(_ info: DebugInfo) throws -> String {
        try format(["network": String(describing: info.network), "mailboxId": info.mailboxId, "vtxoXpub": info.vtxoXpub])
    }
    static func format(_ value: Any) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes])
        return String(decoding: data, as: UTF8.self)
    }
    private static func value(_ result: ArkInfo) -> [String: Any] {
        [
            "mailboxPubkey": result.mailboxPubkey,
            "network": String(describing: result.network),
            "serverPubkey": result.serverPubkey,
            "roundIntervalSecs": result.roundIntervalSecs,
            "nbRoundNonces": result.nbRoundNonces,
            "vtxoExitDelta": result.vtxoExitDelta,
            "vtxoLifetime": result.vtxoLifetime,
            "vtxoExpiryDelta": result.vtxoExpiryDelta,
            "htlcSendExpiryDelta": result.htlcSendExpiryDelta,
            "htlcExpiryDelta": result.htlcExpiryDelta,
            "maxVtxoAmountSats": result.maxVtxoAmountSats.map { $0 as Any } ?? NSNull(),
            "requiredBoardConfirmations": result.requiredBoardConfirmations,
            "maxUserInvoiceCltvDelta": result.maxUserInvoiceCltvDelta,
            "minBoardAmountSats": result.minBoardAmountSats,
            "lnReceiveAntiDosRequired": result.lnReceiveAntiDosRequired,
            "feeSchedule": value(result.feeSchedule),
            "maxVtxoExitDepth": result.maxVtxoExitDepth,
        ]
    }
    private static func value(_ result: Vtxo) -> [String: Any] {
        [
            "serverPubkey": result.serverPubkey,
            "exitDelta": result.exitDelta,
            "anchorPoint": value(result.anchorPoint),
            "point": value(result.point),
            "id": result.id,
            "amountSats": result.amountSats,
            "expiryHeight": result.expiryHeight,
            "kind": result.kind,
            "state": state(result.state),
            "exitDepth": result.exitDepth,
            "exitTxWeightWu": result.exitTxWeightWu,
            "registered": result.registered,
        ]
    }
    private static func value(_ result: OutPoint) -> [String: Any] {
        [
            "txid": result.txid,
            "vout": result.vout,
        ]
    }
    private static func value(_ result: FeeSchedule) -> [String: Any] {
        [
            "board": value(result.board),
            "offboard": value(result.offboard),
            "refresh": value(result.refresh),
            "lightningReceive": value(result.lightningReceive),
            "lightningSend": value(result.lightningSend),
        ]
    }
    private static func value(_ result: BoardFees) -> [String: Any] {
        [
            "minFeeSats": result.minFeeSats,
            "baseFeeSats": result.baseFeeSats,
            "ppm": result.ppm,
        ]
    }
    private static func value(_ result: OffboardFees) -> [String: Any] {
        [
            "baseFeeSats": result.baseFeeSats,
            "fixedAdditionalVb": result.fixedAdditionalVb,
            "ppmExpiryTable": result.ppmExpiryTable.map(value),
        ]
    }
    private static func value(_ result: RefreshFees) -> [String: Any] {
        [
            "baseFeeSats": result.baseFeeSats,
            "ppmExpiryTable": result.ppmExpiryTable.map(value),
        ]
    }
    private static func value(_ result: LightningReceiveFees) -> [String: Any] {
        [
            "baseFeeSats": result.baseFeeSats,
            "ppm": result.ppm,
        ]
    }
    private static func value(_ result: LightningSendFees) -> [String: Any] {
        [
            "minFeeSats": result.minFeeSats,
            "baseFeeSats": result.baseFeeSats,
            "ppmExpiryTable": result.ppmExpiryTable.map(value),
        ]
    }
    private static func value(_ result: PpmExpiryFeeEntry) -> [String: Any] {
        [
            "expiryBlocksThreshold": result.expiryBlocksThreshold,
            "ppm": result.ppm,
        ]
    }
    private static func state(_ state: VtxoState) -> Any {
        switch state {
        case .unknown: return "unknown"
        case .spendable: return "spendable"
        case .spent: return "spent"
        case .exited: return "exited"
        case .locked: return "locked"
        }
    }
}
