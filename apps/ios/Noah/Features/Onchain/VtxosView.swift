import SwiftUI

struct VtxosView: View {
    @State private var model: OnchainModel
    @State private var filter: VtxoStatus?
    @State private var selecting = false
    @State private var viewedID: String?
    @ScaledMetric(relativeTo: .body) private var minimumPaneWidth: CGFloat = 260
    @State private var selected = Set<String>()
    init(model: OnchainModel = OnchainModel()) { _model = State(initialValue: model) }
    private var visible: [VtxoModel] {
        (model.overview?.vtxos ?? []).filter { filter == nil || $0.statusID == filter }
    }
    private var selectedVtxos: [VtxoModel] {
        OnchainDisplay.currentSelection(model.overview?.vtxos ?? [], ids: selected)
    }
    var body: some View {
        GeometryReader { geometry in
            let expanded = AdaptiveLayout.expanded(width: geometry.size.width, minimumPaneWidth: minimumPaneWidth)
            let compactDetail = !expanded && viewedID != nil
            HStack(spacing: 0) {
                vtxoList.settingsStyle("VTXOs").frame(maxWidth: .infinity)
                if expanded {
                    Divider()
                    VStack(spacing: 0) {
                        if let viewedID {
                            HStack {
                                Spacer()
                                Button("Close", systemImage: "xmark") { self.viewedID = nil }
                                    .labelStyle(.iconOnly).frame(minWidth: 44, minHeight: 44)
                            }.padding(.horizontal)
                            VtxoDetailView(id: viewedID, model: model)
                        } else {
                            ContentUnavailableView("Select a VTXO to see its details", systemImage: "doc.text.magnifyingglass")
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityIdentifier("vtxo-detail-pane")
                }
            }
            .readableWidth(AdaptiveLayout.pairedWidth)
            .opacity(compactDetail ? 0 : 1)
            .accessibilityHidden(compactDetail)
            .allowsHitTesting(!compactDetail)
            .overlay {
                if compactDetail, let viewedID {
                    VtxoDetailView(id: viewedID, model: model)
                        .background(NoahColor.background)
                }
            }
            .navigationTitle(compactDetail ? "VTXO Details" : "VTXOs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if compactDetail {
                    Button("Done") { viewedID = nil }
                } else {
                    Button(selecting ? "Done" : "Select") {
                        selecting.toggle()
                        selected = []
                    }.disabled(model.busy)
                }
            }
        }
        .background(NoahColor.background)
        .modifier(OnchainScreen(model: model))
        .onChange(of: selected) { _, _ in model.invalidate() }
    }

    private var vtxoList: some View {
        List {
            Section {
                Picker("Filter", selection: $filter) {
                    Text("All").tag(nil as VtxoStatus?)
                    ForEach(VtxoStatus.allCases, id: \.self) { status in
                        Text("\(L10n.text(status.label)) (\((model.overview?.vtxos ?? []).filter { $0.statusID == status }.count))")
                            .tag(Optional(status))
                    }
                }
                if let overview = model.overview {
                    OnchainAmountRow(title: "Total", sats: overview.vtxos.reduce(0) { $0 + $1.amount })
                    LabeledContent("VTXOs", value: "\(overview.vtxos.count)")
                }
            }
            if selecting {
                Section {
                    LabeledContent("Selected", value: "\(selectedVtxos.count)")
                    OnchainAmountRow(title: "Amount selected", sats: selectedVtxos.reduce(0) { $0 + $1.amount })
                    Button("Select visible") { selected = Set(visible.filter(\.refreshable).map(\.id)) }
                    Button("Select expiring") {
                        selected = Set(
                            (model.overview?.vtxos ?? []).filter { $0.refreshable && ($0.expiring || $0.expired) }
                                .map(\.id))
                    }
                    Button("Clear selection") { selected = [] }
                    Button("Refresh \(selectedVtxos.count) VTXOs") {
                        Task { await model.review(.refresh, ids: Array(selected)) }
                    }
                    .disabled(selectedVtxos.isEmpty || model.locked)
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
                        Button { viewedID = vtxo.id } label: {
                            VtxoRow(vtxo: vtxo)
                        }
                    }
                }
            }
            OnchainStatusSection(model: model)
        }
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
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    let id: String
    let model: OnchainModel
    var body: some View {
        List {
            if let vtxo = model.overview?.vtxos.first(where: { $0.id == id }) {
                Section {
                    OnchainAmountRow(title: "Amount", sats: vtxo.amount)
                    LabeledContent("State") { Text(LocalizedStringKey(vtxo.state)) }
                    LabeledContent("Status") { Text(LocalizedStringKey(vtxo.status)) }
                    NoahDetailRow(label: "VTXO ID", value: vtxo.id, copyable: true)
                    LabeledContent("Type", value: vtxo.kind)
                    LabeledContent("Current Block Height", value: vtxo.tip.formatted(.number.locale(locale)))
                    LabeledContent("Expiry Height", value: vtxo.expiry.formatted(.number.locale(locale)))
                    LabeledContent("Blocks Until Expiry", value: vtxo.expiry <= vtxo.tip ? L10n.text("Expired", locale: locale.identifier) : (vtxo.expiry - vtxo.tip).formatted(.number.locale(locale)))
                    LabeledContent("Exit Delta", value: "\(vtxo.exitDelta)")
                    LabeledContent("Point") { Text(vtxo.point).textSelection(.enabled) }
                    NoahDetailRow(label: "Anchor Point", value: vtxo.anchorPoint, copyable: true)
                    if let url = OnchainDisplay.explorerURL(vtxo.anchorPoint, variant: app.variant) {
                        Link("View in explorer", destination: url)
                    }
                    NoahDetailRow(label: "Server Public Key", value: vtxo.serverPubkey, copyable: true)
                    LabeledContent("Exit depth", value: "\(vtxo.exitDepth)")
                    LabeledContent("Exit transaction weight", value: "\(vtxo.exitWeight) wu")
                    LabeledContent("Registered with server") { Text(LocalizedStringKey(vtxo.registered ? "Yes" : "No")) }
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
    }
}
