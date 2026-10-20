import SwiftUI

struct VtxosView: View {
    @State private var model = OnchainModel()
    @State private var filter = "All"
    @State private var selecting = false
    @State private var selected = Set<String>()
    private let filters = ["All", "Active", "Expiring", "Expired", "Locked"]
    private var visible: [VtxoModel] {
        (model.overview?.vtxos ?? []).filter { filter == "All" || $0.status == filter }
    }
    var body: some View {
        List {
            Section {
                Picker("Filter", selection: $filter) { ForEach(filters, id: \.self) { Text(LocalizedStringKey($0)).tag($0) } }
                if let overview = model.overview {
                    OnchainAmountRow(title: "Total", sats: overview.vtxos.reduce(0) { $0 + $1.amount })
                    LabeledContent("VTXOs", value: "\(overview.vtxos.count)")
                }
            }
            if selecting {
                Section {
                    Button("Select visible") { selected = Set(visible.filter(\.refreshable).map(\.id)) }
                    Button("Select expiring") {
                        selected = Set(
                            (model.overview?.vtxos ?? []).filter { $0.refreshable && ($0.expiring || $0.expired) }
                                .map(\.id))
                    }
                    Button("Clear selection") { selected = [] }
                    Button("Refresh \(selected.count) VTXOs") {
                        Task { await model.review(.refresh, ids: Array(selected)) }
                    }
                    .disabled(selected.isEmpty || model.locked)
                }
            }
            Section {
                if visible.isEmpty && !model.loading {
                    Text("No VTXOs in this view.").foregroundStyle(NoahColor.mutedForeground)
                }
                ForEach(visible) { vtxo in
                    if selecting {
                        Button {
                            if selected.contains(vtxo.id) {
                                selected.remove(vtxo.id)
                            } else {
                                selected.insert(vtxo.id)
                            }
                        } label: {
                            HStack {
                                Image(systemName: selected.contains(vtxo.id) ? "checkmark.circle.fill" : "circle")
                                VtxoRow(vtxo: vtxo)
                            }
                        }.disabled(!vtxo.refreshable || model.locked)
                    } else {
                        NavigationLink {
                            VtxoDetailView(id: vtxo.id)
                        } label: {
                            VtxoRow(vtxo: vtxo)
                        }
                    }
                }
            }
            OnchainStatusSection(model: model)
        }
        .settingsStyle("VTXOs")
        .toolbar {
            Button(selecting ? "Done" : "Select") {
                selecting.toggle()
                selected = []
            }.disabled(model.busy)
        }
        .modifier(OnchainScreen(model: model))
        .onChange(of: selected) { _, _ in model.invalidate() }
    }
}

private struct VtxoRow: View {
    @Environment(\.locale) private var locale

    let vtxo: VtxoModel
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            OnchainAmountRow(title: vtxo.status, sats: vtxo.amount)
            Text(vtxo.id).lineLimit(1).truncationMode(.middle).font(NoahFont.footnote)
            Text(L10n.text("Expiry: Block {_gt_value_2}", vtxo.expiry, locale: locale.identifier)).font(NoahFont.footnote).foregroundStyle(
                NoahColor.mutedForeground)
        }.padding(.vertical, 4)
    }
}

struct VtxoDetailView: View {
    let id: String
    @State private var model = OnchainModel()
    var body: some View {
        List {
            if let vtxo = model.overview?.vtxos.first(where: { $0.id == id }) {
                Section {
                    OnchainAmountRow(title: "Amount", sats: vtxo.amount)
                    LabeledContent("State", value: vtxo.status)
                    LabeledContent("VTXO ID") { Text(vtxo.id).textSelection(.enabled) }
                    LabeledContent("Type", value: vtxo.kind)
                    LabeledContent("Expiry Height", value: "\(vtxo.expiry)")
                    LabeledContent("Blocks Until Expiry", value: "\(Int64(vtxo.expiry) - Int64(vtxo.tip))")
                    LabeledContent("Exit Delta", value: "\(vtxo.exitDelta)")
                    LabeledContent("Point") { Text(vtxo.point).textSelection(.enabled) }
                    LabeledContent("Anchor Point") { Text(vtxo.anchorPoint).textSelection(.enabled) }
                    LabeledContent("Server Public Key") { Text(vtxo.serverPubkey).textSelection(.enabled) }
                    LabeledContent("Exit depth", value: "\(vtxo.exitDepth)")
                    LabeledContent("Exit transaction weight", value: "\(vtxo.exitWeight) wu")
                    LabeledContent("Registered with server", value: vtxo.registered ? "Yes" : "No")
                }
                if vtxo.expired || vtxo.expiring { Text("Refresh this VTXO to keep it available.") }
                Button("Refresh VTXO") { Task { await model.review(.refresh, ids: [id]) } }
                    .disabled(!vtxo.refreshable || model.locked)
            } else if !model.loading {
                Text("This VTXO is no longer in the wallet's current VTXOs. Refresh to check its status.")
            }
            OnchainStatusSection(model: model)
        }
        .settingsStyle("VTXO Details")
        .modifier(OnchainScreen(model: model))
    }
}
