//
//  TimeParser.swift
//  TimeTwisterCore
//
//  Created by dumbspacecookie on 4/21/26.
//

import Foundation

/// A time reference we detected in user-typed text.
public struct DetectedTime: Equatable {
    public let hour: Int          // 0...23
    public let minute: Int        // 0...59
    public let timeZone: TimeZone // if none was written, defaults to user's current TZ
    public let hadExplicitZone: Bool
    public let range: NSRange     // range in the original NSString
    public let originalText: String
}

/// Parses human-written time expressions like "5pm CT", "17:00 ET", "5:30pm pacific".
/// Deliberately conservative: we require either am/pm OR an explicit TZ token,
/// otherwise "room 5" or "5 apples" would match and the suggestion bar would
/// light up constantly.
public enum TimeParser {

    // (hh)(:mm)? (am|pm)? (tz)?
    // - hour 1..12 if am/pm present, 0..23 if not
    // - TZ is a 2-6 letter token or "pacific"/"eastern"/... (up to 10 letters)
    //
    // We use word boundaries to avoid matching inside identifiers.
    private static let pattern = #"""
    (?xi)
    \b
    (?<hour>\d{1,2})
    (?: : (?<minute>\d{2}) )?
    \s*
    (?<ampm>am|pm|a\.m\.|p\.m\.)?
    (?:
        \s+
        (?<tz>
            et|est|edt|ct|cst|cdt|mt|mst|mdt|pt|pst|pdt|akst|akdt|hst
          | utc|gmt|bst|cet|cest|eet|eest
          | ist|jst|kst|sgt|hkt|aest|aedt|nzst|nzdt
          | eastern|central|mountain|pacific|alaska|hawaii
          | london|tokyo|singapore|sydney|india
        )
    )?
    \b
    """#

    public static func detect(in text: String) -> [DetectedTime] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: []) else {
            return []
        }
        let ns = text as NSString
        let matches = regex.matches(in: text, options: [], range: NSRange(location: 0, length: ns.length))
        let defaultTZ = TimeZone.current

        var out: [DetectedTime] = []
        for m in matches {
            let hourRange = m.range(withName: "hour")
            let minuteRange = m.range(withName: "minute")
            let ampmRange = m.range(withName: "ampm")
            let tzRange = m.range(withName: "tz")

            guard hourRange.location != NSNotFound,
                  let hourRaw = Int(ns.substring(with: hourRange)) else { continue }

            let hasAMPM = ampmRange.location != NSNotFound
            let hasTZ = tzRange.location != NSNotFound

            // Require a disambiguator to avoid false positives.
            guard hasAMPM || hasTZ else { continue }

            let minuteRaw: Int = minuteRange.location != NSNotFound
                ? (Int(ns.substring(with: minuteRange)) ?? 0)
                : 0

            // Normalize to 24-hour.
            var hour24 = hourRaw
            if hasAMPM {
                let ampm = ns.substring(with: ampmRange).lowercased().replacingOccurrences(of: ".", with: "")
                if ampm == "pm" && hour24 < 12 { hour24 += 12 }
                if ampm == "am" && hour24 == 12 { hour24 = 0 }
                guard hourRaw >= 1 && hourRaw <= 12 else { continue }
            } else {
                guard hour24 >= 0 && hour24 <= 23 else { continue }
            }
            guard minuteRaw >= 0 && minuteRaw <= 59 else { continue }

            let zone: TimeZone
            if hasTZ {
                let token = ns.substring(with: tzRange)
                zone = TimeZoneAlias.resolve(token) ?? defaultTZ
            } else {
                zone = defaultTZ
            }

            out.append(DetectedTime(
                hour: hour24,
                minute: minuteRaw,
                timeZone: zone,
                hadExplicitZone: hasTZ,
                range: m.range,
                originalText: ns.substring(with: m.range)
            ))
        }
        return out
    }

    /// Returns only the last detected time (most useful while the user is typing).
    public static func detectLast(in text: String) -> DetectedTime? {
        detect(in: text).last
    }
}
