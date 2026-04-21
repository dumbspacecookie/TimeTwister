//
//  TimeZoneAlias.swift
//  TimeTwisterCore
//
//  Created by dumbspacecookie on 4/20/26.
//

import Foundation

/// Resolves common human-written timezone names to IANA identifiers.
/// We map to IANA (e.g. America/New_York) rather than fixed offsets so
/// daylight saving time is handled correctly by Foundation.
public enum TimeZoneAlias {

    /// Lowercase alias → IANA identifier.
    /// Ordered roughly by expected frequency in US/EU messaging.
    public static let map: [String: String] = [
        // US
        "et": "America/New_York",
        "est": "America/New_York",
        "edt": "America/New_York",
        "eastern": "America/New_York",
        "ct": "America/Chicago",
        "cst": "America/Chicago",
        "cdt": "America/Chicago",
        "central": "America/Chicago",
        "mt": "America/Denver",
        "mst": "America/Denver",
        "mdt": "America/Denver",
        "mountain": "America/Denver",
        "pt": "America/Los_Angeles",
        "pst": "America/Los_Angeles",
        "pdt": "America/Los_Angeles",
        "pacific": "America/Los_Angeles",
        "akst": "America/Anchorage",
        "akdt": "America/Anchorage",
        "alaska": "America/Anchorage",
        "hst": "Pacific/Honolulu",
        "hawaii": "Pacific/Honolulu",

        // Europe
        "utc": "UTC",
        "gmt": "UTC",
        "bst": "Europe/London",
        "london": "Europe/London",
        "cet": "Europe/Paris",
        "cest": "Europe/Paris",
        "eet": "Europe/Helsinki",
        "eest": "Europe/Helsinki",

        // Asia / Pacific
        "ist": "Asia/Kolkata",
        "india": "Asia/Kolkata",
        "jst": "Asia/Tokyo",
        "tokyo": "Asia/Tokyo",
        "kst": "Asia/Seoul",
        "sgt": "Asia/Singapore",
        "singapore": "Asia/Singapore",
        "hkt": "Asia/Hong_Kong",
        "cst_china": "Asia/Shanghai", // intentional disambiguation; "CST" alone stays US Central
        "aest": "Australia/Sydney",
        "aedt": "Australia/Sydney",
        "sydney": "Australia/Sydney",
        "nzst": "Pacific/Auckland",
        "nzdt": "Pacific/Auckland",
    ]

    /// Resolve a user-written TZ token (case-insensitive) to a Foundation `TimeZone`.
    public static func resolve(_ raw: String) -> TimeZone? {
        let key = raw.lowercased().trimmingCharacters(in: .whitespaces)
        if let iana = map[key], let tz = TimeZone(identifier: iana) {
            return tz
        }
        // Fallbacks: try Foundation's own abbreviation and identifier lookups.
        if let tz = TimeZone(abbreviation: raw.uppercased()) { return tz }
        if let tz = TimeZone(identifier: raw) { return tz }
        return nil
    }

    /// A stable short label we render to users (e.g. "ET", "PT", "UTC").
    /// Uses the "first alias that maps to this IANA id" heuristic.
    public static func shortLabel(for tz: TimeZone) -> String {
        let iana = tz.identifier
        switch iana {
        case "America/New_York": return "ET"
        case "America/Chicago": return "CT"
        case "America/Denver": return "MT"
        case "America/Los_Angeles": return "PT"
        case "America/Anchorage": return "AK"
        case "Pacific/Honolulu": return "HI"
        case "UTC": return "UTC"
        case "Europe/London": return "UK"
        case "Europe/Paris": return "CET"
        case "Asia/Kolkata": return "IST"
        case "Asia/Tokyo": return "JST"
        case "Asia/Singapore": return "SGT"
        case "Asia/Hong_Kong": return "HKT"
        case "Asia/Shanghai": return "CN"
        case "Australia/Sydney": return "AEST"
        case "Pacific/Auckland": return "NZ"
        default:
            return tz.abbreviation() ?? iana
        }
    }
}
