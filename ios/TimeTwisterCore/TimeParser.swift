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

    /// Longest selection we will parse. Roughly a page of text; anything larger is
    /// a "select all", not a time reference.
    ///
    /// It lives here rather than in a host because every host needs it and only one
    /// had it. Android capped at this value before parsing and was measured safe;
    /// this platform's Action Extension and keyboard, and the desktop tray, had no
    /// cap at all — and "Select All" in Notes is one tap from the share sheet. An
    /// extension that blocks for seconds is killed by the watchdog, and the
    /// keyboard runs on the main thread on every keystroke.
    ///
    /// The parser is linear now, which makes this a belt-and-braces bound rather
    /// than the load-bearing one it used to be — but the cost of parsing a whole
    /// document is still real, and nothing good comes of converting one.
    public static let maxInputChars = 5000

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
            # Military / 24-hour-without-a-colon, e.g. "1700 UTC", "1400 CET".
            # The valid range is written into the pattern rather than checked
            # afterwards, so "2500" and "1899" simply are not this shape. Listed
            # before the general hour branch so four digits win over two.
            # A zone of at least three characters is REQUIRED — see detect().
            (?<mil>(?:[01]\d|2[0-3])[0-5]\d)
          |
            (?<hour>\d{1,2})
            # A dot is accepted as a minute separator, but only conditionally —
            # see the sep check in detect(). "5.30pm" is how a lot of the world
            # writes half past five; "3.50 pt" is a measurement.
            (?: (?<sep>[:.]) (?<minute>\d{2}) )?
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

    /// A relative-time phrase immediately before the match — "half past 5pm",
    /// "quarter to 6pm", "ten past 3pm".
    ///
    /// These read as an offset from the hour, and we parse only the hour. So
    /// "half past 5pm ET" was rendering "(4pm CT · 2pm PT)" — the equivalents of
    /// 5:00, while the sentence the reader is looking at says half past. A silent
    /// half-hour error in a message that has already been sent, which is worse than
    /// the false positives this parser is otherwise arranged to avoid, because
    /// nothing about the output looks wrong.
    ///
    /// Declining is the fix rather than implementing the arithmetic: "half past 5
    /// ET" has no am/pm and is already refused for that reason, so supporting the
    /// phrase would mean guessing morning or evening — exactly what the
    /// disambiguator rule exists to prevent. A missed conversion costs a retry.
    ///
    /// "5 to 6pm" and "10 to 6pm" are caught by the same rule and are genuinely
    /// ambiguous between a range and a relative time, so declining both is right.
    private static let relativeTimePrefix = try! NSRegularExpression(
        pattern: #"\b(?:half|quarter|five|ten|twenty|twenty[-\x20]?five|\d{1,2})"#
            + #"\#(SP)+(?:past|to|after|till|til)\#(SP)*$"#,
        options: [.caseInsensitive]
    )

    /// Longest phrase `relativeTimePrefix` can match ("twenty-five after " = 18).
    private static let relativeLookback = 32

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

    /// How far back `timeBeforeStamp` may look. The longest thing it can match is a
    /// time plus the widest label we emit plus separators — comfortably under 64.
    private static let stampLookback = 64

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
                // Searched over a bounded range rather than a copied prefix. This
                // runs once per stamp in the text, and copying from the start each
                // time is how a linear parser becomes quadratic: 200 KB of
                // already-stamped text took 34 seconds before this was bounded.
                let start = max(0, range.location - stampLookback)
                return timeBeforeStamp.firstMatch(
                    in: text,
                    range: NSRange(location: start, length: range.location - start)
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

        // Both `stamps` and the matches below arrive in increasing start order, so
        // this advances instead of rescanning. `stamps.contains(where:)` was
        // O(stamps) per match — on 200 KB of already-stamped text that is ~8,000
        // stamps against ~16,000 matches, i.e. over a hundred million comparisons
        // for a question each match should be able to answer in one. It cost 10.6s
        // here against Kotlin's 0.3s for the same input; the JVM was absorbing the
        // same bad algorithm well enough to hide it.
        var stampIndex = 0

        for m in pattern.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            // Never re-read our own output.
            while stampIndex < stamps.count,
                  stamps[stampIndex].location + stamps[stampIndex].length <= m.range.location {
                stampIndex += 1
            }
            if stampIndex < stamps.count,
               NSLocationInRange(m.range.location, stamps[stampIndex]) {
                continue
            }

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

            // "half past 5pm" — an offset from the hour we would render as the hour.
            //
            // Searched over a bounded range rather than a copied prefix: this runs
            // once per match, and materialising the prefix each time is how a linear
            // parser turns quadratic on a long selection. `$` anchors to the end of
            // the search range by default, which is exactly the "immediately before
            // the match" test we want.
            let lookbackStart = max(0, m.range.location - relativeLookback)
            if relativeTimePrefix.firstMatch(
                in: text,
                range: NSRange(location: lookbackStart, length: m.range.location - lookbackStart)
            ) != nil { continue }

            func group(_ name: String) -> String? {
                let r = m.range(withName: name)
                return r.location == NSNotFound ? nil : ns.substring(with: r)
            }

            let keywordRaw = group("keyword")?.lowercased()
            let tzRaw = group("tz") ?? group("tzparen") ?? group("tzbracket")
            let hasTz = !(tzRaw ?? "").isEmpty
            let zone = hasTz ? (TimeZoneAlias.resolve(tzRaw!) ?? defaultZone) : defaultZone

            // Both checks below look at what immediately follows the match, and both
            // run once per match, so neither may copy the tail of the string.
            // `NSRegularExpression` anchors `^` to the start of the search range by
            // default, which is exactly the question being asked.
            let afterStart = min(m.range.location + m.range.length, ns.length)
            let afterRange = NSRange(location: afterStart, length: ns.length - afterStart)

            // A zone token sits right there but we did not consume it — either we
            // don't support it, or something (markup, punctuation) separated it
            // from the time. Guessing the device zone would relabel the writer's
            // own words; decline instead.
            if !hasTz, trailingZoneToken.firstMatch(in: text, range: afterRange) != nil {
                continue
            }

            // A spelled-out zone word followed by a capitalised word is a place
            // name — "Central Park", not US Central.
            if hasTz, let raw = tzRaw, Self.spelledOut.contains(raw.lowercased()) {
                if let hit = spelledOutFollower.firstMatch(in: text, range: afterRange) {
                    let follower = ns.substring(with: hit.range(at: 1)).lowercased()
                    if !Self.zoneSuffixWords.contains(follower) { continue }
                }
            }

            let hour24: Int
            let minute: Int

            if keywordRaw == "noon" {
                hour24 = 12
                minute = 0
            } else if keywordRaw == "midnight" {
                hour24 = 0
                minute = 0
            } else if let mil = group("mil") {
                // "1700 UTC". Two restrictions, both learned by running it:
                //
                // A bare four-digit number is a year, a price, a count or a flight
                // number far more often than a time, so an explicit zone is
                // required — which is also how anyone who writes this form writes
                // it. ("in 1700 the war ended" must survive.)
                //
                // And the zone has to be at least three characters. Every
                // two-letter abbreviation we accept collides with a unit or an
                // ordinary abbreviation in exactly this position: "1500 MT" is
                // metric tons, "2000 PT" is physical-therapy sessions, "1200 CT" is
                // CT scans. All three were being rewritten mid-sentence. Three
                // characters keeps the canonical spellings — UTC, GMT, EST, CET,
                // "0900 pacific" — and costs us "1700 ET", which is a real usage
                // and is recorded as a known gap rather than pretended away.
                //
                // The pattern has already restricted the digits to 0000-2359.
                guard hasTz, (tzRaw?.count ?? 0) >= 3 else { continue }
                hour24 = Int(mil.prefix(2)) ?? 0
                minute = Int(mil.suffix(2)) ?? 0
            } else {
                guard let hourRaw = group("hour").flatMap(Int.init) else { continue }
                let minuteRaw = group("minute").flatMap(Int.init) ?? 0
                let ampmRaw = group("ampm")?.lowercased().replacingOccurrences(of: ".", with: "")
                let hasAmpm = !(ampmRaw ?? "").isEmpty
                let hasMinute = m.range(withName: "minute").location != NSNotFound

                // Require am/pm or a minute separator. A bare number plus a zone
                // token is not evidence of a time — it is how people write font
                // sizes ("12 pt font"), carats ("1 ct diamond"), scores ("won
                // 3-1 pt"), abbreviations ("top 5 est. results") and street
                // addresses ("5 London Road"). Every one of those was being
                // rewritten into the middle of a real sentence. A zone alone is
                // too weak a signal; requiring a clock-shaped time costs us
                // "5 ET", which almost nobody writes, and buys back the class.
                if !hasAmpm && !hasMinute { continue }

                // A dot only separates minutes when am/pm confirms it.
                //
                // "5.30pm" is how much of the world writes half past five, and it
                // silently did nothing here before. But a dot is also a decimal
                // point, so accepting it unconditionally would make "3.50 pt" a
                // time — the same false-positive class the rule above exists to
                // kill, re-entering through a different door.
                if group("sep") == ".", !hasAmpm { continue }

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
