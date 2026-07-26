package com.timetwister.core

import java.time.ZoneId
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parses human-written time expressions like "5pm CT", "17:00 ET", "5:30pm pacific".
 *
 * Deliberately conservative. This parser edits text the user is about to SEND, so a
 * false positive is far more costly than a miss: a missed conversion is invisible,
 * but a wrong one garbles a real message in front of a real recipient. Every rule
 * below is biased accordingly.
 */
object TimeParser {

    /**
     * Longest selection we will parse. Roughly a page of text; anything larger is a
     * "select all", not a time reference.
     *
     * It lives here rather than in a host because every host needs it and only one
     * had it. Android capped at this value before parsing and was measured safe;
     * the iOS Action Extension, the iOS keyboard and the desktop tray had no cap at
     * all, and "Select All" in Notes is one tap from the share sheet. An extension
     * that blocks for seconds is killed by the watchdog, and the keyboard runs on
     * the main thread on every keystroke.
     *
     * The parser is linear now, which makes this a belt-and-braces bound rather
     * than the load-bearing one it used to be — but the cost of parsing a whole
     * document is still real, and nothing good comes of converting one.
     */
    const val MAX_INPUT_CHARS: Int = 5000

    /**
     * Horizontal separators accepted between a time and its zone.
     *
     * Java's `\s` is ASCII-only, which used to mean a non-breaking space (U+00A0,
     * everywhere in text pasted from Word/Outlook/Docs) or a narrow no-break space
     * (U+202F, what Apple/CLDR put between a time and its AM/PM) silently defeated
     * the zone match — and then the time was attributed to the *system* zone and
     * rendered with full confidence. That failure was silent and wrong by hours, so
     * the separator class is spelled out explicitly. Written as escapes, never as
     * literal characters: the pattern runs in COMMENTS mode, and Java strips
     * whitespace *inside* character classes too, so a literal space would vanish.
     *
     * \n and \r are in the set deliberately: a zone can wrap onto the next line
     * ("meet 5pm\nCT"), and losing it there does not merely miss the conversion —
     * it re-attributes the time to the system zone and renders it with full
     * confidence, which is the worst failure mode this tool has.
     */
    private const val SP = """[\x20\t\r\n\xA0\x{202F}\x{2007}\x{2009}]"""

    /** The same set as [SP], for the hand-written scans that don't go through a regex. */
    private val SPACE_CHARS = charArrayOf(
        '\u0020', '\u0009', '\u000D', '\u000A', '\u00A0', '\u202F', '\u2007', '\u2009',
    )

    /**
     * Zone tokens, longest-first: multi-word names must beat their own prefixes, or
     * "3pm Central European Time" matches `central` and resolves to US Central —
     * wrong zone AND a dangling " European Time" left in the message.
     */
    /**
     * Zone tokens that are also ordinary English words. They have to be accepted —
     * people write "5pm pacific" — but they are the ones that can swallow a place
     * name, so [SPELLED_OUT_FOLLOWER] guards them and the abbreviations go free.
     */
    private val SPELLED_OUT = setOf(
        "eastern", "central", "mountain", "pacific", "alaska", "hawaii",
        "london", "tokyo", "singapore", "sydney", "india",
    )

    /**
     * What may legitimately follow a spelled-out zone word.
     *
     * "5pm Central Park" used to render "5pm CT (…) Park", destroying the sentence
     * in front of the recipient; so did Mountain View, London Bridge, India Gate,
     * Sydney Opera House and Pacific Coast Highway. Worse, "5pm Eastern Europe"
     * resolved to *US Eastern* — the multi-word alias is "eastern european", not
     * "eastern europe", so the bare token won and the zone was wrong by six hours.
     *
     * A following capitalised word means the writer is naming a place, not a zone.
     * Lowercase words, punctuation, end-of-text and the zone suffixes we already
     * consume are all fine. Getting this wrong in the safe direction costs a
     * conversion the user can redo; getting it wrong in the other direction
     * corrupts a message they have already sent.
     */
    private val SPELLED_OUT_FOLLOWER: Pattern = Pattern.compile("""^$SP+([A-Z][a-z]{2,})""")

    /** Words that are part of a zone name rather than the start of a place name. */
    private val ZONE_SUFFIX_WORDS = setOf("time", "standard", "daylight", "summer", "european")

    private const val TZ_TOKENS = """
              (?:utc|gmt) [+-] \d{1,2} (?: :? \d{2} )?
            | central$SP+european | eastern$SP+european
            | et|est|edt|ct|cst|cdt|mt|mst|mdt|pt|pst|pdt|akst|akdt|hst
            | utc|gmt|bst|cet|cest|eet|eest|uk|cn
            | ist|jst|kst|sgt|hkt|aest|aedt|nzst|nzdt
            | eastern|central|mountain|pacific|alaska|hawaii
            | london|tokyo|singapore|sydney|india
    """

    /**
     * A paired abbreviation the writer used to cover both halves of the year
     * ("5pm EST/EDT"). We resolve via the IANA zone anyway, so the second half is
     * redundant — but it has to be *consumed*, or it dangles after the stamp as
     * "5pm ET (4pm CT · 2pm PT)/EDT".
     */
    private const val TZ_PAIR = """est|edt|cst|cdt|mst|mdt|pst|pdt|aest|aedt|nzst|nzdt|cet|cest|eet|eest"""

    // (?xi) = COMMENTS + CASE_INSENSITIVE. Extended syntax lets us annotate the groups.
    //
    // The flags are concatenated OUTSIDE the raw string rather than written as its
    // first line. Interpolating TZ_TOKENS drags in lines indented less than the
    // pattern body, which lowers what trimIndent() considers the common prefix and
    // leaves literal leading whitespace ahead of "(?xi)" — at which point the flags
    // are no longer at position 0, the leading spaces are matched literally instead
    // of ignored, and the pattern silently matches nothing at all.
    private val pattern: Regex = Regex(
        "(?xi)" + """
        \b
        (?:
            # "12 noon" and "12 midnight" are written often enough to matter, and
            # the 12 has to be part of the match — otherwise it is left stranded in
            # front of the stamp as "12 12pm ET (…)".
            (?: 12 $SP* )? (?<keyword>noon|midnight)
          |
            # Military / 24-hour-without-a-colon, e.g. "1700 UTC", "0930 ET".
            # The valid range is written into the pattern rather than checked
            # afterwards, so "2500" and "1899" simply are not this shape. Listed
            # before the general hour branch so four digits win over two.
            # A zone is REQUIRED for this form — see the check in detect().
            (?<mil>(?:[01]\d|2[0-3])[0-5]\d)
          |
            (?<hour>\d{1,2})
            # A dot is accepted as a minute separator, but only conditionally —
            # see the sep check in detect(). "5.30pm" is how a lot of the world
            # writes half past five; "3.50 pt" is a measurement.
            (?: (?<sep>[:.]) (?<minute>\d{2}) )?
            (?: $SP* (?<ampm>am|pm|a\.m\.|p\.m\.) )?
        )
        (?:
            # Optional separator, then the zone — either bare or bracketed. A comma
            # or a bracket between the two used to break the match entirely
            # ("5pm, CT" / "5pm (CT)"), which then silently re-attributed the time
            # to the system zone.
            ,? $SP*
            (?:
                \( $SP* (?<tzparen>$TZ_TOKENS) $SP* \)
              |
                \[ $SP* (?<tzbracket>$TZ_TOKENS) $SP* \]
              |
                (?<tz>$TZ_TOKENS)
                (?: / (?:$TZ_PAIR) )?
                (?: $SP+ (?:standard|daylight|summer) )?
                (?: $SP+ time )?
            )
        )?
        # Not \b. Java's \b is Unicode-aware, so "5pm CTです" has no boundary between
        # the T and the で — the zone was dropped and the time silently relabelled to
        # the system zone. All we actually need is that the token isn't running into
        # more ASCII word characters ("CTX" must not match "CT").
        (?! [A-Za-z0-9] )
        """.trimIndent()
    )

    /**
     * Recognises a stamp this tool rendered earlier, e.g. "(6pm ET · 3pm PT)" or
     * "(1am CT +1d · 2am ET +1d)".
     *
     * Without this the tool could not read its own output: the times inside a stamp
     * are perfectly valid time expressions, so a second pass detected one and
     * stamped the stamp — "5pm CT (6pm ET · 3pm PT (5pm CT · 6pm ET))". On Android
     * the selection usually survives ACTION_PROCESS_TEXT, so that was one stray
     * double-tap away.
     */
    private const val STAMP_PART =
        """\d{1,2}(?::\d{2})?(?:am|pm)[ ]+[A-Za-z][A-Za-z0-9+:/_-]*(?:[ ]+[+-]\d+d)?"""

    internal val STAMP: Regex = Regex(
        """\((?:$STAMP_PART)(?:[ ]*·[ ]*(?:$STAMP_PART))*\)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A zone-shaped token immediately after a time we could not attach a zone to.
     *
     * Two cases, and we decline for both:
     *
     *  - A zone we don't support (NPT, ACST, MSK, WAT). The user clearly named a
     *    zone, so falling back to the system zone relabels their time as something
     *    else entirely ("call 7pm NPT" → "call 7pm ET (…) NPT").
     *  - A zone we *do* support, which the pattern nevertheless failed to consume
     *    because something sits between it and the time. This is the more common
     *    and more damaging case: "Standup is **5pm** CT" is ordinary Slack and
     *    WhatsApp bold, and the closing asterisks defeated the match, so the time
     *    was silently reattributed to the device zone. The writer typed CT and the
     *    message went out saying ET.
     *
     * Hence a gap class rather than plain whitespace: any run of markup, quotes or
     * brackets between the time and the token still counts as "the writer named a
     * zone here". Leaving the text alone is the safe answer for both.
     *
     * Sentence punctuation is excluded from that gap on purpose. It ends the
     * clause, so what follows is a new sentence rather than a zone attached to this
     * time — without the exclusion, "lets meet at 5pm. OK so then…" and "3pm. FYI"
     * both stop converting, which is a safe failure but a needless one.
     *
     * Case-sensitive on purpose. An all-caps run is what makes a bare token read as
     * a zone abbreviation instead of an ordinary word; accepting lowercase would
     * make "see you at 5pm ok" decline. Lowercase unsupported tokens ("call 5pm
     * msk") consequently still slip through — that is a known gap with a corpus
     * row, not an oversight.
     */
    private val TRAILING_ZONE_TOKEN: Pattern =
        Pattern.compile("""^[^A-Za-z0-9.,!?;:]*([A-Z]{2,5})\b""")

    /**
     * A relative-time phrase immediately before the match — "half past 5pm",
     * "quarter to 6pm", "ten past 3pm".
     *
     * These read as an offset from the hour, and we parse only the hour. So
     * "half past 5pm ET" was rendering "(4pm CT · 2pm PT)" — the equivalents of
     * 5:00, while the sentence the reader is looking at says half past. A silent
     * half-hour error in a message that has already been sent, which is worse than
     * the false positives this parser is otherwise arranged to avoid, because
     * nothing about the output looks wrong.
     *
     * Declining is the fix rather than implementing the arithmetic: "half past 5
     * ET" has no am/pm and is already refused for that reason, so supporting the
     * phrase would mean guessing morning or evening — exactly what the
     * disambiguator rule exists to prevent. A missed conversion costs a retry.
     *
     * "5 to 6pm" and "10 to 6pm" are caught by the same rule and are genuinely
     * ambiguous between a range and a relative time, so declining both is right.
     */
    private val RELATIVE_TIME_PREFIX = Regex(
        """(?i)\b(?:half|quarter|five|ten|twenty|twenty[-\x20]?five|\d{1,2})""" +
            """$SP+(?:past|to|after|till|til)$SP*$""",
    )

    /** Longest phrase RELATIVE_TIME_PREFIX can match ("twenty-five after " = 18). */
    private const val RELATIVE_LOOKBACK = 32

    /**
     * A URL or path scheme at the very start of the token the match sits in.
     *
     * "see https://x.com/a/5pm now" was rewritten to
     * "see https://x.com/a/5pm ET (4pm CT · …) now" — the link is destroyed, and the
     * user has no way to tell until somebody clicks it. Nothing shaped like this is
     * ever a time a reader needs converted.
     */
    private val URL_SCHEME = Regex("""(?i)^(?:[a-z][a-z0-9+.-]*://|www\.|mailto:)""")

    /**
     * A stamp is only ever emitted directly after the source time, so "(6pm PT)" on
     * its own is a user's parenthesised time, not our output — even though it is
     * shaped exactly like a single-conversion stamp. Requiring a time immediately
     * before the bracket keeps us from ignoring text we have never touched.
     */
    private val TIME_BEFORE_STAMP: Pattern = Pattern.compile(
        """\d{1,2}(?::\d{2})?(?:am|pm)$SP+[A-Za-z][A-Za-z0-9+:/_-]*$SP*$""",
        Pattern.CASE_INSENSITIVE,
    )

    /**
     * How far back [TIME_BEFORE_STAMP] may look. The longest thing it can match is
     * a time plus the widest label we emit plus separators — comfortably under 64.
     *
     * The bound is the point. This test runs once per stamp in the text, and
     * scanning from the start of the string each time is how a linear parser
     * becomes quadratic: 200 KB of already-stamped text took 34 seconds before
     * these were bounded. Android caps its input at 5000 characters and was safe;
     * the iOS extension, the iOS keyboard and the desktop tray had no cap at all.
     */
    private const val STAMP_LOOKBACK = 64

    /**
     * Run [pattern] over `text[start, end)` without copying the substring.
     *
     * `Matcher.region` keeps anchoring bounds on by default, so `^` and `$` mean
     * "start/end of this region" — which is exactly the question every caller here
     * is asking, and the reason a region beats a substring even before the
     * allocation is considered.
     */
    private fun matcherOn(pattern: Pattern, text: String, start: Int, end: Int): Matcher =
        pattern.matcher(text).region(start.coerceIn(0, text.length), end.coerceIn(0, text.length))

    /**
     * True when the match sits inside a URL or a path.
     *
     * Judged from what precedes the match within its own non-space token, not from
     * the token as a whole. A slash *after* the match is ordinary writing — "5pm
     * EST/EDT" is a zone pair we deliberately support, and "5pm ET/PT" is someone
     * naming two zones — whereas a slash *before* it, inside the same token, means
     * we are somewhere in a path. Scanning the whole token declines both, which
     * cost the EST/EDT case the first time this was written.
     */
    private fun isInsideUrlOrPath(text: String, range: IntRange): Boolean {
        var start = range.first
        while (start > 0 && text[start - 1] !in SPACE_CHARS) start--
        if (start == range.first) return false
        val prefix = text.substring(start, range.first)
        return prefix.contains('/') || URL_SCHEME.containsMatchIn(prefix)
    }

    /** Ranges occupied by stamps this tool rendered earlier. */
    private fun stampRanges(text: String): List<IntRange> =
        STAMP.findAll(text)
            .filter {
                matcherOn(
                    TIME_BEFORE_STAMP,
                    text,
                    it.range.first - STAMP_LOOKBACK,
                    it.range.first,
                ).find()
            }
            .map { it.range }
            .toList()

    fun detect(text: String): List<DetectedTime> {
        val defaultZone = ZoneId.systemDefault()
        val out = mutableListOf<DetectedTime>()
        val stamps = stampRanges(text)

        // Both `stamps` and the matches below arrive in increasing start order, so
        // this advances instead of rescanning. `stamps.any { ... }` was O(stamps)
        // per match — on 200 KB of already-stamped text that is ~8,000 stamps
        // against ~16,000 matches, i.e. over a hundred million comparisons for a
        // question each match should be able to answer in one.
        var stampIndex = 0

        for (m in pattern.findAll(text)) {
            // Never re-read our own output.
            while (stampIndex < stamps.size && stamps[stampIndex].last < m.range.first) {
                stampIndex++
            }
            if (stampIndex < stamps.size &&
                m.range.first >= stamps[stampIndex].first &&
                m.range.first <= stamps[stampIndex].last
            ) {
                continue
            }

            // A match glued to the right of a digit or a colon is a fragment of a
            // larger number, not a time: "9:5 am" would otherwise yield "5 am".
            val before = text.getOrNull(m.range.first - 1)
            if (before != null && (before.isDigit() || before == ':' || before == '.')) continue

            // Inside a URL or a path — never a time meant for a reader.
            if (isInsideUrlOrPath(text, m.range)) continue

            // "half past 5pm" — an offset from the hour we would render as the hour.
            // Bounded lookback rather than the whole prefix: this runs once per
            // match, and copying the prefix each time is how a linear parser turns
            // quadratic on a long selection.
            val lookback = text.substring(
                maxOf(0, m.range.first - RELATIVE_LOOKBACK),
                m.range.first,
            )
            if (RELATIVE_TIME_PREFIX.containsMatchIn(lookback)) continue

            val keywordRaw = m.groups["keyword"]?.value?.lowercase()
            val tzRaw = m.groups["tz"]?.value
                ?: m.groups["tzparen"]?.value
                ?: m.groups["tzbracket"]?.value
            val hasTz = !tzRaw.isNullOrEmpty()
            val zone = if (hasTz) TimeZoneAlias.resolve(tzRaw!!) ?: defaultZone else defaultZone

            // Both checks below look at what immediately follows the match, and both
            // run once per match, so neither may copy the tail of the string.
            val afterStart = minOf(m.range.last + 1, text.length)

            // A zone token sits right there but we did not consume it — either we
            // don't support it, or something (markup, punctuation) separated it
            // from the time. Guessing the device zone would relabel the writer's
            // own words; decline instead.
            if (!hasTz &&
                matcherOn(TRAILING_ZONE_TOKEN, text, afterStart, text.length).lookingAt()
            ) {
                continue
            }

            // A spelled-out zone word followed by a capitalised word is a place
            // name — "Central Park", not US Central.
            if (hasTz && tzRaw!!.lowercase() in SPELLED_OUT) {
                val follower = matcherOn(SPELLED_OUT_FOLLOWER, text, afterStart, text.length)
                    .takeIf { it.lookingAt() }
                    ?.group(1)
                if (follower != null && follower.lowercase() !in ZONE_SUFFIX_WORDS) continue
            }

            val (hour24, minute) = when {
                keywordRaw == "noon" -> 12 to 0
                keywordRaw == "midnight" -> 0 to 0
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
                m.groups["mil"] != null -> {
                    if (!hasTz || (tzRaw?.length ?: 0) < 3) continue
                    val raw = m.groups["mil"]!!.value
                    raw.substring(0, 2).toInt() to raw.substring(2, 4).toInt()
                }

                else -> {
                    val hourRaw = m.groups["hour"]?.value?.toIntOrNull() ?: continue
                    val minuteRaw = m.groups["minute"]?.value?.toIntOrNull() ?: 0
                    val ampmRaw = m.groups["ampm"]?.value?.lowercase()?.replace(".", "")
                    val hasAmpm = !ampmRaw.isNullOrEmpty()
                    val hasMinute = m.groups["minute"] != null

                    // Require am/pm or a minute separator. A bare number plus a zone
                    // token is not evidence of a time — it is how people write font
                    // sizes ("12 pt font"), carats ("1 ct diamond"), scores ("won
                    // 3-1 pt"), abbreviations ("top 5 est. results") and street
                    // addresses ("5 London Road"). Every one of those was being
                    // rewritten into the middle of a real sentence. A zone alone is
                    // too weak a signal; requiring a clock-shaped time costs us
                    // "5 ET", which almost nobody writes, and buys back the class.
                    if (!hasAmpm && !hasMinute) continue

                    // A dot only separates minutes when am/pm confirms it.
                    //
                    // "5.30pm" is how much of the world writes half past five, and
                    // it silently did nothing here before. But a dot is also a
                    // decimal point, so accepting it unconditionally would make
                    // "3.50 pt" a time — the same false-positive class the rule
                    // above exists to kill, re-entering through a different door.
                    // With am/pm required, "5.30pm" converts and "3.50 pt" does not.
                    if (m.groups["sep"]?.value == "." && !hasAmpm) continue

                    var h = hourRaw
                    if (hasAmpm) {
                        if (hourRaw !in 1..12) continue
                        if (ampmRaw == "pm" && h < 12) h += 12
                        if (ampmRaw == "am" && h == 12) h = 0
                    } else if (h !in 0..23) {
                        continue
                    }
                    if (minuteRaw !in 0..59) continue
                    h to minuteRaw
                }
            }

            out += DetectedTime(
                hour = hour24,
                minute = minute,
                zone = zone,
                hadExplicitZone = hasTz,
                range = m.range,
                originalText = m.value,
            )
        }
        return out
    }

    /** Returns only the last detected time — most useful when handling a user selection. */
    fun detectLast(text: String): DetectedTime? = detect(text).lastOrNull()

    /**
     * If a stamp we rendered earlier sits immediately after [endExclusive], returns the
     * range it occupies (including the whitespace joining it to the time). The caller
     * replaces that whole span, so re-running on already-converted text refreshes the
     * stamp in place rather than nesting a new one inside it.
     */
    internal fun trailingStampRange(text: String, endExclusive: Int): IntRange? {
        var i = endExclusive
        while (i < text.length && text[i] in SPACE_CHARS) i++
        if (i >= text.length || text[i] != '(') return null
        val m = STAMP.find(text, i) ?: return null
        return if (m.range.first == i) endExclusive..m.range.last else null
    }
}
