import SwiftUI

struct RecurringPaymentEditorView: View {
    let executor: RecurringExecutor
    let original: RecurringPayment?
    @Environment(AppModel.self) private var app
    @Environment(\.locale) private var locale
    @Environment(\.settings) private var settings
    @Environment(\.settingsPage) private var currentPage
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
    @State private var editingRecipient = false

    init(executor: RecurringExecutor, schedule: RecurringPayment? = nil) {
        self.executor = executor; original = schedule
        _label = State(initialValue: schedule?.label ?? "")
        _recipient = State(initialValue: schedule?.destination ?? "")
        _amount = State(initialValue: schedule.map { String($0.amountSat) } ?? "")
        _comment = State(initialValue: schedule?.comment ?? "")
        _frequency = State(initialValue: schedule?.interval.unit ?? "month")
        _periodEvery = State(initialValue: schedule?.interval.unit == "day" ? 1 : (schedule?.interval.every ?? 1))
        _customDays = State(initialValue: schedule.map { String($0.interval.every) } ?? "14")
        let nextDay = Calendar.current.date(byAdding: .day, value: 1, to: Date()) ?? Date(timeIntervalSinceNow: 86_400)
        let tomorrow = Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: nextDay) ?? nextDay
        _start = State(initialValue: schedule.map { Date(timeIntervalSince1970: $0.startAt / 1000) } ?? tomorrow)
        _endMode = State(initialValue: schedule?.endAt != nil ? "date" : schedule?.maxOccurrences != nil ? "count" : "never")
        _endDate = State(initialValue: schedule?.endAt.map { Date(timeIntervalSince1970: $0 / 1000) } ?? tomorrow)
        _count = State(initialValue: schedule?.maxOccurrences.map(String.init) ?? "12")
    }
    nonisolated static func limitedText(_ value: String, limit: Int) -> String {
        var length = 0
        return String(value.prefix { character in
            length += String(character).utf16.count
            return length <= limit
        })
    }
    private var interval: RecurringInterval { .init(unit: frequency, every: frequency == "day" ? (Int(customDays) ?? 0) : periodEvery) }
    private var destination: (destination: String, type: String)? { try? RecurringRecipient.resolve(recipient) }
    var body: some View {
        SettingsScroll(line: L10n.text(
            // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
            "Noah sends this payment automatically from your Ark balance. Only this recipient and amount are ever allowed, and you can pause or cancel at any time.",
            locale: language)) {
                if original != nil {
                    SettingsFine(text: L10n.text("Saving starts a new schedule at the first payment date. Previous attempts remain visible.", locale: language))
                }
                amountField.padding(.top, 14)
                recipientFields
                repeatsFields
                datesFields
                previewSection
                if let error { SettingsFine(text: L10n.text(error, locale: language), failure: true) }
            }
            .settingsChrome(original == nil ? "New payment" : "Edit payment")
            .settingsFooter {
                Button(action: { focus = nil; save() }) {
                    HStack(spacing: 8) {
                        if saving { ProgressView().tint(NoahTokens.Colors.background) }
                        Text(L10n.text(original == nil ? "Schedule payment" : "Save", locale: language))
                    }
                }
                .buttonStyle(SettingsActionStyle())
                .disabled(saving)
                .accessibilityIdentifier("recurring-save-button")
            }
    }

    private var language: String { locale.identifier }

    @ViewBuilder private var recipientFields: some View {
        SettingsField(label: L10n.text("To", locale: language), placeholder: L10n.text("Name, address or offer", locale: language),
                      text: $recipient, failure: recipientError != nil, tag: recipientTag, id: "recurring-recipient-input",
                      focusChanged: { editingRecipient = $0 })
        if let recipientError { SettingsFine(text: recipientError, failure: true).padding(.top, -8) }
        SettingsField(label: L10n.text("Name", locale: language), placeholder: L10n.text("e.g. Rent", locale: language), text: $label,
                      id: "recurring-label-input")
            .onChange(of: label) { _, value in label = Self.limitedText(value, limit: 64) }
        if destination?.type == "lnurl" {
            SettingsField(label: L10n.text("Note (optional)", locale: language), placeholder: L10n.text("Shared with the recipient", locale: language),
                          text: $comment)
                .onChange(of: comment) { _, value in comment = Self.limitedText(value, limit: 140) }
        }
    }

    @ViewBuilder private var repeatsFields: some View {
        label(L10n.text("Repeats", locale: language))
        Picker(L10n.text("Repeats", locale: language), selection: $frequency) {
            Text(L10n.text("Weekly", locale: language)).tag("week")
            Text(L10n.text("Monthly", locale: language)).tag("month")
            Text(L10n.text("Custom", locale: language)).tag("day")
        }
        .pickerStyle(.segmented)
        .padding(.top, 8)
        if frequency == "day" {
            SettingsField(placeholder: L10n.text("Every … days", locale: language), text: $customDays, keyboard: .numberPad,
                          tag: L10n.text("days", locale: language))
                .padding(.top, -10)
        } else {
            stepper(periodEvery == 1 ? L10n.text(frequency == "week" ? "Every week" : "Every month", locale: language)
                : frequency == "week" ? L10n.plural("Every {n} weeks", count: periodEvery, locale: language)
                : L10n.plural("Every {n} months", count: periodEvery, locale: language),
                value: $periodEvery, range: 1...12)
        }
    }

    @ViewBuilder private var datesFields: some View {
        label(L10n.text("First payment", locale: language))
        DatePicker(L10n.text("First payment", locale: language), selection: $start)
            .labelsHidden()
            .padding(.top, 8)
        label(L10n.text("Ends", locale: language))
        Picker(L10n.text("Ends", locale: language), selection: $endMode) {
            Text(L10n.text("Never", locale: language)).tag("never")
            Text(L10n.text("On a date", locale: language)).tag("date")
            Text(L10n.text("After", locale: language)).tag("count")
        }
        .pickerStyle(.segmented)
        .padding(.top, 8)
        if endMode == "date" {
            DatePicker(L10n.text("End date", locale: language), selection: $endDate, displayedComponents: .date)
                .labelsHidden()
                .padding(.top, 10)
        }
        if endMode == "count" {
            SettingsField(placeholder: L10n.text("Number of payments", locale: language), text: $count, keyboard: .numberPad,
                          tag: L10n.text("payments", locale: language))
                .padding(.top, -10)
        }
    }

    @ViewBuilder private var previewSection: some View {
        if interval.valid {
            label(interval.text(locale: language))
            WrapLayout(spacing: NoahTokens.Space.s, lineSpacing: NoahTokens.Space.s) {
                ForEach(preview, id: \.self) { next in
                    Text(verbatim: Date(timeIntervalSince1970: next / 1000)
                        .formatted(.dateTime.month(.abbreviated).day().hour().minute().locale(locale)))
                        .noahText(NoahTokens.Typography.control)
                        .padding(.horizontal, 14).frame(minHeight: NoahTokens.Size.chip)
                        .overlay { Capsule().strokeBorder(NoahTokens.Colors.hairline, lineWidth: 1) }
                }
            }
            .padding(.top, 8)
            SettingsFine(text: L10n.text("A reminder comes the day before. A missed payment is skipped, never paid twice.", locale: language))
        }
    }

    /// The big amount, in sats each time.
    private var amountField: some View {
        // swiftlint:disable:next accessibility_trait_for_button - the tap only focuses the field; the TextField is the accessible control
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            TextField(text: $amount, prompt: Text(verbatim: "0").foregroundStyle(NoahTokens.Colors.muted)) { Text("Amount in sats") }
                .noahText(SettingsText.big)
                .keyboardType(.numberPad)
                .tint(NoahTokens.Colors.orange)
                .focused($focus, equals: .amount)
                .fixedSize()
                .accessibilityIdentifier("recurring-amount-input")
            Text(L10n.text("sats each time", locale: language)).noahText(NoahTokens.Typography.label).foregroundStyle(NoahTokens.Colors.muted)
            Spacer(minLength: 0)
        }
        .padding(.bottom, 10)
        .overlay(alignment: .bottom) { OnboardingUnderline(focused: focus == .amount) }
        .contentShape(Rectangle())
        .onTapGesture { focus = .amount }
    }

    private var recipientTag: String? {
        destination.map { L10n.text($0.type == "ark" ? "Ark address" : $0.type == "lnurl" ? "Lightning address" : "BOLT12 offer", locale: language) }
    }

    /// Today's resolution errors, inline once you've left the field.
    private var recipientError: String? {
        guard !editingRecipient, !recipient.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        do { _ = try RecurringRecipient.resolve(recipient); return nil } catch { return error.localizedDescription }
    }

    private func label(_ text: String) -> some View {
        Text(verbatim: text).noahText(HomeText.section).foregroundStyle(NoahTokens.Colors.muted).padding(.top, 20)
    }

    private func stepper(_ title: String, value: Binding<Int>, range: ClosedRange<Int>) -> some View {
        HStack {
            Text(verbatim: title).noahText(SettingsText.value)
            Spacer()
            HStack(spacing: 8) {
                Button { value.wrappedValue = max(range.lowerBound, value.wrappedValue - 1) } label: { Image(systemName: "minus").frame(width: 40, height: 36) }
                    .disabled(value.wrappedValue <= range.lowerBound)
                    .accessibilityLabel(Text("Fewer"))
                Button { value.wrappedValue = min(range.upperBound, value.wrappedValue + 1) } label: { Image(systemName: "plus").frame(width: 40, height: 36) }
                    .disabled(value.wrappedValue >= range.upperBound)
                    .accessibilityLabel(Text("More"))
            }
            .buttonStyle(.plain)
            .font(.system(size: 17, weight: .medium))
            .background(NoahTokens.Colors.soft, in: Capsule())
        }
        .frame(height: 48)
        .padding(.top, 10)
    }

    private var preview: [Double] {
        let schedule = draft(destination: destination?.destination ?? "", type: destination?.type ?? "ark")
        return (0..<3).compactMap { schedule.next($0) }
    }
    private func draft(destination: String, type: String) -> RecurringPayment {
        let now = RecurringPayment.now
        return .init(
            id: original?.id ?? UUID().uuidString,
            label: label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? String(destination.prefix(32)) : label
                .trimmingCharacters(in: .whitespacesAndNewlines),
            destination: destination,
            destinationType: type,
            amountSat: amount.trimmingCharacters(in: .whitespacesAndNewlines)
                .range(of: "^[0-9]+$", options: .regularExpression) != nil ? (UInt64(amount.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0) : 0,
            comment: type == "lnurl" ? String(comment.prefix(140)).trimmingCharacters(in: .whitespacesAndNewlines) : "",
            interval: interval,
            startAt: start.timeIntervalSince1970 * 1000,
            endAt: endMode == "date" ? Self.endDateTimestamp(endDate) : nil,
            maxOccurrences: endMode == "count" ? (Int(count) ?? 0) : nil,
            nextRunAt: start.timeIntervalSince1970 * 1000,
            createdAt: original?.createdAt ?? now,
            updatedAt: now)
    }
    static func endDateTimestamp(_ date: Date, zone: TimeZone = .current) -> Double {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        // RN parses the chosen end date with "23:59", seconds and milliseconds both zero.
        guard let end = calendar.date(bySettingHour: 23, minute: 59, second: 0, of: date) else { preconditionFailure("every Gregorian day has 23:59") }
        return end.timeIntervalSince1970 * 1000
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
                settings.toast(original == nil ? "Scheduled" : "Saved")
                settings.leave(currentPage)
            } catch { self.error = error.localizedDescription }
        }
    }
}
