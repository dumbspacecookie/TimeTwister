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

    // Either a numeric time (hh[:mm][am/pm]) or the keywords noon/midnight,
    // optionally followed by a TZ token. Word boundaries avoid matching inside
    // identifiers. The numeric branch keeps `\s*` *inside* the optional ampm group
    // so it doesn't eat the separator before the TZ when no am/pm is present.
    private static let pattern = #"""
    (?xi)
    \b
    (?:
        (?<keyword>noon|midnight)
      |
        (?<hour>\d{1,2})
        (?: : (?<minute>\d{2}) )?
        (?: \s* (?<ampm>am|pm|a\.m\.|p\.m\.) )?
    )
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
            let keywordRange = m.range(withName: "keyword")
            let hourRange = m.range(withName: "hour")
            let minuteRange = m.range(withName: "minute")
            let ampmRange = m.range(withName: "ampm")
            let tzRange = m.range(withName: "tz")

            let hasTZ = tzRange.location != NSNotFound
            let zone: TimeZone
            if hasTZ {
                let token = ns.substring(with: tzRange)
                zone = TimeZoneAlias.resolve(token) ?? defaultTZ
            } else {
                zone = defaultTZ
            }

            let hour24: Int
            let minuteRaw: Int

            if keywordRange.location != NSNotFound {
                let keyword = ns.substring(with: keywordRange).lowercased()
                hour24 = (keyword == "noon") ? 12 : 0
                minuteRaw = 0
            } else {
                guard hourRange.location != NSNotFound,
                      let hourRaw = Int(ns.substring(with: hourRange)) else { continue }

                let hasAMPM = ampmRange.location != NSNotFound

                // Require a disambiguator to avoid false positives.
                guard hasAMPM || hasTZ else { continue }

                minuteRaw = minuteRange.location != NSNotFound
                    ? (Int(ns.substring(with: minuteRange)) ?? 0)
                    : 0

                var h = hourRaw
                if hasAMPM {
                    let ampm = ns.substring(with: ampmRange).lowercased().replacingOccurrences(of: ".", with: "")
                    if ampm == "pm" && h < 12 { h += 12 }
                    if ampm == "am" && h == 12 { h = 0 }
                    guard hourRaw >= 1 && hourRaw <= 12 else { continue }
                } else {
                    guard h >= 0 && h <= 23 else { continue }
                }
                guard minuteRaw >= 0 && minuteRaw <= 59 else { continue }
                hour24 = h
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
