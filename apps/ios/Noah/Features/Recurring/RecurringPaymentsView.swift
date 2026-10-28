import SwiftUI

/// Bitcoin → Recurring payments: Activity-style rows (name, "Monthly · next Nov 20" or a status,
/// amount); each opens its schedule page. Behaviour, limits and the journal are today's.
struct RecurringPaymentsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale
    @State private var executor: RecurringExecutor
    @State private var haptics = OnboardingHaptics()

    init(executor: RecurringExecutor = .shared) {
        _executor = State(initialValue: executor)
    }

    private var language: String { locale.identifier }
    private var schedules: [RecurringPayment] { executor.schedules }

    var body: some View {
        SettingsScroll(line: schedules.isEmpty ? nil : L10n.text("Paid from your Ark balance.", locale: language)) {
            if schedules.isEmpty { emptyState } else { scheduleList }
            if executor.syncPending {
                SettingsFine(text: L10n.text("Wake-up schedule hasn't synced yet. Noah will retry when opened.", locale: language))
            }
            if let error = executor.error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
            SettingsFine(text: L10n.text(
                "Lightning fee limits use Ark’s published fees, up to 2% or 200 sats, whichever is higher. Payments above the limit need manual approval.",
                locale: language)
                + " " + L10n.text("Keep notifications on so payments can go through while Noah is closed.", locale: language))
        }
        .settingsChrome("Recurring payments") {
            Button { open(.editor(nil)) } label: {
                if settings.wide { ActivityCircleIcon(systemName: "plus") } else { Label("New payment", systemImage: "plus") }
            }
            .accessibilityLabel(Text("New payment"))
            .accessibilityIdentifier("recurring-new")
        }
        .onboardingHaptics(haptics)
        .task { await executor.reload(); await ForegroundExecution.run { await executor.execute() } }
    }

    private var emptyState: some View {
        VStack(spacing: 6) {
            Image(systemName: "arrow.triangle.2.circlepath").font(.system(size: 22, weight: .medium))
                .accessibilityHidden(true)
                .frame(width: 56, height: 56).background(NoahTokens.Colors.soft, in: Circle())
            Text(L10n.text("No recurring payments", locale: language)).noahText(ActivityText.emptyTitle).padding(.top, 20)
            Text(L10n.text("Rent, allowances or donations, paid on time.", locale: language))
                .noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).multilineTextAlignment(.center)
            Button(L10n.text("New payment", locale: language)) { open(.editor(nil)) }
                .buttonStyle(HomePillStyle(height: 40))
                .padding(.top, 12)
                .accessibilityIdentifier("recurring-new-button")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }

    @ViewBuilder private var scheduleList: some View {
        VStack(spacing: 0) {
            ForEach(Array(schedules.enumerated()), id: \.element.id) { index, schedule in
                RecurringRow(schedule: schedule, sending: schedule.inFlight != nil && executor.running, hairline: index < schedules.count - 1) {
                    open(.schedule(schedule.id))
                }
            }
        }
        .padding(.top, 8)
        if schedules.contains(where: { $0.status == "active" }) {
            Button {
                haptics.play(.light)
                Task { await ForegroundExecution.run { await executor.execute(manual: true) } }
            } label: {
                HStack(spacing: 8) {
                    Text(L10n.text("Run due payments now", locale: language))
                    if executor.running { ProgressView() }
                }
                .noahText(HomeText.strong)
                .frame(minHeight: NoahTokens.Size.hitTarget)
            }
            .buttonStyle(.plain)
            .disabled(executor.running)
            .padding(.top, 10)
            .accessibilityIdentifier("recurring-run-now-button")
        }
    }

    private func open(_ page: SettingsPage) {
        haptics.play(.light)
        settings.open(page)
    }

    /// The approval sheet. Leaving an unresolved result (Close, or Done while still paying)
    /// only dismisses it; the attempt and the send lock stay as they are.
    /// The payment and quote the sheet reviews.
    struct ApprovalReview { var payment: SendPayment; var quote: SendQuote }
    static func approval(review: ApprovalReview, controller: SendPaymentController, pay: @escaping () -> Void,
                         dismiss: @escaping () -> Void, done: @escaping () -> Void) -> SendConfirmation {
        let payment = review.payment, quote = review.quote
        return SendConfirmation(confirm: pay, payment: payment, quote: quote, controller: controller, cancel: dismiss, done: done, onLeave: dismiss)
    }
}

extension RecurringPayment {
    /// What the row and the page say instead of the next date.
    func statusWord(sending: Bool) -> String? {
        if sending { return "Sending" }
        switch status {
        case "active": return nil
        case "paused": return "Paused"
        case "needs_approval": return "Needs approval"
        case "completed": return "Completed"
        default: return "Needs attention"
        }
    }

    var needsYou: Bool { status == "needs_approval" || status == "needs_attention" }

    /// "Monthly · next Nov 20".
    func nextLine(locale: Locale) -> String {
        let repeats = interval.text(locale: locale.identifier)
        guard let next = nextRunAt else { return repeats }
        let date = Date(timeIntervalSince1970: next / 1000).formatted(.dateTime.month(.abbreviated).day().locale(locale))
        return L10n.text("{interval} · next {date}", repeats, date, locale: locale.identifier)
    }

    var via: String {
        switch destinationType {
        case "ark": "Ark"
        case "offer", "bolt12": "BOLT12 offer"
        default: "Lightning"
        }
    }

    var avatar: HomeActivityRow.Avatar { destinationType == "ark" ? .initial(String(label.prefix(1)).lowercased()) : .lightning }
}

/// One schedule as an Activity row; "Needs approval" carries the red badge.
private struct RecurringRow: View {
    let schedule: RecurringPayment
    let sending: Bool
    let hairline: Bool
    let action: () -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                HomeAvatar(avatar: schedule.avatar, pending: sending, failed: schedule.needsYou, isNew: false)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: schedule.label).noahText(SettingsText.value).lineLimit(1)
                    Text(verbatim: schedule.statusWord(sending: sending).map { L10n.text($0, locale: locale.identifier) } ?? schedule.nextLine(locale: locale))
                        .noahText(HomeText.footnote)
                        .foregroundStyle(schedule.needsYou ? NoahTokens.Colors.failure : NoahTokens.Colors.muted)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(verbatim: app.isBalanceHidden ? "••••" : schedule.formattedAmount(unit: app.amountUnit, locale: locale))
                    .noahText(HomeText.amount).monospacedDigit()
                    .foregroundStyle(schedule.status == "paused" ? NoahTokens.Colors.muted : NoahTokens.Colors.foreground)
            }
            .frame(height: 66)
            .contentShape(Rectangle())
            .overlay(alignment: .bottom) { if hairline { HomeHairline().padding(.leading, 56) } }
        }
        .buttonStyle(SettingsRowPressStyle())
        .accessibilityIdentifier("recurring-payment-\(schedule.id)")
    }
}

/// One schedule (also what Activity's "Repeats ›" opens): what it pays and when, then Edit, Check
/// status, the two-step resume and Cancel as rows, with Pause, Resume or Review and pay at the bottom.
struct RecurringSchedulePage: View {
    private enum Sheet: String, Identifiable { case cancel, checked, twice; var id: Self { self } }

    let id: String
    @State private var executor: RecurringExecutor
    @Environment(\.settingsPage) private var currentPage
    @State private var review: RecurringExecutor.Review?
    @State private var sheet: Sheet?
    @State private var error: String?
    @State private var haptics = OnboardingHaptics()
    @Environment(AppModel.self) private var app
    @Environment(\.settings) private var settings
    @Environment(\.locale) private var locale

    init(id: String, executor: RecurringExecutor = .shared) {
        self.id = id
        _executor = State(initialValue: executor)
    }

    private var language: String { locale.identifier }
    private var schedule: RecurringPayment? { executor.schedules.first { $0.id == id } }

    var body: some View {
        Group {
            if let schedule { page(schedule) } else { RecurringPaymentsView(executor: executor) }
        }
        .task { await executor.reload() }
    }

    private func page(_ schedule: RecurringPayment) -> some View {
        let sending = schedule.inFlight != nil && executor.running
        let editable = !schedule.unsupportedStorage && schedule.status != "completed"
            && (schedule.status != "needs_attention" || schedule.requiresPolicyApproval)
        let checkable = !schedule.unsupportedStorage && (schedule.status == "needs_attention" || schedule.inFlight != nil)
        return SettingsScroll {
            header(schedule, sending: sending)
            details(schedule)
            actions(schedule, editable: editable, checkable: checkable)
            if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
        }
        .settingsChrome(nil)
        .settingsFooter {
            if schedule.status == "needs_approval" {
                Button(L10n.text("Review and pay", locale: language)) {
                    haptics.play(.light)
                    run { review = try await executor.review(schedule.id) }
                }
                .buttonStyle(SettingsActionStyle())
                .disabled(executor.running || executor.controller.locked)
                .accessibilityIdentifier("recurring-review-\(schedule.id)")
            } else if editable {
                toggleButton(schedule)
            }
        }
        .onboardingHaptics(haptics)
        .sheet(item: $review) { item in
            RecurringPaymentsView.approval(
                review: .init(payment: item.payment, quote: item.quote),
                controller: executor.controller,
                pay: { Task { await ForegroundExecution.run { await executor.execute(manual: true, approval: item) } } },
                dismiss: { review = nil },
                done: { review = nil; Task { await executor.reload() } })
                .onAppear { app.paymentLink.approvalOpen = true }
                .onDisappear { app.paymentLink.approvalOpen = false }
                .alert("Payment in progress", isPresented: Bindable(app.paymentLink).approvalBlocked) {
                    Button("OK", role: .cancel) {}
                } message: { Text(WalletTabs.blockedMessage(link: true)) }
        }
        .settingsSheet(item: $sheet) { sheet in sheetBody(sheet, schedule) }
    }

    @ViewBuilder private func header(_ schedule: RecurringPayment, sending: Bool) -> some View {
        HomeAvatar(avatar: schedule.avatar, pending: sending, failed: schedule.needsYou, isNew: false, size: 60).padding(.top, 6)
        Text(verbatim: app.isBalanceHidden ? "₿••••" : schedule.formattedAmount(unit: app.amountUnit, locale: locale))
            .noahText(ActivityDetailText.amount).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
            .padding(.top, 18)
        Text(verbatim: schedule.statusWord(sending: sending).map { L10n.text($0, locale: language) } ?? schedule.nextLine(locale: locale))
            .noahText(NoahTokens.Typography.label)
            .foregroundStyle(schedule.needsYou ? NoahTokens.Colors.failure : schedule.status == "paused" ? NoahTokens.Colors.muted : NoahTokens.Colors
                .foreground)
            .padding(.top, 12)
        Text(verbatim: schedule.label).noahText(NoahTokens.Typography.sub).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 3)
    }

    private func details(_ schedule: RecurringPayment) -> some View {
        VStack(spacing: 0) {
            SettingsDetailRow(label: L10n.text("To", locale: language), value: schedule.destination, mono: schedule.destination.count > 40,
                              copy: schedule.destination)
            SettingsDetailRow(label: L10n.text("Via", locale: language), value: L10n.text(schedule.via, locale: language))
            SettingsDetailRow(label: L10n.text("Repeats", locale: language), value: schedule.interval.text(locale: language))
            if schedule.status == "active", let next = schedule.nextRunAt {
                SettingsDetailRow(label: L10n.text("Next", locale: language),
                                  value: Date(timeIntervalSince1970: next / 1000).formatted(date: .abbreviated, time: .shortened))
            }
            SettingsDetailRow(label: L10n.text("Ends", locale: language), value: ends(schedule))
            SettingsDetailRow(label: L10n.text("Paid", locale: language),
                              value: schedule.maxOccurrences.map { L10n.text("{n} of {total}", schedule.occurrencesPaid, $0, locale: language) }
                                  ?? L10n.plural("{n} payments", count: schedule.occurrencesPaid, locale: language), numeric: true)
            if let run = schedule.runs.first {
                SettingsDetailRow(label: L10n.text("Last", locale: language),
                                  value: Date(timeIntervalSince1970: run.attemptedAt / 1000).formatted(.dateTime.month(.abbreviated).day().locale(locale))
                                      + " · " + L10n.text(
                                          run.status == "success" ? "Sent" : run.status == "skipped" ? "Skipped" : "Failed",
                                          locale: language),
                                  hairline: false)
            }
        }
        .padding(.top, 22)
    }

    @ViewBuilder private func actions(_ schedule: RecurringPayment, editable: Bool, checkable: Bool) -> some View {
        if let issue = schedule.lastError {
            SettingsFine(text: schedule.issue?.message(locale: language) ?? L10n.text(issue, locale: language), failure: true)
        }
        SettingsGroup {
            if editable {
                SettingsRow(title: L10n.text("Edit", locale: language), id: "recurring-edit-\(schedule.id)") {
                    haptics.play(.light)
                    settings.open(.editor(schedule.id))
                }
            }
            if checkable {
                SettingsRow(title: L10n.text("Check status", locale: language), trailing: .empty) {
                    haptics.play(.light)
                    Task { await executor.checkStatus(schedule.id) }
                }
                .disabled(executor.running)
                if !schedule.requiresPolicyApproval {
                    SettingsRow(title: L10n.text("Resume after checking history", locale: language), id: "recurring-override-\(schedule.id)") {
                        haptics.play(.light)
                        sheet = .checked
                    }
                    .disabled(executor.running)
                }
            }
            if schedule.status == "needs_approval" && editable { toggleRow(schedule) }
            SettingsRow(title: L10n.text(schedule.status == "completed" ? "Remove payment" : "Cancel payment", locale: language), danger: true,
                        trailing: .empty, hairline: false, id: "recurring-cancel-\(schedule.id)") {
                haptics.play(.light)
                sheet = .cancel
            }
        }
    }

    @ViewBuilder private func sheetBody(_ sheet: Sheet, _ schedule: RecurringPayment) -> some View {
        switch sheet {
        case .cancel:
            SettingsSheetBody(title: L10n.text("Cancel {name}?", schedule.label, locale: language),
                              line: L10n.text(
                                  "No more payments will be sent. Payments already sent can't be undone. An in-progress payment may still complete.",
                                  locale: language)) {
                EmptyView()
            } actions: {
                SettingsSheetButtons(cancel: L10n.text("Keep payment", locale: language),
                                     action: L10n.text(schedule.status == "completed" ? "Remove payment" : "Cancel payment", locale: language),
                                     destructive: true, actionID: "recurring-cancel-confirm", onCancel: { self.sheet = nil }) {
                    self.sheet = nil
                    run {
                        try await executor.cancel(schedule.id)
                        haptics.play(.success)
                        settings.toast("Canceled")
                        settings.leave(currentPage)
                    }
                }
            }
        case .checked:
            // The first resume step: check history before anything can be sent again.
            SettingsSheetBody(title: L10n.text("Have you checked?", locale: language),
                              line: L10n.text(
                                  "Check Activity for this payment's last attempt and ask the recipient whether it arrived.",
                                  locale: language)) {
                EmptyView()
            } actions: {
                SettingsSheetButtons(cancel: L10n.text("Keep stopped", locale: language), action: L10n.text("I checked", locale: language),
                                     actionID: "recurring-checked", onCancel: { self.sheet = nil }) {
                    self.sheet = nil
                    Task {
                        try? await Task.sleep(for: .milliseconds(350))
                        self.sheet = .twice
                    }
                }
            }
        case .twice:
            SettingsSheetBody(title: L10n.text(SendOverrideAudit.titleText, locale: language),
                              line: L10n.text(RecurringExecutor.overrideWarning, locale: language)) {
                EmptyView()
            } actions: {
                SettingsSheetButtons(cancel: L10n.text("Keep stopped", locale: language),
                                     action: L10n.text(RecurringExecutor.overrideConfirmation, locale: language), destructive: true,
                                     actionID: "recurring-resume-anyway", onCancel: { self.sheet = nil }) {
                    self.sheet = nil
                    run { try await executor.overrideAndResume(schedule.id, confirmed: true) }
                }
            }
        }
    }

    private func toggleTitle(_ schedule: RecurringPayment) -> String {
        schedule.status == "active" ? "Pause" : schedule.requiresPolicyApproval ? "Approve and resume" : "Resume"
    }

    private func toggle(_ schedule: RecurringPayment) {
        haptics.play(.selection)
        run {
            if schedule.status == "active" { try await executor.pause(schedule.id); settings.toast("Paused") }
            else { try await executor.resume(schedule.id); settings.toast("Resumed") }
        }
    }

    private func toggleRow(_ schedule: RecurringPayment) -> some View {
        SettingsRow(title: L10n.text(toggleTitle(schedule), locale: language), trailing: .empty, id: "recurring-toggle-\(schedule.id)") {
            toggle(schedule)
        }
    }

    /// Pause is the outlined pill, Resume the black one.
    @ViewBuilder private func toggleButton(_ schedule: RecurringPayment) -> some View {
        let button = Button(L10n.text(toggleTitle(schedule), locale: language)) { toggle(schedule) }
        if schedule.status == "active" {
            button.buttonStyle(OnboardingSecondaryButtonStyle(height: NoahTokens.Size.button)).accessibilityIdentifier("recurring-toggle-\(schedule.id)")
        } else {
            button.buttonStyle(SettingsActionStyle()).accessibilityIdentifier("recurring-toggle-\(schedule.id)")
        }
    }

    private func ends(_ schedule: RecurringPayment) -> String {
        if let end = schedule.endAt {
            return Date(timeIntervalSince1970: end / 1000).formatted(date: .abbreviated, time: .omitted)
        }
        if let count = schedule.maxOccurrences { return L10n.plural("After {n} payments", count: count, locale: language) }
        return L10n.text("Never", locale: language)
    }

    private func run(_ work: @escaping @MainActor () async throws -> Void) {
        error = nil
        Task { do { try await work() } catch { self.error = error.localizedDescription } }
    }
}
