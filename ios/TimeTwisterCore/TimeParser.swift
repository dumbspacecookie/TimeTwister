//
//  TimeParser.swift
//  TimeTwisterCore
//
//  Created by dumbspacecookie on 4/21/26.
//
//  Mirrors com.timetwister.core.TimeParser. The Kotlin core is the reference
//  implementation — it is scored against a graded corpus on every build — so
//  behaviour changes belong there first and are translated here against that
//  oracle. Rule and comment order is kept aligned between the two files on
//  purpose, so a diff between them is readable.
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

    public init(
        hour: Int,
        minute: Int,
        timeZone: TimeZone,
        hadExplicitZone: Bool,
        range: NSRange,
        originalText: String
    ) {
        self.hour = hour
        self.minute = minute
        self.timeZone = timeZone
        self.hadExplicitZone = hadExplicitZone
        self.range = range
        self.originalText = originalText
    }
}

/// Parses human-written time expressions like "5pm CT", "17:00 ET", "5:30pm pacific".
///
/// Deliberately conservative. This parser edits text the user is about to SEND, so a
/// false positive is far more costly than a miss: a missed conversion is invisible,
/// but a wrong one garbles a real message in front of a real recipient. Every rule
/// below is biased accordingly.
public enum TimeParser {

    /// Horizontal separators accepted between a time and its zone.
    ///
    /// `\s` is ASCII-only in most engines, which used to mean a non-breaking
    /// space (U+00A0, everywhere in text pasted from Word/Outlook/Docs) or a
    /// narrow no-break space (U+202F, what Apple/CLDR put between a time and its
    /// AM/PM) silently defeated the zone match — and then the time was attributed
    /// to the *system* zone and rendered with full confidence. That failure was
    /// silent and wrong by hours, so the separator class is spelled out
    /// explicitly. Written as escapes, never as literal characters: the pattern
    /// runs in COMMENTS mode, where whitespace can be stripped inside character
    /// classes too, so a literal space would vanish.
    ///
    /// \n and \r are in the set deliberately: a zone can wrap onto the next line
    /// ("meet 5pm\nCT"), and losing it there does not merely miss the conversion —
    /// it re-attributes the time to the system zone and renders it with full
    /// confidence, which is the worst failure mode this tool has.
    private static let SP = #"[\x20\t\r\n\xA0\x{202F}\x{2007}\x{2009}]"#

    /// The same set as `SP`, for the hand-written scans that don't go through a regex.
    private static let spaceChars: Set<Character> = [
        "\u{0020}", "\u{0009}", "\u{000D}", "\u{000A}",
        "\u{00A0}", "\u{202F}", "\u{2007}", "\u{2009}",
    ]

    /// Zone tokens, longest-first: multi-word names must beat their own prefixes, or
    /// "3pm Central European Time" matches `central` and resolves to US Central —
    /// wrong zone AND a dangling " European Time" left in the message.
    /// Zone tokens that are also ordinary English words. They have to be accepted —
    /// people write "5pm pacific" — but they are the ones that can swallow a place
    /// name, so `spelledOutFollower` guards them and the abbreviations go free.
    private static let spelledOut: Set<String> = [
        "eastern", "central", "mountain", "pacific", "alaska", "hawaii",
        "london", "tokyo", "singapore", "sydney", "india",
    ]

    /// What may legitimately follow a spelled-out zone word.
    ///
    /// "5pm Central Park" used to render "5pm CT (…) Park", destroying the sentence
    /// in front of the recipient; so did Mountain View, London Bridge, India Gate,
    /// Sydney Opera House and Pacific Coast Highway. Worse, "5pm Eastern Europe"
    /// resolved to *US Eastern* — the multi-word alias is "eastern european", not
    /// "eastern europe", so the bare token won and the zone was wrong by six hours.
    ///
    /// A following capitalised word means the writer is naming a place, not a zone.
    /// Lowercase words, punctuation, end-of-text and the zone suffixes we already
    /// consume are all fine. Getting this wrong in the safe direction costs a
    /// conversion the user can redo; getting it wrong in the other direction
    /// corrupts a message they have already sent.
    private static let spelledOutFollower =
        try! NSRegularExpression(pattern: #"^\#(SP)+([A-Z][a-z]{2,})"#)

    /// Words that are part of a zone name rather than the start of a place name.
    private static let zoneSuffixWords: Set<String> =
        ["time", "standard", "daylight", "summer", "european"]

    private static let tzTokens = #"""
              (?:utc|gmt) [+-] \d{1,2} (?: :? \d{2} )?
            | central\#(SP)+european | eastern\#(SP)+european
            | et|est|edt|ct|cst|cdt|mt|mst|mdt|pt|pst|pdt|akst|akdt|hst
            | utc|gmt|bst|cet|cest|eet|eest|uk|cn
            | ist|jst|kst|sgt|hkt|aest|aedt|nzst|nzdt
            | eastern|central|mountain|pacific|alaska|hawaii
            | london|tokyo|singapore|sydney|india
    """#

    /// A paired abbreviation the writer used to cover both halves of the year
    /// ("5pm EST/EDT"). We resolve via the IANA zone anyway, so the second half is
    /// redundant — but it has to be *consumed*, or it dangles after the stamp as
    /// "5pm ET (4pm CT · 2pm PT)/EDT".
    private static let tzPair = #"est|edt|cst|cdt|mst|mdt|pst|pdt|aest|aedt|nzst|nzdt|cet|cest|eet|eest"#

    // (?xi) = COMMENTS + CASE_INSENSITIVE. Extended syntax lets us annotate the groups.
    //
    // The flags are concatenated OUTSIDE the pattern body rather than written as
    // its first line, matching Kotlin. There, interpolating the token list drags
    // in lines indented less than the pattern body, which lowers what
    // `trimIndent()` considers the common prefix and leaves literal whitespace
    // ahead of "(?xi)" — at which point the flags are no longer at position 0 and
    // the pattern silently matches nothing at all. Swift has no trimIndent, but
    // keeping the two files structurally identical is worth more than the two
    // characters it saves.
    private static let pattern: NSRegularExpression = {
        let body = #"""
        \b
        (?:
            # "12 noon" and "12 midnight" are written often enough to matter, and
            # the 12 has to be part of the match — otherwise it is left stranded in
            # front of the stamp as "12 12pm ET (…)".
            (?: 12 \#(SP)* )? (?<keyword>noon|midnight)
          |
            (?<hour>\d{1,2})
            (?: : (?<minute>\d{2}) )?
            (?: \#(SP)* (?<ampm>am|pm|a\.m\.|p\.m\.) )?
        )
        (?:
            # Optional separator, then the zone — either bare or bracketed. A comma
            # or a bracket between the two used to break the match entirely
            # ("5pm, CT" / "5pm (CT)"), which then silently re-attributed the time
            # to the system zone.
            ,? \#(SP)*
            (?:
                \( \#(SP)* (?<tzparen>\#(tzTokens)) \#(SP)* \)
              |
                \[ \#(SP)* (?<tzbracket>\#(tzTokens)) \#(SP)* \]
              |
                (?<tz>\#(tzTokens))
                (?: / (?:\#(tzPair)) )?
                (?: \#(SP)+ (?:standard|daylight|summer) )?
                (?: \#(SP)+ time )?
            )
        )?
        # Not \b. \b is Unicode-aware, so "5pm CTです" has no boundary between the T
        # and the で — the zone was dropped and the time silently relabelled to the
        # system zone. All we actually need is that the token isn't running into
        # more ASCII word characters ("CTX" must not match "CT").
        (?! [A-Za-z0-9] )
        """#
        // A malformed pattern here is a programming error, not a runtime
        // condition — and the old code swallowed it and returned [], which is
        // exactly how a silently-dead parser ships. Fail loudly instead.
        return try! NSRegularExpression(pattern: "(?xi)" + body)
    }()

    /// Recognises a stamp this tool rendered earlier, e.g. "(6pm ET · 3pm PT)" or
    /// "(1am CT +1d · 2am ET +1d)".
    ///
    /// Without this the tool could not read its own output: the times inside a stamp
    /// are perfectly valid time expressions, so a second pass detected one and
    /// stamped the stamp — "5pm CT (6pm ET · 3pm PT (5pm CT · 6pm ET))". On iOS the
    /// share sheet hands the same selection straight back, so that was one stray
    /// double-tap away.
    private static let stampPart =
        #"\d{1,2}(?::\d{2})?(?:am|pm)[ ]+[A-Za-z][A-Za-z0-9+:/_-]*(?:[ ]+[+-]\d+d)?"#

    static let stamp: NSRegularExpression = {
        try! NSRegularExpression(
            pattern: #"\((?:\#(stampPart))(?:[ ]*·[ ]*(?:\#(stampPart)))*\)"#,
            options: [.caseInsensitive]
        )
    }()

    /// A zone-shaped token immediately after a time we could not attach a zone to.
    ///
    /// Two cases, and we decline for both:
    ///
    ///  - A zone we don't support (NPT, ACST, MSK, WAT). The user clearly named a
    ///    zone, so falling back to the system zone relabels their time as something
    ///    else entirely ("call 7pm NPT" → "call 7pm ET (…) NPT").
    ///  - A zone we *do* support, which the pattern nevertheless failed to consume
    ///    because something sits between it and the time. This is the more common
    ///    and more damaging case: "Standup is **5pm** CT" is ordinary Slack and
    ///    WhatsApp bold, and the closing asterisks defeated the match, so the time
    ///    was silently reattributed to the device zone. The writer typed CT and the
    ///    message went out saying ET.
    ///
    /// Hence a gap class rather than plain whitespace: any run of markup, quotes or
    /// brackets between the time and the token still counts as "the writer named a
    /// zone here". Leaving the text alone is the safe answer for both.
    ///
    /// Sentence punctuation is excluded from that gap on purpose. It ends the
    /// clause, so what follows is a new sentence rather than a zone attached to this
    /// time — without the exclusion, "lets meet at 5pm. OK so then…" and "3pm. FYI"
    /// both stop converting, which is a safe failure but a needless one.
    ///
    /// Case-sensitive on purpose. An all-caps run is what makes a bare token read as
    /// a zone abbreviation instead of an ordinary word; accepting lowercase would
    /// make "see you at 5pm ok" decline. Lowercase unsupported tokens ("call 5pm
    /// msk") consequently still slip through — that is a known gap with a corpus
    /// row, not an oversight.
    private static let trailingZoneToken =
        try! NSRegularExpression(pattern: #"^[^A-Za-z0-9.,!?;:]*([A-Z]{2,5})\b"#)

    /// A URL or path scheme at the very start of the token the match sits in.
    ///
    /// "see https://x.com/a/5pm now" was rewritten to
    /// "see https://x.com/a/5pm ET (4pm CT · …) now" — the link is destroyed, and the
    /// user has no way to tell until somebody clicks it. Nothing shaped like this is
    /// ever a time a reader needs converted.
    private static let urlScheme = try! NSRegularExpression(
        pattern: #"^(?:[a-z][a-z0-9+.-]*://|www\.|mailto:)"#,
        options: [.caseInsensitive]
    )

    /// A stamp is only ever emitted directly after the source time, so "(6pm PT)" on
    /// its own is a user's parenthesised time, not our output — even though it is
    /// shaped exactly like a single-conversion stamp. Requiring a time immediately
    /// before the bracket keeps us from ignoring text we have never touched.
    private static let timeBeforeStamp = try! NSRegularExpression(
        pattern: #"\d{1,2}(?::\d{2})?(?:am|pm)\#(SP)+[A-Za-z][A-Za-z0-9+:/_-]*\#(SP)*$"#,
        options: [.caseInsensitive]
    )

    /// True when the match sits inside a URL or a path.
    ///
    /// Judged from what precedes the match within its own non-space token, not from
    /// the token as a whole. A slash *after* the match is ordinary writing — "5pm
    /// EST/EDT" is a zone pair we deliberately support, and "5pm ET/PT" is someone
    /// naming two zones — whereas a slash *before* it, inside the same token, means
    /// we are somewhere in a path. Scanning the whole token declines both, which
    /// cost the EST/EDT case the first time this was written.
    private static func isInsideUrlOrPath(_ ns: NSString, _ range: NSRange) -> Bool {
        var start = range.location
        while start > 0,
              let c = ns.substring(with: NSRange(location: start - 1, length: 1)).first,
              !spaceChars.contains(c) {
            start -= 1
        }
        guard start < range.location else { return false }

        let prefix = ns.substring(with: NSRange(location: start, length: range.location - start))
        if prefix.contains("/") { return true }
        let prefixNS = prefix as NSString
        return urlScheme.firstMatch(
            in: prefix, range: NSRange(location: 0, length: prefixNS.length)
        ) != nil
    }

    /// Ranges occupied by stamps this tool rendered earlier.
    private static func stampRanges(in text: String) -> [NSRange] {
        let ns = text as NSString
        return stamp
            .matches(in: text, range: NSRange(location: 0, length: ns.length))
            .map(\.range)
            .filter { range in
                let before = ns.substring(to: range.location)
                let beforeNS = before as NSString
                return timeBeforeStamp.firstMatch(
                    in: before,
                    range: NSRange(location: 0, length: beforeNS.length)
                ) != nil
            }
    }

    /// - Parameter defaultZone: the zone to attribute a time to when the writer
    ///   named none. Defaults to the device zone, which is what every production
    ///   caller wants; it is a parameter so tests can pin it.
    ///
    ///   Kotlin reads `ZoneId.systemDefault()` here and its suite pins the JVM
    ///   default around the test class instead. That trick does not port:
    ///   `NSTimeZone.default` is honoured by Apple's Foundation but ignored by
    ///   swift-corelibs, so on a Windows or Linux runner every zone-less row
    ///   would silently be scored against whatever zone the machine happens to
    ///   be in. An explicit seam is both more honest and less spooky than a
    ///   global mutation, so it is the seam that got ported rather than the
    ///   trick.
    public static func detect(
        in text: String,
        defaultZone: TimeZone = .current
    ) -> [DetectedTime] {
        let ns = text as NSString
        let stamps = stampRanges(in: text)
        var out: [DetectedTime] = []

        for m in pattern.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            // Never re-read our own output.
            if stamps.contains(where: { NSLocationInRange(m.range.location, $0) }) { continue }

            // A match glued to the right of a digit or a colon is a fragment of a
            // larger number, not a time: "9:5 am" would otherwise yield "5 am".
            //
            // `decimalDigits` (Unicode category Nd), not `Character.isNumber`.
            // `isNumber` also covers No and Nl — superscripts, fractions, Roman
            // numerals — so "²5pm ET" and "½noon" were skipped here while Kotlin,
            // whose `isDigit` is Nd-only, detected them. A differential run over
            // both cores found 98 such inputs.
            if m.range.location > 0 {
                let prev = ns.substring(with: NSRange(location: m.range.location - 1, length: 1))
                let isDigit = prev.unicodeScalars.first
                    .map(CharacterSet.decimalDigits.contains) ?? false
                if prev == ":" || prev == "." || isDigit { continue }
            }

            // Inside a URL or a path — never a time meant for a reader.
            if isInsideUrlOrPath(ns, m.range) { continue }

            func group(_ name: String) -> String? {
                let r = m.range(withName: name)
                return r.location == NSNotFound ? nil : ns.substring(with: r)
            }

            let keywordRaw = group("keyword")?.lowercased()
            let tzRaw = group("tz") ?? group("tzparen") ?? group("tzbracket")
            let hasTz = !(tzRaw ?? "").isEmpty
            let zone = hasTz ? (TimeZoneAlias.resolve(tzRaw!) ?? defaultZone) : defaultZone

            let end = min(m.range.location + m.range.length, ns.length)
            let after = ns.substring(from: end)
            let afterNS = after as NSString
            let afterRange = NSRange(location: 0, length: afterNS.length)

            // A zone token sits right there but we did not consume it — either we
            // don't support it, or something (markup, punctuation) separated it
            // from the time. Guessing the device zone would relabel the writer's
            // own words; decline instead.
            if !hasTz, trailingZoneToken.firstMatch(in: after, range: afterRange) != nil {
                continue
            }

            // A spelled-out zone word followed by a capitalised word is a place
            // name — "Central Park", not US Central.
            if hasTz, let raw = tzRaw, Self.spelledOut.contains(raw.lowercased()) {
                if let hit = spelledOutFollower.firstMatch(in: after, range: afterRange) {
                    let follower = afterNS.substring(with: hit.range(at: 1)).lowercased()
                    if !Self.zoneSuffixWords.contains(follower) { continue }
                }
            }

            let hour24: Int
            let minute: Int

            switch keywordRaw {
            case "noon":
                hour24 = 12
                minute = 0
            case "midnight":
                hour24 = 0
                minute = 0
            default:
                guard let hourRaw = group("hour").flatMap(Int.init) else { continue }
                let minuteRaw = group("minute").flatMap(Int.init) ?? 0
                let ampmRaw = group("ampm")?.lowercased().replacingOccurrences(of: ".", with: "")
                let hasAmpm = !(ampmRaw ?? "").isEmpty
                let hasColon = m.range(withName: "minute").location != NSNotFound

                // Require am/pm or a colon. A bare number plus a zone token is
                // not evidence of a time — it is how people write font sizes
                // ("12 pt font"), carats ("1 ct diamond"), scores ("won 3-1 pt"),
                // abbreviations ("top 5 est. results") and street addresses
                // ("5 London Road"). Every one of those was being rewritten into
                // the middle of a real sentence. A zone alone is too weak a
                // signal; requiring a clock-shaped time costs us "5 ET", which
                // almost nobody writes, and buys back the entire class.
                if !hasAmpm && !hasColon { continue }

                var h = hourRaw
                if hasAmpm {
                    guard (1...12).contains(hourRaw) else { continue }
                    if ampmRaw == "pm" && h < 12 { h += 12 }
                    if ampmRaw == "am" && h == 12 { h = 0 }
                } else {
                    guard (0...23).contains(h) else { continue }
                }
                guard (0...59).contains(minuteRaw) else { continue }
                hour24 = h
                minute = minuteRaw
            }

            out.append(DetectedTime(
                hour: hour24,
                minute: minute,
                timeZone: zone,
                hadExplicitZone: hasTz,
                range: m.range,
                originalText: ns.substring(with: m.range)
            ))
        }
        return out
    }

    /// Returns only the last detected time — most useful when handling a user selection.
    public static func detectLast(
        in text: String,
        defaultZone: TimeZone = .current
    ) -> DetectedTime? {
        detect(in: text, defaultZone: defaultZone).last
    }

    /// If a stamp we rendered earlier sits immediately after `endExclusive`, returns
    /// the range it occupies (including the whitespace joining it to the time). The
    /// caller replaces that whole span, so re-running on already-converted text
    /// refreshes the stamp in place rather than nesting a new one inside it.
    static func trailingStampRange(in text: String, from endExclusive: Int) -> NSRange? {
        let ns = text as NSString
        var i = endExclusive
        while i < ns.length,
              let c = ns.substring(with: NSRange(location: i, length: 1)).first,
              spaceChars.contains(c) {
            i += 1
        }
        guard i < ns.length,
              ns.substring(with: NSRange(location: i, length: 1)) == "(" else { return nil }

        let tail = NSRange(location: i, length: ns.length - i)
        guard let m = stamp.firstMatch(in: text, range: tail), m.range.location == i else {
            return nil
        }
        return NSRange(location: endExclusive, length: m.range.location + m.range.length - endExclusive)
    }
}
