import SwiftUI

struct RecurringPaymentsView: View {
    @Environment(\.locale) private var locale

    @State private var executor: RecurringExecutor
    init(executor: RecurringExecutor = .shared) { _executor = State(initialValue: executor) }
    @State private var cancelling: RecurringPayment?
    @State private var overriding: RecurringPayment?
    @State private var review: RecurringExecutor.Review?
    @State private var actionError: String?
    @ScaledMetric(relativeTo: .body) private var minimumPaneWidth: CGFloat = 260
    @State private var editingID: String?
    var body: some View {
        GeometryReader { geometry in
            let expanded = !executor.schedules.isEmpty && AdaptiveLayout.expanded(width: geometry.size.width, minimumPaneWidth: minimumPaneWidth)
            HStack(alignment: .top, spacing: 0) {
                List {
                    Section {
                        Text("Automatic payments from your Ark balance. Lightning fee limits use Ark’s published fees, up to 2% or 200 sats, whichever is higher. Payments above the limit need manual approval. Keep notifications enabled.")
                            .foregroundStyle(NoahColor.mutedForeground)
                        NavigationLink("New recurring payment") { RecurringPaymentEditorView(executor: executor) }
                            .accessibilityIdentifier("recurring-new-button")
                    }
                    if executor.schedules.isEmpty {
                        Section {
                            Text("No recurring payments yet. Schedule rent, subscriptions, allowances or donations to an Ark address, Lightning address or BOLT12 offer.")
                                .foregroundStyle(NoahColor.mutedForeground)
                        }
                    }
                    ForEach(Array(executor.schedules.enumerated()).filter { !expanded || $0.offset.isMultiple(of: 2) }.map(\.element)) { schedule in
                        scheduleSection(schedule)
                    }
                    if executor.schedules.contains(where: { $0.status == "active" }) {
                        Section {
                            Button { Task { await ForegroundExecution.run { await executor.execute(manual: true) } } } label: {
                                HStack { Text("Run due payments now"); if executor.running { Spacer(); ProgressView() } }
                            }.disabled(executor.running).accessibilityIdentifier("recurring-run-now-button")
                        }
                    }
                    if executor.syncPending { Text("Wake-up schedule hasn't synced yet. Noah will retry when opened.").font(NoahFont.footnote) }
                    if let error = actionError ?? executor.error { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) }
                }
                if expanded {
                    List {
                        ForEach(Array(executor.schedules.enumerated()).filter { !$0.offset.isMultiple(of: 2) }.map(\.element)) { schedule in
                            scheduleSection(schedule)
                        }
                    }
                }
            }
        }
        .settingsStyle("Recurring payments", maximumWidth: AdaptiveLayout.pairedWidth)
        .navigationDestination(item: $editingID) { id in
            if let schedule = executor.schedules.first(where: { $0.id == id }) {
                RecurringPaymentEditorView(executor: executor, schedule: schedule)
            }
        }
        .sheet(item: $review) { item in
            SendConfirmation(confirm: { Task { await ForegroundExecution.run { await executor.execute(manual: true, approval: item) } } },
                             payment: item.payment, quote: item.quote, controller: executor.controller,
                             cancel: { review = nil }, done: { review = nil; Task { await executor.reload() } })
        }
        .task { await executor.reload(); await ForegroundExecution.run { await executor.execute() } }
        .alert("Cancel recurring payment?", isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } })) {
            Button("Keep payment", role: .cancel) { cancelling = nil }
            Button("Cancel payment", role: .destructive) {
                if let schedule = cancelling { run { try await executor.cancel(schedule.id) } }
                cancelling = nil
            }
        } message: { Text("No more payments will be sent. Payments already sent can't be undone. An in-progress payment may still complete.") }
        .alert(SendOverrideAudit.titleText, isPresented: Binding(get: { overriding != nil }, set: { if !$0 { overriding = nil } })) {
            Button("Keep stopped", role: .cancel) { overriding = nil }
            Button(LocalizedStringKey(RecurringExecutor.overrideConfirmation), role: .destructive) {
                if let schedule = overriding { run { try await executor.overrideAndResume(schedule.id, confirmed: true) } }
                overriding = nil
            }
        } message: { Text(LocalizedStringKey(RecurringExecutor.overrideWarning)) }
    }
    private func scheduleSection(_ schedule: RecurringPayment) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text(schedule.label).font(NoahFont.headline)
                    Spacer()
                    Text(LocalizedStringKey(status(schedule))).font(NoahFont.footnote)
                        .foregroundStyle(schedule.status == "needs_attention" ? NoahColor.destructive : NoahColor.mutedForeground)
                }
                Text(schedule.destination).font(NoahFont.footnote).textSelection(.enabled)
                Text("\(schedule.amountSat.formatted(.number.locale(locale))) sats").font(NoahFont.title)
                Text("\(schedule.interval.description) · \(schedule.occurrencesPaid) paid" + (schedule.maxOccurrences.map { " of \($0)" } ?? ""))
                    .foregroundStyle(NoahColor.mutedForeground)
                if schedule.status == "active", let next = schedule.nextRunAt {
                    Text(L10n.text("Next payment: {_gt_value_2}", Date(timeIntervalSince1970: next / 1000).formatted(), locale: locale.identifier))
                }
                if let end = schedule.endAt { Text(L10n.text("Ends: {_gt_value_2}", Date(timeIntervalSince1970: end / 1000).formatted(date: .abbreviated, time: .omitted), locale: locale.identifier)) }
                if let run = schedule.runs.first {
                    Text(L10n.text("Last attempt: {_gt_value_2} ({_gt_value_3})", Date(timeIntervalSince1970: run.attemptedAt / 1000).formatted(), run.status == "success" ? "Successful" : run.status == "skipped" ? "Skipped" : "Failed", locale: locale.identifier))
                        .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                }
                if let error = schedule.lastError { Text(schedule.issue?.message(locale: locale.identifier) ?? L10n.text(error)).font(NoahFont.footnote).foregroundStyle(NoahColor.destructive) }
            }.accessibilityIdentifier("recurring-payment-\(schedule.id)")
            if schedule.status == "needs_approval" {
                Button("Review and pay manually") { run { review = try await executor.review(schedule.id) } }
                    .disabled(executor.running || executor.controller.locked)
                    .accessibilityIdentifier("recurring-review-\(schedule.id)")
            }
            if !schedule.unsupportedStorage && (schedule.status == "needs_attention" || schedule.inFlight != nil) {
                Button("Check payment status") { Task { await executor.checkStatus(schedule.id) } }
                    .disabled(executor.running)
                if !schedule.requiresPolicyApproval {
                    Button("Resume after checking history…", role: .destructive) { overriding = schedule }.disabled(executor.running)
                }
            }
            if !schedule.unsupportedStorage && schedule.status != "completed" && (schedule.status != "needs_attention" || schedule.requiresPolicyApproval) {
                Button { editingID = schedule.id } label: {
                    HStack {
                        Text("Edit payment")
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                }.buttonStyle(.plain)
                    .accessibilityIdentifier("recurring-edit-\(schedule.id)")
                Button(schedule.status == "active" ? "Pause" : schedule.requiresPolicyApproval ? "Approve and resume" : "Resume") {
                    run { schedule.status == "active" ? try await executor.pause(schedule.id) : try await executor.resume(schedule.id) }
                }.accessibilityIdentifier("recurring-toggle-\(schedule.id)")
            }
            Button(schedule.status == "completed" ? "Remove" : "Cancel", role: .destructive) { cancelling = schedule }
                .accessibilityIdentifier("recurring-cancel-\(schedule.id)")
        }
    }

    private func status(_ schedule: RecurringPayment) -> String {
        if schedule.inFlight != nil && executor.running { return "Sending" }
        return ["active": "Active", "paused": "Paused", "needs_attention": "Needs attention", "needs_approval": "Needs approval", "completed": "Completed"][schedule.status] ?? "Needs attention"
    }
    private func run(_ work: @escaping @MainActor () async throws -> Void) {
        actionError = nil
        Task { do { try await work() } catch { actionError = error.localizedDescription } }
    }
}
