import SwiftUI

struct RecurringPaymentEditorView: View {
    let executor: RecurringExecutor
    let original: RecurringPayment?
    @Environment(\.dismiss) private var dismiss
    private enum Field: Hashable { case label, recipient, amount, comment, days, count }
    @FocusState private var focus: Field?
    @State private var label: String
    @State private var recipient: String
    @State private var amount: String
    @State private var comment: String
    @State private var frequency: String
    @State private var customDays: String
    @State private var periodEvery: Int
    @State private var start: Date
    @State private var endMode: String
    @State private var endDate: Date
    @State private var count: String
    @State private var error: String?
    @State private var saving = false

    init(executor: RecurringExecutor, schedule: RecurringPayment? = nil) {
        self.executor = executor; original = schedule
        _label = State(initialValue: schedule?.label ?? "")
        _recipient = State(initialValue: schedule?.destination ?? "")
        _amount = State(initialValue: schedule.map { String($0.amountSat) } ?? "")
        _comment = State(initialValue: schedule?.comment ?? "")
        _frequency = State(initialValue: schedule?.interval.unit ?? "month")
        _periodEvery = State(initialValue: schedule?.interval.unit == "day" ? 1 : (schedule?.interval.every ?? 1))
        _customDays = State(initialValue: schedule.map { String($0.interval.every) } ?? "14")
        let tomorrow = Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: Calendar.current.date(byAdding: .day, value: 1, to: Date())!)!
        _start = State(initialValue: schedule.map { Date(timeIntervalSince1970: $0.startAt / 1000) } ?? tomorrow)
        _endMode = State(initialValue: schedule?.endAt != nil ? "date" : schedule?.maxOccurrences != nil ? "count" : "never")
        _endDate = State(initialValue: schedule?.endAt.map { Date(timeIntervalSince1970: $0 / 1000) } ?? tomorrow)
        _count = State(initialValue: schedule?.maxOccurrences.map(String.init) ?? "12")
    }
    private var interval: RecurringInterval { .init(unit: frequency, every: frequency == "day" ? (Int(customDays) ?? 0) : periodEvery) }
    private var destination: (destination: String, type: String)? { try? RecurringRecipient.resolve(recipient) }
    var body: some View {
        Form {
            Section {
                Text("Noah sends this payment automatically from your Ark balance. Only this recipient and amount are ever allowed, and you can pause or cancel at any time.")
                    .foregroundStyle(NoahColor.mutedForeground)
                if original != nil { Text("Saving starts a new schedule at the first payment date. Previous attempts remain visible.").font(NoahFont.footnote) }
            }
            Section("Name") { TextField("e.g. Rent, Donation, VPN", text: $label).focused($focus, equals: .label).accessibilityIdentifier("recurring-label-input") }
            Section("Recipient") {
                TextField("name@domain.com, ark1… or lno1…", text: $recipient)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().focused($focus, equals: .recipient)
                    .accessibilityIdentifier("recurring-recipient-input")
                Text(destination.map { $0.type == "ark" ? "Ark address" : $0.type == "lnurl" ? "Lightning address" : "BOLT12 offer" } ?? "Ark address, Lightning address or BOLT12 offer")
                    .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
            }
            Section("Amount (sats)") {
                TextField("Amount in sats", text: $amount).keyboardType(.numberPad).focused($focus, equals: .amount)
                    .accessibilityIdentifier("recurring-amount-input")
                Text("\(amount.isEmpty ? "0" : amount) sats per payment, plus network fees").font(NoahFont.footnote)
            }
            if destination?.type == "lnurl" {
                Section("Note (optional)") {
                    TextField("Shared with the recipient", text: $comment).focused($focus, equals: .comment)
                }
            }
            Section("Repeat") {
                Picker("Repeat", selection: $frequency) { Text("Weekly").tag("week"); Text("Monthly").tag("month"); Text("Custom").tag("day") }.pickerStyle(.segmented)
                if frequency == "day" { TextField("Every … days", text: $customDays).keyboardType(.numberPad).focused($focus, equals: .days) }
                else {
                    Picker(frequency == "week" ? "Every … weeks" : "Every … months", selection: $periodEvery) {
                        ForEach(1...12, id: \.self) { Text("\($0)").tag($0) }
                    }
                }
            }
            Section("First payment") { DatePicker("First payment", selection: $start) }
            Section("Ends") {
                Picker("Ends", selection: $endMode) { Text("Never").tag("never"); Text("On date").tag("date"); Text("After").tag("count") }.pickerStyle(.segmented)
                if endMode == "date" { DatePicker("End date", selection: $endDate, displayedComponents: .date) }
                if endMode == "count" { TextField("Number of payments", text: $count).keyboardType(.numberPad).focused($focus, equals: .count) }
            }
            if interval.valid {
                Section(interval.description) {
                    ForEach(preview, id: \.self) { Text(Date(timeIntervalSince1970: $0 / 1000).formatted()) }
                    Text("You'll get a reminder the day before and a notification after each payment. If a payment is missed while your phone is off, Noah skips it instead of paying twice.")
                        .font(NoahFont.footnote).foregroundStyle(NoahColor.mutedForeground)
                }
            }
            if let error { Section { Text(error).foregroundStyle(NoahColor.destructive) } }
        }
        .settingsStyle(original == nil ? "New recurring payment" : "Edit recurring payment")
        .scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { focus = nil } } }
        .safeAreaInset(edge: .bottom) {
            if focus == nil {
                NoahButton(title: original == nil ? "Schedule payment" : "Save payment", isLoading: saving) { save() }.padding()
                    .accessibilityIdentifier("recurring-save-button")
            }
        }
    }
    private var preview: [Double] {
        let schedule = draft(destination: destination?.destination ?? "", type: destination?.type ?? "ark")
        return (0..<3).compactMap { schedule.next($0) }
    }
    private func draft(destination: String, type: String) -> RecurringPayment {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = .current
        let end = calendar.date(bySettingHour: 23, minute: 59, second: 0, of: endDate)!
        let now = RecurringPayment.now
        return .init(id: original?.id ?? UUID().uuidString, label: label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? String(destination.prefix(32)) : label.trimmingCharacters(in: .whitespacesAndNewlines), destination: destination, destinationType: type, amountSat: amount.trimmingCharacters(in: .whitespacesAndNewlines).range(of: "^[0-9]+$", options: .regularExpression) != nil ? (UInt64(amount.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0) : 0, comment: type == "lnurl" ? String(comment.prefix(140)).trimmingCharacters(in: .whitespacesAndNewlines) : "", interval: interval, startAt: start.timeIntervalSince1970 * 1000, endAt: endMode == "date" ? end.timeIntervalSince1970 * 1000 : nil, maxOccurrences: endMode == "count" ? (Int(count) ?? 0) : nil, nextRunAt: start.timeIntervalSince1970 * 1000, createdAt: original?.createdAt ?? now, updatedAt: now)
    }
    private func save() {
        error = nil; saving = true
        Task {
            defer { saving = false }
            do {
                let recipient = try RecurringRecipient.resolve(recipient)
                var schedule = draft(destination: recipient.destination, type: recipient.type)
                // Editing grants a new policy from its chosen first payment; retain visible history.
                if let original { schedule.runs = original.runs }
                try await executor.save(schedule, editing: original != nil)
                dismiss()
            } catch { self.error = error.localizedDescription }
        }
    }
}
