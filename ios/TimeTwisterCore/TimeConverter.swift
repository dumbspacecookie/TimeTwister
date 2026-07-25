//
//  TimeConverter.swift
//  TimeTwisterCore
//
//  Created by dumbspacecookie on 4/21/26.
//

import Foundation

public enum TimeConverter {

    /// Build a stamp string for a detected time, rendered into each of the
    /// target zones the user cares about. The source zone is always included
    /// first so the reader sees "this is what the sender said" with conversions
    /// next to it.
    ///
    /// Example: for "5pm CT" with targets = [ET, PT]
    ///   → "5pm CT (6pm ET · 3pm PT)"
    public static func renderStamp(
        for detected: DetectedTime,
        targets: [TimeZone],
        now: Date = Date()
    ) -> String {
        let date = absoluteDate(for: detected, now: now)

        let sourceLabel = formatted(date, in: detected.timeZone)
        let sourceTag = TimeZoneAlias.shortLabel(for: detected.timeZone)

        let others = targets
            .filter { $0.identifier != detected.timeZone.identifier }
            .map { tz -> String in
                "\(formatted(date, in: tz)) \(TimeZoneAlias.shortLabel(for: tz))"
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
        now: Date = Date()
    ) -> String {
        guard let detected = TimeParser.detectLast(in: input) else { return input }
        let stamp = renderStamp(for: detected, targets: targets, now: now)
        let ns = input as NSString
        return ns.replacingCharacters(in: detected.range, with: stamp)
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

    static func formatted(_ date: Date, in tz: TimeZone) -> String {
        let f = DateFormatter()
        f.timeZone = tz
        f.locale = Locale(identifier: "en_US_POSIX")
        // "5pm" style for whole hours, "5:30pm" otherwise.
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        let minute = cal.component(.minute, from: date)
        f.dateFormat = minute == 0 ? "ha" : "h:mma"
        return f.string(from: date).lowercased()
    }
}
