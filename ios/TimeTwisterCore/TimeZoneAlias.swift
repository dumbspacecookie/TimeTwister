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
        // "uk" and "cn" are here because we RENDER them as labels. Every label we
        // emit has to parse back to the zone it came from, or a second pass reads
        // its own output as an unzoned time and stamps it again.
        "uk": "Europe/London",
        "cet": "Europe/Paris",
        "cest": "Europe/Paris",
        "central european": "Europe/Paris",
        "eet": "Europe/Helsinki",
        "eest": "Europe/Helsinki",
        "eastern european": "Europe/Helsinki",

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
        "cn": "Asia/Shanghai",
        "aest": "Australia/Sydney",
        "aedt": "Australia/Sydney",
        "sydney": "Australia/Sydney",
        "nzst": "Pacific/Auckland",
        "nzdt": "Pacific/Auckland",
    ]

    /// Labels that are correct all year because they name a *region*, not an
    /// offset. "ET" is right whether New York is on EST or EDT — which is exactly
    /// why the US entries are hardcoded rather than derived.
    ///
    /// Zones whose conventional abbreviation is DST-specific are deliberately
    /// absent (Europe/Paris, Europe/Helsinki, Australia/Sydney): a fixed "CET" or
    /// "AEST" label contradicts the actual offset for half the year, and a reader
    /// who trusts the abbreviation and re-derives the time lands an hour off.
    /// Those are resolved against the instant instead, in `shortLabel`.
    ///
    /// Both "UTC" and "GMT" appear as keys on purpose. `TimeZone(identifier:)`
    /// canonicalises one to the other, and *which way* it goes differs between
    /// Apple's Foundation and swift-corelibs. Keying on both makes the label
    /// stable regardless of which name the platform hands back.
    private static let regionLabels: [String: String] = [
        "America/New_York": "ET",
        "America/Chicago": "CT",
        "America/Denver": "MT",
        "America/Los_Angeles": "PT",
        "Pacific/Honolulu": "HST", // no DST here, so the abbreviation is safe year-round
        "UTC": "UTC",
        "GMT": "UTC",
        "Europe/London": "UK",
        "Asia/Kolkata": "IST",
        "Asia/Tokyo": "JST",
        "Asia/Seoul": "KST",
        "Asia/Singapore": "SGT",
        "Asia/Hong_Kong": "HKT",
        "Asia/Shanghai": "CN",
        // America/Anchorage and Pacific/Auckland are resolved against the instant
        // (AKST/AKDT, NZST/NZDT) rather than pinned: both observe DST, and both
        // abbreviations are already alias tokens, so the output stays re-readable.
    ]

    /// Standard/daylight abbreviations for the DST-observing zones this app
    /// supports, so the label never has to be looked up in the platform's CLDR
    /// tables.
    ///
    /// That lookup is not portable. Apple's Foundation answers
    /// `abbreviation(for:)` with "CEST"; swift-corelibs on Windows has no
    /// abbreviation for these zones at all and returns an offset-shaped stand-in,
    /// so the same input rendered "3pm CEST" on a phone and "3pm UTC+2" on a CI
    /// runner. Thirteen corpus rows disagreed across platforms for that reason
    /// alone.
    ///
    /// Every spelling here is already an alias token in `map`, because these are
    /// the abbreviations users write — which is also what keeps our own output
    /// re-readable on a second pass. We know both halves of each pair already;
    /// asking the platform for them bought nothing and cost portability.
    ///
    /// Zones in `regionLabels` are deliberately not here: their label is
    /// DST-stable by design and must not flip.
    private static let dstAbbreviations: [String: (standard: String, daylight: String)] = [
        "Europe/Paris": ("CET", "CEST"),
        "Europe/Helsinki": ("EET", "EEST"),
        "Australia/Sydney": ("AEST", "AEDT"),
        "Pacific/Auckland": ("NZST", "NZDT"),
        "America/Anchorage": ("AKST", "AKDT"),
    ]

    /// An explicit UTC offset, written the way we render one: "UTC+2", "GMT-5",
    /// "UTC+5:30", "utc+0530".
    ///
    /// This exists because `offsetLabel` *emits* this shape for every zone outside
    /// the tables above, and the parser has to be able to read back everything we
    /// emit. Before it did, "3pm UTC+2" parsed as the zone `UTC` with a stray "+2"
    /// left dangling outside the stamp, and any second pass over our own output for
    /// one of the ~400 unlisted zones nested a fresh stamp inside the last one.
    ///
    /// It is also simply what people write. Everyone outside the handful of regions
    /// with a famous abbreviation says "UTC+2", and until now that was a guaranteed
    /// garble on the first pass.
    private static let offsetToken = try! NSRegularExpression(
        pattern: #"^(?:utc|gmt)([+-])([0-9]{1,2})(?::?([0-9]{2}))?$"#
    )

    /// Lowercase, trim, and collapse any run of whitespace to a single space, so
    /// multi-word aliases match however the writer spaced them. The pattern
    /// accepts a separator between the words, and "central  european" (or one split
    /// across a line break) has to land on the same key as "central european".
    private static func normalise(_ raw: String) -> String {
        raw.lowercased()
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }

    /// Parse "utc+5:30" into a fixed-offset zone, or nil if it isn't that shape.
    private static func resolveOffset(_ key: String) -> TimeZone? {
        let ns = key as NSString
        guard let m = offsetToken.firstMatch(
            in: key, range: NSRange(location: 0, length: ns.length)
        ) else { return nil }

        func part(_ i: Int) -> String? {
            let r = m.range(at: i)
            return r.location == NSNotFound ? nil : ns.substring(with: r)
        }
        guard let sign = part(1), let hours = part(2).flatMap(Int.init) else { return nil }
        let minutes = part(3).flatMap(Int.init) ?? 0
        guard hours <= 18, minutes <= 59 else { return nil }

        let magnitude = hours * 3600 + minutes * 60
        return TimeZone(secondsFromGMT: sign == "-" ? -magnitude : magnitude)
    }

    /// True when `raw` is a zone token this app actually understands.
    ///
    /// Distinct from `resolve`, which also accepts anything Foundation
    /// recognises — including legacy identifiers like "NZ" and "EST5EDT".
    /// Callers asking "did the writer name a zone I support?" need this stricter
    /// answer, or an unsupported token looks supported and the time gets
    /// relabelled with the system zone.
    public static func isKnownToken(_ raw: String) -> Bool {
        let key = normalise(raw)
        return map[key] != nil || resolveOffset(key) != nil
    }

    /// Resolve a user-written TZ token (case-insensitive) to a Foundation `TimeZone`.
    public static func resolve(_ raw: String) -> TimeZone? {
        let key = normalise(raw)
        if let iana = map[key], let tz = TimeZone(identifier: iana) {
            return tz
        }
        if let offset = resolveOffset(key) { return offset }
        // Fallback: Foundation knows some identifiers we don't list.
        //
        // `TimeZone(abbreviation:)` is deliberately NOT in this chain. It accepts
        // arbitrary three-letter strings and hands back a *fixed-offset* zone, so
        // an unsupported token would resolve to something plausible-looking that
        // ignores DST — silently wrong by an hour for half the year, which is the
        // failure mode this whole file exists to avoid.
        return TimeZone(identifier: raw)
    }

    /// A stable short label we render to users (e.g. "ET", "PT", "UTC").
    ///
    /// `at` is the instant being rendered. When supplied, zones outside
    /// `regionLabels` are labelled with the abbreviation actually in force at that
    /// instant ("CEST" in July, "CET" in January). When it is absent we fall back
    /// to a plain UTC offset rather than the raw IANA id — dumping
    /// "5pm America/Toronto" into a chat message is not something anyone wants to
    /// send, and every Canadian, Berliner, Dubliner and Australian outside the
    /// hardcoded list used to get exactly that.
    public static func shortLabel(for tz: TimeZone, at date: Date? = nil) -> String {
        if let region = regionLabels[tz.identifier] { return region }

        if let date, let pair = dstAbbreviations[tz.identifier] {
            return tz.isDaylightSavingTime(for: date) ? pair.daylight : pair.standard
        }

        // Everything else gets an offset. We deliberately do NOT ask the platform
        // for an abbreviation here any more, for two reasons found by measurement:
        //
        //  - It is not the same everywhere. java.time on a desktop JVM, ICU on
        //    Android, and swift-corelibs on a CI runner disagree about which zones
        //    have a name, so the same message rendered "11pm CEST" on one and
        //    "11pm UTC+2" on another. A differential run over both cores found all
        //    20 tested unlisted zones diverging on exactly this call.
        //  - Where it does have a name, that name is not necessarily ours.
        //    Europe/Dublin abbreviates to "IST" in summer, and `map["ist"]` is
        //    Asia/Kolkata — so a Dublin user's 5pm was labelled in a way that
        //    re-parses four and a half hours away.
        //
        // An offset is plainer than "CEST", and it is the same on every platform,
        // never wrong, and always readable back by `offsetToken`. That trade is
        // worth it: this label goes out in somebody's message.
        return offsetLabel(tz, at: date)
    }

    /// "UTC+5:30" / "UTC-4" — always meaningful, never a raw IANA id.
    private static func offsetLabel(_ tz: TimeZone, at date: Date?) -> String {
        let seconds = tz.secondsFromGMT(for: date ?? Date())
        if seconds == 0 { return "UTC" }
        let totalMinutes = seconds / 60
        let sign = totalMinutes < 0 ? "-" : "+"
        let hours = abs(totalMinutes) / 60
        let minutes = abs(totalMinutes) % 60
        if minutes == 0 { return "UTC\(sign)\(hours)" }
        let mm = minutes < 10 ? "0\(minutes)" : "\(minutes)"
        return "UTC\(sign)\(hours):\(mm)"
    }
}
