import Foundation

/// Five taps on the version, each within two seconds of the last, turn debug mode on.
struct DebugUnlock: Equatable {
    static let taps = 5
    static let window: TimeInterval = 2
    private(set) var count = 0
    private var last = Date.distantPast

    /// Counts a tap; true when it's the fifth.
    mutating func tap(at now: Date = Date()) -> Bool {
        count = now.timeIntervalSince(last) < Self.window ? count + 1 : 1
        last = now
        guard count >= Self.taps else { return false }
        count = 0
        return true
    }

    var remaining: Int { Self.taps - count }
}

/// Debug mode is today's persisted flag. Turning it off hides Debug again; five taps bring it back.
enum DebugMode {
    static func isOn(_ storage: SettingsStorage = .shared) -> Bool {
        storage.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isDebugModeEnabled == true
    }

    static func set(_ on: Bool, _ storage: SettingsStorage = .shared) throws {
        var wallet = storage.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isDebugModeEnabled = on
        try storage.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
    }
}

/// Backup's status mark and its two lines, from today's backup state.
struct BackupStatus: Equatable {
    enum Mark: Equatable { case done, running, failed, pending, off, never }
    let mark: Mark
    let title: String
    /// A date to show (the last good backup), or nil.
    let lastBackup: Date?

    init(_ state: PersistedBackupState, enabled: Bool) {
        lastBackup = state.lastBackupAt.map { Date(timeIntervalSince1970: $0 / 1000) }
        switch state.lastBackupStatus {
        case .inProgress: mark = .running; title = "Backing up…"
        case .failed: mark = .failed; title = "Backup failed"
        case .idle, .success:
            // Changes (or a deleted backup) wait for an upload: never "Backed up" until one succeeds.
            if state.backupPending { mark = .pending; title = "Backup pending" }
            else if lastBackup == nil { mark = .never; title = "Not backed up yet" }
            else if enabled { mark = .done; title = "Backed up" }
            else { mark = .off; title = "Automatic backups off" }
        }
    }

    /// The root row's value.
    func rowValue(enabled: Bool) -> String {
        switch mark {
        case .failed: "Failed"
        case .running: "Backing up…"
        default: enabled ? "On" : "Off"
        }
    }
}

/// The VTXO list (Settings → VTXOs): grouped Expiring, Spendable and Locked, with today's filters in ⋯.
enum VtxoList {
    /// Today's filters, each one VTXO status (`VtxoModel.statusID`).
    enum Filter: String, CaseIterable, Identifiable {
        case all, active, expiring, expired, locked
        var id: Self { self }
        var title: String { VtxoStatus(rawValue: rawValue)?.label ?? "All" }
    }

    enum Group: String, CaseIterable {
        case expiring, spendable, locked
        var title: String {
            switch self {
            case .expiring: "Expiring"
            case .spendable: "Spendable"
            case .locked: "Locked"
            }
        }
    }

    static func group(_ vtxo: VtxoModel) -> Group {
        if vtxo.state == "Locked" { return .locked }
        return isDue(vtxo) ? .expiring : .spendable
    }

    /// Needs renewing within the wallet's refresh threshold (about a day on mainnet), or already expired.
    static func isDue(_ vtxo: VtxoModel) -> Bool { vtxo.refreshable && (vtxo.expiring || vtxo.expired) }

    static func includes(_ filter: Filter, _ vtxo: VtxoModel) -> Bool {
        filter == .all || vtxo.statusID?.rawValue == filter.rawValue
    }

    struct Section: Identifiable, Equatable {
        let group: Group
        let rows: [VtxoModel]
        var id: Group { group }
    }

    /// Non-empty groups in order, soonest expiry first.
    static func groups(_ vtxos: [VtxoModel], filter: Filter) -> [Section] {
        let visible = vtxos.filter { includes(filter, $0) }.sorted { $0.expiry < $1.expiry }
        return Group.allCases.compactMap { group in
            let rows = visible.filter { self.group($0) == group }
            return rows.isEmpty ? nil : Section(group: group, rows: rows)
        }
    }

    static func due(_ vtxos: [VtxoModel]) -> [VtxoModel] { vtxos.filter(isDue) }

    /// Select mode starts with the expiring ones (today's Select expiring).
    static func preselected(_ vtxos: [VtxoModel]) -> Set<String> { Set(due(vtxos).map(\.id)) }

    /// Today's Select visible: every renewable VTXO the filter shows.
    static func visibleSelection(_ vtxos: [VtxoModel], filter: Filter) -> Set<String> {
        Set(vtxos.filter { includes(filter, $0) && $0.refreshable }.map(\.id))
    }

    /// Only spendable VTXOs can be renewed; a locked one never joins the selection.
    static func toggle(_ id: String, in selection: Set<String>, vtxos: [VtxoModel]) -> Set<String> {
        guard let vtxo = vtxos.first(where: { $0.id == id }), vtxo.refreshable else { return selection }
        var selection = selection
        if selection.remove(id) == nil { selection.insert(id) }
        return selection
    }

    /// What's still selectable now: VTXOs can lock, spend or renew while the list is open.
    static func renewable(_ selection: Set<String>, in vtxos: [VtxoModel]) -> [String] {
        vtxos.filter { selection.contains($0.id) && $0.refreshable }.map(\.id)
    }

    /// Blocks until expiry as words at ten minutes a block ("under an hour", "{n} hours", "{n} days").
    static func expiresIn(_ blocks: Int64, locale: String) -> String {
        if blocks <= 0 { return L10n.text("Expired", locale: locale) }
        if blocks < 6 { return L10n.text("Expires in under an hour", locale: locale) }
        if blocks < 144 { return L10n.plural("Expires in {n} hours", count: Int(blocks / 6), locale: locale) }
        return L10n.plural("Expires in {n} days", count: Int(blocks / 144), locale: locale)
    }
}

/// What the delete sheet shows you'd need to restore.
struct DeleteChecklist: Equatable {
    enum Backup: Equatable { case never, failed, at(Date) }
    /// nil when the wallet isn't open (the blocked launch states).
    let balance: UInt64?
    let phraseSaved: Bool
    let backup: Backup

    init(summary: WalletSession.Summary?, needsPhraseBackup: Bool, backup: PersistedBackupState) {
        balance = summary?.balance.totalSats
        phraseSaved = !needsPhraseBackup
        if backup.lastBackupStatus == .failed { self.backup = .failed }
        else if let at = backup.lastBackupAt { self.backup = .at(Date(timeIntervalSince1970: at / 1000)) }
        else { self.backup = .never }
    }

    /// The word every typed-delete prompt asks for, in every language: never translated.
    static let word = "delete"
    static func confirms(_ typed: String) -> Bool { typed.trimmingCharacters(in: .whitespaces).lowercased() == word }
}

/// Advanced → Suspend wallet: turning it on asks first (one stray tap closes the wallet); off resumes.
enum SuspendSwitch: Equatable {
    case confirm, resume, none

    static func action(suspended: Bool, requested: Bool) -> Self {
        switch (suspended, requested) {
        case (false, true): .confirm
        case (true, false): .resume
        default: .none
        }
    }
}
