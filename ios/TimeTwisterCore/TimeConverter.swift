//
//  TimeConverter.swift
//  TimeTwisterCore
//
//  Created by dumbspacecookie on 4/21/26.
//
//  Mirrors com.timetwister.core.TimeConverter. See TimeParser.swift for why the
//  Kotlin core is the reference implementation.
//

import Foundation

public enum TimeConverter {

    private static let utc = TimeZone(identifier: "UTC") ?? TimeZone(secondsFromGMT: 0)!

    /// Build a stamp string for a detected time, rendered into each of the
    /// target zones the user cares about. The source zone is always included
    /// first so the reader sees "this is what the sender said" with conversions
    /// next to it.
    ///
    /// Example: for "5pm CT" with targets = [ET, PT]
    ///   → "5pm CT (6pm ET · 3pm PT)"
    ///
    /// Conversions that land on a different calendar day than the source carry a
    /// "+1d" / "-1d" marker. Without it "meet at 11pm PT (1am CT · 2am ET)" reads
    /// to an ET recipient as 2am *tonight*, which is the single most consequential
    /// way this tool can be quietly wrong.
    public static func renderStamp(
        for detected: DetectedTime,
        targets: [TimeZone],
        now: Date = Date()
    ) -> String {
        let date = absoluteDate(for: detected, now: now)

        let sourceLabel = formatted(date, in: detected.timeZone)
        let sourceTag = TimeZoneAlias.shortLabel(for: detected.timeZone, at: date)
        let sourceOffset = detected.timeZone.secondsFromGMT(for: date)
        let sourceDay = civilDay(date, in: detected.timeZone)

        var seenOffsets: Set<Int> = []
        var others: [String] = []

        for tz in targets {
            if tz.identifier == detected.timeZone.identifier { continue }
            // Two zones on the same offset at this instant render identically, so a
            // second one adds width and no information ("5pm ET (5pm America/Toronto)").
            let offset = tz.secondsFromGMT(for: date)
            if offset == sourceOffset { continue }
            if !seenOffsets.insert(offset).inserted { continue }

            let label = "\(formatted(date, in: tz)) \(TimeZoneAlias.shortLabel(for: tz, at: date))"
            let dayShift = daysBetween(sourceDay, civilDay(date, in: tz))
            if dayShift > 0 {
                others.append("\(label) +\(dayShift)d")
            } else if dayShift < 0 {
                others.append("\(label) \(dayShift)d")
            } else {
                others.append(label)
            }
        }

        if others.isEmpty {
            return "\(sourceLabel) \(sourceTag)"
        }
        return "\(sourceLabel) \(sourceTag) (\(others.joined(separator: " · ")))"
    }

    /// Splice a rendered stamp back into the original text in place of the detected
    /// range. Pure helper — used by both the keyboard extension (when the user taps
    /// a suggestion) and the Action Extension (which gets the full selection from
    /// the share sheet). If no time is detected the input is returned unchanged.
    public static func splice(
        input: String,
        targets: [TimeZone],
        now: Date = Date(),
        defaultZone: TimeZone = .current
    ) -> String {
        guard let detected = TimeParser.detectLast(in: input, defaultZone: defaultZone) else {
            return input
        }
        if isUnrepresentable(detected, now: now) { return input }

        let stamp = renderStamp(for: detected, targets: targets, now: now)
        let ns = input as NSString
        let end = detected.range.location + detected.range.length

        // If our own stamp already follows this time, replace it rather than nesting
        // a second one inside it. This is what makes a second pass a no-op, and it
        // also means re-running after a settings change refreshes the conversions.
        let trailing = TimeParser.trailingStampRange(in: input, from: end)
        let replaceEnd = trailing.map { $0.location + $0.length } ?? end

        return ns.replacingCharacters(
            in: NSRange(location: detected.range.location,
                        length: replaceEnd - detected.range.location),
            with: stamp
        )
    }

    /// Decide what to return to a host that handed us an editable selection:
    ///   - nil  → host should treat as "no change"
    ///   - else → the new text to splice in
    ///
    /// Read-only selections and no-detection cases both produce nil so the host
    /// doesn't garble unmodifiable text. Keeping the decision here rather than in
    /// the extension's view controller is what makes it testable at all — the
    /// controller becomes a thin shell that adapts an `NSExtensionContext` to this
    /// call.
    ///
    /// Hosts that want to *show* the conversion for a read-only selection (rather
    /// than silently doing nothing) should call `splice` directly — this function
    /// only answers "may I rewrite the buffer".
    public static func maybeSplice(
        input: String,
        targets: [TimeZone],
        readOnly: Bool,
        now: Date = Date(),
        defaultZone: TimeZone = .current
    ) -> String? {
        if readOnly { return nil }
        guard let detected = TimeParser.detectLast(in: input, defaultZone: defaultZone) else {
            return nil
        }
        if isUnrepresentable(detected, now: now) { return nil }
        let spliced = splice(input: input, targets: targets, now: now, defaultZone: defaultZone)
        // Already-stamped text splices back to itself; report "no change" so the
        // host can skip the edit entirely.
        return spliced == input ? nil : spliced
    }

    /// Produce the actual UTC instant the user meant. We assume "today" in the
    /// source zone — if that instant is already in the past, roll forward one day
    /// so suggestions stay useful in evening chats.
    static func absoluteDate(for detected: DetectedTime, now: Date) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = detected.timeZone

        let today = cal.dateComponents([.year, .month, .day], from: now)
        var comps = DateComponents()
        comps.year = today.year
        comps.month = today.month
        comps.day = today.day
        comps.hour = detected.hour
        comps.minute = detected.minute
        comps.timeZone = detected.timeZone

        guard let candidate = cal.date(from: comps) else { return now }
        if candidate < now.addingTimeInterval(-60 * 30) {
            return cal.date(byAdding: .day, value: 1, to: candidate) ?? candidate
        }
        return candidate
    }

    /// True when the wall-clock time the user wrote does not exist in their zone on
    /// the day we resolved it — the one-hour hole a spring-forward DST transition
    /// punches out of the local calendar.
    ///
    /// `Calendar.date(from:)` does not fail there; it silently hands back an
    /// instant *after* the gap. That turned "deploy at 2:30am ET" into "deploy at
    /// 3:30am ET (…)" — rewriting the user's own words to a time they did not
    /// type. We would rather leave the text untouched and let a human sort it out.
    static func isUnrepresentable(_ detected: DetectedTime, now: Date) -> Bool {
        let resolved = absoluteDate(for: detected, now: now)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = detected.timeZone
        let c = cal.dateComponents([.hour, .minute], from: resolved)
        return c.hour != detected.hour || c.minute != detected.minute
    }

    /// "5pm" for whole hours, "5:30pm" otherwise.
    ///
    /// Rendered by hand from calendar components rather than through
    /// `DateFormatter`. Three reasons, in ascending order of importance:
    ///
    /// 1. A 12-hour clock with an `am`/`pm` suffix is arithmetic, not
    ///    localisation — a formatter is a heavyweight dependency for a modulo.
    /// 2. `DateFormatter` output is locale-sensitive by default, and the whole
    ///    class of "works for me, renders `17:00` / `午後5時` / an RTL marker for
    ///    the user" bugs disappears when the pattern can't be reinterpreted.
    ///    Kotlin pins `Locale.US` for exactly this reason; here there is nothing
    ///    left to pin.
    /// 3. `DateFormatter.string(from:)` **traps** (illegal instruction,
    ///    0xC000001D) in swift-corelibs-Foundation on Windows, with or without an
    ///    explicit locale. That is a corelibs limitation and not an iOS bug —
    ///    Apple's Foundation is a separate implementation — but it was the single
    ///    thing keeping this core from being testable anywhere except a Mac.
    ///    Without it, `swift test` runs on Windows and on a Linux CI runner.
    static func formatted(_ date: Date, in tz: TimeZone) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        let comps = cal.dateComponents([.hour, .minute], from: date)
        let hour24 = comps.hour ?? 0
        let minute = comps.minute ?? 0

        // 0 → 12am, 12 → 12pm; every other hour is hour % 12.
        let hour12 = (hour24 % 12 == 0) ? 12 : hour24 % 12
        let suffix = hour24 < 12 ? "am" : "pm"

        if minute == 0 { return "\(hour12)\(suffix)" }
        let mm = minute < 10 ? "0\(minute)" : "\(minute)"
        return "\(hour12):\(mm)\(suffix)"
    }

    /// The calendar day `date` falls on in `tz`, re-anchored to noon UTC.
    ///
    /// Anchoring is what makes two of these subtractable: comparing the raw
    /// instants would just re-measure the zone offset, and comparing local
    /// midnights is not a fixed distance apart on a DST boundary. Pinned at noon
    /// so the pair can never drift across a day line under any offset.
    private static func civilDay(_ date: Date, in tz: TimeZone) -> Date {
        var local = Calendar(identifier: .gregorian)
        local.timeZone = tz
        let c = local.dateComponents([.year, .month, .day], from: date)

        var anchored = DateComponents()
        anchored.year = c.year
        anchored.month = c.month
        anchored.day = c.day
        anchored.hour = 12
        anchored.timeZone = utc

        var utcCal = Calendar(identifier: .gregorian)
        utcCal.timeZone = utc
        return utcCal.date(from: anchored) ?? date
    }

    private static func daysBetween(_ from: Date, _ to: Date) -> Int {
        Int((to.timeIntervalSince(from) / 86_400).rounded())
    }
}
