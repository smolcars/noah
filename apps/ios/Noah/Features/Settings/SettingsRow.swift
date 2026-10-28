import SwiftUI
import UIKit

/// Settings' text styles beyond the shared tokens (prototype sizes).
enum SettingsText {
    static let value = NoahTokens.TextStyle(size: 17, lineHeight: nil, weight: .regular, display: false, tracking: 0, textStyle: .body)
    static let line = NoahTokens.Typography.sub
    static let name = NoahTokens.TextStyle(size: 22, lineHeight: 28, weight: .semibold, display: false, tracking: -0.01, textStyle: .title2)
    static let heroName = NoahTokens.TextStyle(size: 28, lineHeight: 34, weight: .semibold, display: false, tracking: -0.02, textStyle: .title1)
    static let big = NoahTokens.TextStyle(size: 40, lineHeight: nil, weight: .semibold, display: true, tracking: -0.025, textStyle: .largeTitle)
    static let current = NoahTokens.TextStyle(size: 20, lineHeight: 26, weight: .medium, display: false, tracking: 0, textStyle: .title3)
    static let mono = Font.system(size: 12, weight: .regular, design: .monospaced)
}

/// Copies a value for this device only, expiring after two minutes (Universal Clipboard never sees it).
enum SecureCopy {
    static let lifetime: TimeInterval = 120

    static func options(now: Date = Date()) -> [UIPasteboard.OptionsKey: Any] {
        [.localOnly: true, .expirationDate: now.addingTimeInterval(lifetime)]
    }

    @MainActor static func copy(_ value: String, to pasteboard: UIPasteboard = .general) {
        pasteboard.setItems([[UIPasteboard.typeAutomatic: value]], options: options())
    }
}

/// Ends whichever field is editing (fields keep their own focus).
@MainActor func endEditing() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

// MARK: Page

/// A page's scrolling body: the optional one line under the title, then the content, at the
/// phone's 24-pt margins or the wide trailing pane's.
struct SettingsScroll<Content: View>: View {
    var line: String?
    @ViewBuilder var content: Content
    @Environment(\.settings) private var settings

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let line {
                    Text(verbatim: line).noahText(SettingsText.line).foregroundStyle(NoahTokens.Colors.muted)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, 4)
                }
                content
            }
            .padding(.leading, settings.wide ? 36 : NoahTokens.Space.screen)
            .padding(.trailing, settings.wide ? 32 : NoahTokens.Space.screen)
            .padding(.top, settings.wide ? 6 : 0)
            .padding(.bottom, 40)
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(NoahTokens.Colors.background.ignoresSafeArea())
        // Number pads have no return key; Done ends whichever field is editing.
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done", action: endEditing)
            }
        }
    }
}

extension View {
    /// The page's bottom actions over a fade (Activity's Pay again bar).
    func settingsFooter(@ViewBuilder _ buttons: () -> some View) -> some View {
        modifier(SettingsFooter(buttons: buttons()))
    }
}

private struct SettingsFooter<Buttons: View>: ViewModifier {
    let buttons: Buttons
    @Environment(\.settings) private var settings

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            GlassGroup(spacing: 10) { HStack(spacing: 10) { buttons } }
                .padding(.leading, settings.wide ? 36 : NoahTokens.Space.screen)
                .padding(.trailing, settings.wide ? 24 : NoahTokens.Space.screen)
                .padding(.top, 24).padding(.bottom, settings.wide ? 16 : 8)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    LinearGradient(colors: [NoahTokens.Colors.background.opacity(0), NoahTokens.Colors.background],
                                   startPoint: .top, endPoint: UnitPoint(x: 0.5, y: 0.46))
                        .ignoresSafeArea()
                }
        }
    }
}

/// One line of small print under a page's rows.
struct SettingsFine: View {
    let text: String
    var failure = false

    var body: some View {
        Text(verbatim: text).noahText(HomeText.footnote)
            .foregroundStyle(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 18)
    }
}

/// The shield line under a secret or a risky field.
struct SettingsWarning: View {
    let text: String
    var systemImage = "exclamationmark.shield"
    var failure = false

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: systemImage).accessibilityHidden(true).font(.system(size: 17, weight: .medium))
            Text(verbatim: text).noahText(NoahTokens.Typography.sub).fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.muted)
        .padding(.top, 18)
        .accessibilityElement(children: .combine)
    }
}

// MARK: Rows

/// "Security", "Bitcoin" and the rest: a muted label over its rows.
struct SettingsGroup<Rows: View>: View {
    var label: LocalizedStringKey?
    var first = false
    @ViewBuilder var rows: Rows

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let label {
                Text(label).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
                    .padding(.bottom, 2).accessibilityAddTraits(.isHeader)
            }
            rows
        }
        .padding(.top, first ? 10 : 26)
    }
}

/// The little round icon on a row's leading edge.
struct SettingsIcon: View {
    let symbol: String
    var danger = false

    var body: some View {
        Group {
            if symbol == "₿" {
                Text(verbatim: "₿").noahText(HomeText.initial)
            } else if symbol == "GitHubMark" || symbol == "TelegramMark" {
                // The assets are templates, so they take the row's tint like the symbols.
                Image(symbol).resizable().frame(width: 17, height: 17)
            } else {
                Image(systemName: symbol).font(.system(size: 16, weight: .medium))
            }
        }
        .foregroundStyle(danger ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
        .frame(width: 34, height: 34)
        .background(NoahTokens.Colors.soft, in: Circle())
        .accessibilityHidden(true)
    }
}

/// A title and its live value ("On", "Not saved", "12"), with a chevron, a switch or an
/// external-link mark. A small orange dot marks rows that need you; red only for failures.
struct SettingsRow: View {
    enum Dot { case hidden, attention, failure }
    enum Trailing { case chevron, external, empty }

    var icon: String?
    let title: String
    var sub: String?
    var value: String?
    var numeric = false
    var dot = Dot.hidden
    var failure = false
    var danger = false
    var bold = false
    var trailing = Trailing.chevron
    var selected = false
    /// A row that expands in place turns its chevron down.
    var expanded = false
    var hairline = true
    var id = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            SettingsRowLabel(icon: icon, title: title, sub: sub, danger: danger, bold: bold, hairline: hairline, selected: selected) {
                HStack(spacing: 7) {
                    if dot != .hidden {
                        Circle().fill(dot == .failure ? NoahTokens.Colors.failure : NoahTokens.Colors.orange).frame(width: 8, height: 8)
                    }
                    if let value {
                        Text(verbatim: value).noahText(numeric ? HomeText.amount : SettingsText.value).monospacedDigit()
                            .foregroundStyle(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.muted)
                            .lineLimit(1).truncationMode(.middle)
                    }
                }
                switch trailing {
                case .chevron:
                    Image(systemName: "chevron.right").accessibilityHidden(true).font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(NoahTokens.Colors.muted)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                case .external:
                    Image(systemName: "arrow.up.right.square").accessibilityHidden(true).font(.system(size: 16)).foregroundStyle(NoahTokens.Colors.muted)
                case .empty:
                    EmptyView()
                }
            }
        }
        .buttonStyle(SettingsRowPressStyle())
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }
}

/// A row with an iOS switch. Some switches ask first (Face ID, Suspend): `isOn` decides.
struct SettingsToggleRow: View {
    var icon: String?
    let title: String
    var sub: String?
    @Binding var isOn: Bool
    var hairline = true
    var id = ""

    var body: some View {
        // VoiceOver reads the switch alone: its title, its line as the hint.
        SettingsRowLabel(icon: icon, title: title, sub: sub, hairline: hairline, labelHidden: true) {
            Toggle(isOn: $isOn) { Text(verbatim: title) }
                .labelsHidden()
                .tint(NoahTokens.Colors.orange)
                .accessibilityLabel(Text(verbatim: title))
                .accessibilityHint(Text(verbatim: sub ?? ""))
                .accessibilityIdentifier(id)
        }
    }
}

/// Icon, title (and its small line), then the trailing value; a hairline under it.
struct SettingsRowLabel<Trailing: View>: View {
    var icon: String?
    let title: String
    var sub: String?
    var danger = false
    var bold = false
    var hairline = true
    var selected = false
    var labelHidden = false
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 14) {
            if let icon { SettingsIcon(symbol: icon, danger: danger) }
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: title).noahText(bold ? NoahTokens.Typography.label : SettingsText.value)
                    .foregroundStyle(danger ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
                if let sub {
                    Text(verbatim: sub).noahText(HomeText.footnote).foregroundStyle(NoahTokens.Colors.muted)
                }
            }
            .padding(.vertical, 9)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityHidden(labelHidden)
            trailing
        }
        .frame(minHeight: 54)
        .contentShape(Rectangle())
        .background {
            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(NoahTokens.Colors.soft)
                .padding(.vertical, 3).padding(.horizontal, -12)
                .opacity(selected ? 1 : 0)
        }
        .overlay(alignment: .bottom) {
            if hairline && !selected { HomeHairline().padding(.leading, icon == nil ? 0 : 48) }
        }
    }
}

/// The prototype's `.sr:active`: the soft rounded surface while pressed.
struct SettingsRowPressStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                RoundedRectangle(cornerRadius: 16, style: .continuous).fill(NoahTokens.Colors.soft)
                    .padding(.vertical, 3).padding(.horizontal, -12)
                    .opacity(configuration.isPressed ? 1 : 0)
            }
            .opacity(isEnabled ? 1 : 0.4)
            .contentShape(Rectangle())
    }
}

/// A key/value row (Activity's detail rows) with copy, explorer or a chevron.
struct SettingsDetailRow: View {
    let label: String
    let value: String
    var mono = false
    var numeric = false
    var compact = false
    var failure = false
    var copy: String?
    var explorer: URL?
    var chevron = false
    var glyph: String?
    var glyphColor: Color?
    var hairline = true
    var action: (() -> Void)?
    @State private var copied = false
    @Environment(\.settings) private var settings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let line = DetailLine(label: label, value: value, compact: compact, mono: mono, numeric: numeric, glyph: glyph,
                              chevron: chevron || action != nil, copied: copied, onCopy: copy.map { value in { copyValue(value) } },
                              explorer: explorer, hairline: hairline, failure: failure, glyphColor: glyphColor)
        if let action, let copy {
            // Opens on a tap and still copies from its own button, which a wrapping Button would swallow.
            line.contentShape(Rectangle())
                .onTapGesture(perform: action)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction(.default, action)
                .accessibilityAction(named: Text(L10n.text("Copy {label}", label))) { copyValue(copy) }
        } else if let action {
            Button(action: action) { line }.buttonStyle(.plain)
        } else {
            line
        }
    }

    private func copyValue(_ value: String) {
        SecureCopy.copy(value)
        withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy)) { copied = true }
        settings.toast("Copied")
        Task {
            try? await Task.sleep(for: .seconds(1.2))
            withAnimation(NoahTokens.Motion.travel(reduceMotion)) { copied = false }
        }
    }
}

/// Collapsed Details (Activity's): one row that opens everything technical.
struct SettingsDetails<Rows: View>: View {
    @Binding var open: Bool
    @ViewBuilder var rows: Rows
    @State private var haptics = OnboardingHaptics()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                haptics.play(.selection)
                withAnimation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.soft)) { open.toggle() }
            } label: {
                HStack {
                    Text("Details").noahText(NoahTokens.Typography.label)
                    Spacer()
                    Image(systemName: "chevron.right").font(.system(size: 15, weight: .semibold)).foregroundStyle(NoahTokens.Colors.muted)
                        .rotationEffect(.degrees(open ? 90 : 0))
                }
                .frame(height: 48)
                .contentShape(Rectangle())
                .overlay(alignment: .top) { HomeHairline() }
                .overlay(alignment: .bottom) { HomeHairline() }
            }
            .buttonStyle(.plain)
            .accessibilityValue(Text(open ? "Expanded" : "Collapsed"))
            .padding(.top, 18)
            if open {
                VStack(alignment: .leading, spacing: 0) { rows }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .onboardingHaptics(haptics)
    }
}

/// A section label inside Details or a list of rows.
struct SettingsSection: View {
    let title: LocalizedStringKey

    var body: some View {
        Text(title).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
            .padding(.top, 22).accessibilityAddTraits(.isHeader)
    }
}

// MARK: Sheets

/// A sheet's title, its line and content, then its actions (prototype `.sheet`).
struct SettingsSheetBody<Content: View, Actions: View>: View {
    let title: String
    var line: String?
    @ViewBuilder var content: Content
    @ViewBuilder var actions: Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: title).noahText(NoahTokens.Typography.sheetTitle).foregroundStyle(NoahTokens.Colors.foreground)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let line {
                Text(verbatim: line).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
            }
            content
            GlassGroup(spacing: 10) { HStack(spacing: 10) { actions } }
                .padding(.top, 22)
        }
        .padding(.horizontal, NoahTokens.Space.screen)
        .padding(.top, 8)
        .padding(.bottom, NoahTokens.Space.l)
        .foregroundStyle(NoahTokens.Colors.foreground)
    }
}

extension View {
    /// Phones: a system sheet sized to its content. Wide windows: a sheet inside the trailing
    /// pane, so it never crosses the fold. `locked` stops dismissal while something runs.
    func settingsSheet<Item: Identifiable & Equatable>(
        item: Binding<Item?>, locked: Bool = false, @ViewBuilder content: @escaping (Item) -> some View) -> some View {
        modifier(SettingsSheetModifier(item: item, locked: locked, sheet: content))
    }
}

private struct SettingsSheetModifier<Item: Identifiable & Equatable, Sheet: View>: ViewModifier {
    @Binding var item: Item?
    let locked: Bool
    let sheet: (Item) -> Sheet
    @State private var height: CGFloat = 300
    /// The layout now, for the system sheet's setter: unfolding dismisses that sheet, and its late
    /// "dismissed" must not close the pane sheet that replaces it.
    @State private var layout = Layout()
    @Environment(\.settings) private var settings
    @Environment(\.settingsSheetLeading) private var leading
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private final class Layout { var wide = false }

    func body(content: Content) -> some View {
        // swiftlint:disable:next redundant_discardable_let - a ViewBuilder body needs `let _ =` to run a statement
        let _ = { layout.wide = settings.wide }()
        content
            .sheet(item: Binding(get: { settings.wide ? nil : item }, set: { if !layout.wide { item = $0 } })) { value in
                ScrollView {
                    sheet(value).padding(.top, 22)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
                }
                .scrollBounceBehavior(.basedOnSize)
                .presentationDetents([.height(min(max(height, 200), 720))])
                .presentationDragIndicator(.visible)
                .interactiveDismissDisabled(locked)
            }
            .overlay {
                GeometryReader { proxy in
                    ZStack {
                        if settings.wide, let value = item {
                            HomePaneSheet(width: proxy.size.width - leading, onDismiss: { if !locked { item = nil } }) {
                                ScrollView { sheet(value) }.scrollBounceBehavior(.basedOnSize).frame(maxHeight: proxy.size.height - 60)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    .animation(NoahTokens.Motion.travel(reduceMotion), value: item)
                }
            }
    }
}

/// Cancel (outlined glass) beside the sheet's action; the action turns red for destructive ones.
struct SettingsSheetButtons: View {
    let cancel: String
    let action: String
    var destructive = false
    var enabled = true
    var busy = false
    var actionID = ""
    let onCancel: () -> Void
    let onAction: () -> Void

    var body: some View {
        Button(action: onCancel) { Text(verbatim: cancel) }
            .buttonStyle(OnboardingSecondaryButtonStyle(height: NoahTokens.Size.button))
            .disabled(busy)
        Button(action: onAction) {
            HStack(spacing: 8) {
                if busy { ProgressView().tint(destructive ? .white : NoahTokens.Colors.background) }
                Text(verbatim: action)
            }
        }
        .buttonStyle(SettingsActionStyle(destructive: destructive))
        .disabled(!enabled || busy)
        .accessibilityIdentifier(actionID)
    }
}

/// The black pill, or red for destructive actions once they're enabled (prototype `.pill.bad`).
struct SettingsActionStyle: ButtonStyle {
    var destructive = false
    /// Large text wraps the words inside a taller pill rather than cutting them off (the exit pages, as
    /// their hold buttons do). Off by default: the rest of Settings keeps one line.
    var wrap = false

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, destructive: destructive, wrap: wrap)
    }

    private struct Styled: View {
        let configuration: Configuration
        let destructive: Bool
        let wrap: Bool
        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            let red = destructive && isEnabled
            configuration.label
                .noahText(NoahTokens.Typography.button)
                .foregroundStyle(red ? Color.white : NoahTokens.Colors.background)
                .lineLimit(wrap ? nil : 1)
                .multilineTextAlignment(.center)
                .padding(.horizontal, NoahTokens.Space.xl)
                .padding(.vertical, wrap ? 10 : 0)
                .frame(maxWidth: .infinity, minHeight: NoahTokens.Size.button)
                .contentShape(Capsule())
                .glassCompat(in: Capsule(), interactive: isEnabled,
                             tint: red ? NoahTokens.Colors.failure : isEnabled ? NoahTokens.Colors.foreground : NoahTokens.Colors.foreground.opacity(0.2))
                .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
                .animation(NoahTokens.Motion.tap, value: configuration.isPressed)
                .animation(NoahTokens.Motion.travel(reduceMotion, NoahTokens.Motion.bouncy), value: red)
        }
    }
}

/// A glass text field (Send's To field): orange when focused, red when wrong.
struct SettingsField: View {
    var label: String?
    let placeholder: String
    @Binding var text: String
    var failure = false
    var keyboard = UIKeyboardType.default
    var tag: String?
    var multiline = false
    var id = ""
    var focusChanged: ((Bool) -> Void)?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let label {
                Text(verbatim: label).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted)
            }
            field
        }
        .padding(.top, 20)
        .onChange(of: focused) { _, focused in focusChanged?(focused) }
    }

    private var field: some View {
        // swiftlint:disable:next accessibility_trait_for_button - the tap only focuses the field; the TextField is the accessible control
        HStack(spacing: 10) {
            Group {
                if multiline {
                    TextField(text: $text, prompt: Text(verbatim: placeholder).foregroundStyle(NoahTokens.Colors.muted), axis: .vertical) {
                        Text(verbatim: label ?? placeholder)
                    }
                    .lineLimit(4...12)
                } else {
                    TextField(text: $text, prompt: Text(verbatim: placeholder).foregroundStyle(NoahTokens.Colors.muted)) {
                        Text(verbatim: label ?? placeholder)
                    }
                }
            }
            .noahText(NoahTokens.Typography.body)
            .tint(NoahTokens.Colors.orange)
            .keyboardType(keyboard)
            .submitLabel(.done)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .focused($focused)
            .accessibilityIdentifier(id)
            if let tag {
                Text(verbatim: tag).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted).lineLimit(1).fixedSize()
            }
        }
        .padding(.leading, 16).padding(.trailing, 12)
        .padding(.vertical, multiline ? 14 : 0)
        .frame(minHeight: 54)
        .glassCompat(in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.orange, lineWidth: 1.5)
                .opacity(failure || focused ? 1 : 0)
        }
        .contentShape(Rectangle())
        .onTapGesture { focused = true }
    }
}

/// The page's big status mark: an orange ring with a check, a turning arc, a red "!", or a muted icon.
struct SettingsStatusMark: View {
    enum Kind: Equatable { case done, running, failed, idle(String), on(String) }
    let kind: Kind
    @State private var turning = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            switch kind {
            case .done:
                Circle().strokeBorder(NoahTokens.Colors.orange, lineWidth: 2.5)
                CheckShape(points: [CGPoint(x: 18.5, y: 28.8), CGPoint(x: 24.9, y: 35.2), CGPoint(x: 37.9, y: 21.8)], box: 56)
                    .stroke(NoahTokens.Colors.foreground, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            case .running:
                Circle().strokeBorder(NoahTokens.Colors.hairline, lineWidth: 2.5)
                Circle().trim(from: 0, to: 0.25)
                    .stroke(NoahTokens.Colors.foreground, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .padding(1.25)
                    .rotationEffect(.degrees(turning ? 360 : 0))
                    .onAppear {
                        guard !reduceMotion else { return }
                        withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: false)) { turning = true }
                    }
            case .failed:
                Circle().strokeBorder(NoahTokens.Colors.failure, lineWidth: 2.5)
                Text(verbatim: "!").font(.system(size: 24, weight: .bold)).foregroundStyle(NoahTokens.Colors.failure)
            case .idle(let symbol):
                Circle().strokeBorder(NoahTokens.Colors.hairline, lineWidth: 2.5)
                Image(systemName: symbol).font(.system(size: 20, weight: .medium)).foregroundStyle(NoahTokens.Colors.muted)
            case .on(let symbol):
                Circle().strokeBorder(NoahTokens.Colors.orange, lineWidth: 2.5)
                Image(systemName: symbol).font(.system(size: 20, weight: .medium)).foregroundStyle(NoahTokens.Colors.foreground)
            }
        }
        .frame(width: 56, height: 56)
        .accessibilityHidden(true)
    }
}

/// The mark and its two lines at the top of Backup and Notifications.
struct SettingsStatusHeader: View {
    let mark: SettingsStatusMark.Kind
    let title: String
    let detail: String
    var failure = false

    var body: some View {
        HStack(spacing: 16) {
            SettingsStatusMark(kind: mark)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title).noahText(SettingsText.current)
                    .foregroundStyle(failure ? NoahTokens.Colors.failure : NoahTokens.Colors.foreground)
                Text(verbatim: detail).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.top, 14)
        .accessibilityElement(children: .combine)
    }
}

// MARK: Receipts

extension View {
    /// Today's on-chain receipts and their buttons (`OnchainStatusSection`), in Settings' type and rows.
    func settingsReceipts() -> some View {
        VStack(alignment: .leading, spacing: 10) { self }
            .noahText(NoahTokens.Typography.sub)
            .foregroundStyle(NoahTokens.Colors.foreground)
            .labeledContentStyle(SettingsLabeledContentStyle())
            .buttonStyle(HomePillStyle(prominent: false, height: 40))
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A receipt's label and value as a detail row.
struct SettingsLabeledContentStyle: LabeledContentStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 18) {
            configuration.label.foregroundStyle(NoahTokens.Colors.muted)
            Spacer(minLength: 0)
            configuration.content.monospacedDigit().multilineTextAlignment(.trailing)
        }
        .frame(minHeight: 44)
        .overlay(alignment: .bottom) { HomeHairline() }
    }
}
