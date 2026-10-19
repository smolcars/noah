import BarkBoltFFI
import SwiftUI

struct DebugView: View {
    enum Action: String, CaseIterable {
        case ark = "Get Ark Info", push = "Get Push Token", refresh = "Refresh Server"
        case maintenance = "Maintenance", maintenanceRefresh = "Maintenance Refresh", delegated = "Maintenance Delegated"
        case offboard = "Offboard All", decode = "Decode VTXO Hex", importVtxo = "Import VTXO"
        case drop = "Drop VTXO", unlock = "Unlock VTXO"
        var needsInput: Bool { [.offboard, .decode, .importVtxo, .drop, .unlock].contains(self) }
    }
    @State private var action = Action.ark
    @State private var input = ""
    @State private var result: String?
    @State private var busy = false
    @State private var confirming = false
    @State private var confirmation = ""
    private enum Field { case input }
    @FocusState private var focus: Field?
    var body: some View {
        Form {
            Section("Select Action") {
                Picker("Action", selection: $action) { ForEach(Action.allCases, id: \.self) { Text($0.rawValue).tag($0) } }.disabled(busy)
                if action.needsInput {
                    TextField(action == .offboard ? "Enter Bitcoin address" : "Enter VTXO hex or ID", text: $input, axis: .vertical)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().focused($focus, equals: .input).disabled(busy)
                }
                Button(busy ? "Running..." : "Execute") {
                    focus = nil
                    if action == .drop || action == .offboard { confirmation = ""; confirming = true } else { execute() }
                }.disabled(busy || (action.needsInput && input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
            }
            if let result {
                Section("Result") { Text(verbatim: result).font(.system(.caption, design: .monospaced)).textSelection(.enabled) }
            }
        }.settingsStyle("Debug")
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { focus = nil } } }
        .onChange(of: action) { _, _ in input = ""; result = nil }
        .alert(action == .drop ? "Dangerously remove this VTXO?" : "Offboard all funds?", isPresented: $confirming) {
            TextField("Type delete to confirm", text: $confirmation)
            Button("Cancel", role: .cancel) {}
            Button("Confirm", role: .destructive) { execute() }.disabled(confirmation.lowercased() != "delete")
        } message: { Text("This action changes your wallet. Type delete to confirm.") }
    }
    private func execute() {
        guard !busy else { return }; busy = true; result = nil
        let action = action
        let input = input.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            defer { busy = false }
            do {
                if action == .push {
                    switch try await PushRegistration.shared.register() {
                    case .registered(let token): result = token
                    case .permissionDenied: result = "Push permission not granted"
                    case .deviceNotSupported: result = "Push tokens are only available on a physical device"
                    }
                    return
                }
                result = try await WalletSession.shared.withOpenWallet { handles in
                    switch action {
                    case .ark: return String(describing: try await handles.wallet.arkInfo())
                    case .refresh: try await handles.wallet.refreshServer()
                    case .maintenance: try await handles.wallet.maintenance()
                    case .maintenanceRefresh: return try await handles.wallet.maintenanceRefresh() ?? "Maintenance refresh completed"
                    case .delegated: try await handles.wallet.maintenanceDelegated()
                    case .offboard: return String(describing: try await handles.wallet.offboardAll(bitcoinAddress: input))
                    case .decode: return String(describing: try decodeVtxo(hexOrBase64: input))
                    case .importVtxo: _ = try await handles.wallet.importVtxos(encodedVtxos: [input])
                    case .drop: try await handles.wallet.dangerousDropVtxo(id: input)
                    case .unlock: try await handles.wallet.unlockVtxos(vtxoIds: [input], expectedHolder: nil)
                    case .push: break
                    }
                    return "Action completed successfully"
                }
            } catch { result = "Action failed. Check your input and connection before trying again." }
        }
    }
}
