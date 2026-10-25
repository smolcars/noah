import SwiftUI

struct QRHubView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.settings) private var settings
    @Environment(\.dismiss) private var dismiss
    @State private var hub: QRHubModel
    @State private var displayName = ""
    @State private var pendingScan: String?
    let isActive: Bool
    let onScan: (String) -> Void

    init(isActive: Bool, mode: QRHubModel.Mode = .myCode, onScan: @escaping (String) -> Void) {
        self.isActive = isActive
        self.onScan = onScan
        _hub = State(initialValue: QRHubModel(mode: mode))
    }

    var body: some View {
        @Bindable var hub = hub
        GeometryReader { geometry in
            let qrSide = AdaptiveLayout.qrSide(availableHeight: geometry.size.height - 80)
            ScrollView {
                VStack(spacing: 28) {
                    if hub.mode == .myCode {
                        if let address = app.server.state.lightningAddress, !address.isEmpty {
                            AdaptiveColumns { _ in
                                StyledQRCode(content: address)
                                    .frame(maxWidth: qrSide, maxHeight: qrSide).padding(20)
                                    .background(.white, in: RoundedRectangle(cornerRadius: 28))
                                    .frame(maxWidth: .infinity)
                                VStack(spacing: 28) {
                                    if !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                        Text(displayName).font(NoahFont.title)
                                    }
                                    Button {
                                        UIPasteboard.general.string = address
                                        hub.copied = true
                                    } label: {
                                        Text(LocalizedStringKey(hub.copied ? "Copied" : QRHubModel.displayAddress(address)))
                                            .multilineTextAlignment(.center).font(NoahFont.headline)
                                    }
                                    .accessibilityIdentifier("qr-copy-address")
                                    .task(id: hub.copied) {
                                        guard hub.copied else { return }
                                        do { try await Task.sleep(for: .milliseconds(1800)) } catch { return }
                                        hub.copied = false
                                    }
                                    Text("This QR contains only your Lightning address.")
                                        .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                                        .multilineTextAlignment(.center)
                                }.frame(maxWidth: .infinity)
                            }
                        } else {
                            NoahCard {
                                VStack(alignment: .leading, spacing: 16) {
                                    Text("Lightning address unavailable").font(NoahFont.headline)
                                    Text("Finish setting up your Lightning address before sharing your QR.")
                                    Button("Open Profile") { settings.open(.profile) }
                                }
                            }
                        }
                    } else {
                        NoahCard {
                            VStack(spacing: 12) {
                                Image(systemName: "qrcode.viewfinder").font(.largeTitle)
                                Text("Scan to pay").font(NoahFont.title)
                                Text("Scan a Bitcoin, Lightning, or Ark QR code and send from Noah.")
                                    .font(NoahFont.footnote).multilineTextAlignment(.center)
                            }.frame(maxWidth: .infinity)
                        }
                    }
                }
                .padding(24).frame(maxWidth: AdaptiveLayout.pairedWidth).frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("QR Code").navigationBarTitleDisplayMode(.inline)
        .background(NoahColor.background.ignoresSafeArea())
        .task {
            if hub.mode == .scan && isActive { await hub.openScanner() }
        }
        .onAppear {
            displayName = SettingsStorage.shared.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.displayName ?? ""
        }
        .onChange(of: isActive) { _, active in if !active { pendingScan = nil; hub.closeScanner() } }
        .onChange(of: scenePhase) { _, phase in if phase == .background { pendingScan = nil; hub.closeScanner() } }
        .onDisappear { if !hub.scannerPresented { hub.closeScanner() } }
        .alert("Permission required", isPresented: $hub.permissionError) {
            Button("OK", role: .cancel) { leaveScan() }
        } message: { Text("Camera permission is required to scan QR codes.") }
        .fullScreenCover(isPresented: Binding(get: { hub.scannerPresented }, set: { if !$0 { hub.closeScanner() } }), onDismiss: {
            hub.closeScanner()
            if let pendingScan { self.pendingScan = nil; onScan(pendingScan) }
            leaveScan()
        }) {
            SendScanner { value in pendingScan = hub.receive(value) }
                .safeAreaInset(edge: .bottom) {
                    PasteButton(payloadType: String.self) { values in
                        if let value = values.first { pendingScan = hub.receive(value, pasted: true) }
                    }
                    .accessibilityLabel("Paste payment request")
                    .accessibilityIdentifier("qr-scanner-paste")
                    .padding().frame(maxWidth: .infinity).background(NoahColor.background)
                }
        }
    }

    /// Home's scanner is the camera alone: once it closes or is refused, go back.
    private func leaveScan() {
        if hub.mode == .scan { dismiss() }
    }
}
