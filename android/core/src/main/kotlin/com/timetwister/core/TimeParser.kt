package com.timetwister.core

import java.time.ZoneId

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
    private const val TZ_TOKENS = """
              central$SP+european | eastern$SP+european
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
            (?<hour>\d{1,2})
            (?: : (?<minute>\d{2}) )?
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
     * A zone-looking token we do NOT support (NPT, ACST, MSK, WAT…). When one of
     * these follows a time we must stay out of the way: the user clearly named a
     * zone, so falling back to the system zone would relabel their time as
     * something else entirely ("call 7pm NPT" → "call 7pm ET (…) NPT").
     */
    private val UNKNOWN_ZONE_TOKEN = Regex("""^$SP*([A-Z]{2,5})\b""")

    /**
     * A stamp is only ever emitted directly after the source time, so "(6pm PT)" on
     * its own is a user's parenthesised time, not our output — even though it is
     * shaped exactly like a single-conversion stamp. Requiring a time immediately
     * before the bracket keeps us from ignoring text we have never touched.
     */
    private val TIME_BEFORE_STAMP =
        Regex("""\d{1,2}(?::\d{2})?(?:am|pm)$SP+[A-Za-z][A-Za-z0-9+:/_-]*$SP*$""", RegexOption.IGNORE_CASE)

    /** Ranges occupied by stamps this tool rendered earlier. */
    private fun stampRanges(text: String): List<IntRange> =
        STAMP.findAll(text)
            .filter { TIME_BEFORE_STAMP.containsMatchIn(text.substring(0, it.range.first)) }
            .map { it.range }
            .toList()

    fun detect(text: String): List<DetectedTime> {
        val defaultZone = ZoneId.systemDefault()
        val out = mutableListOf<DetectedTime>()
        val stamps = stampRanges(text)

        for (m in pattern.findAll(text)) {
            // Never re-read our own output.
            if (stamps.any { m.range.first >= it.first && m.range.first <= it.last }) continue

            // A match glued to the right of a digit or a colon is a fragment of a
            // larger number, not a time: "9:5 am" would otherwise yield "5 am".
            val before = text.getOrNull(m.range.first - 1)
            if (before != null && (before.isDigit() || before == ':' || before == '.')) continue

            val keywordRaw = m.groups["keyword"]?.value?.lowercase()
            val tzRaw = m.groups["tz"]?.value
                ?: m.groups["tzparen"]?.value
                ?: m.groups["tzbracket"]?.value
            val hasTz = !tzRaw.isNullOrEmpty()
            val zone = if (hasTz) TimeZoneAlias.resolve(tzRaw!!) ?: defaultZone else defaultZone

            // The writer named a zone we don't know — decline rather than guess.
            if (!hasTz) {
                val after = text.substring(minOf(m.range.last + 1, text.length))
                val token = UNKNOWN_ZONE_TOKEN.find(after)?.groupValues?.get(1)
                if (token != null && !TimeZoneAlias.isKnownToken(token)) continue
            }

            val (hour24, minute) = when (keywordRaw) {
                "noon" -> 12 to 0
                "midnight" -> 0 to 0
                else -> {
                    val hourRaw = m.groups["hour"]?.value?.toIntOrNull() ?: continue
                    val minuteRaw = m.groups["minute"]?.value?.toIntOrNull() ?: 0
                    val ampmRaw = m.groups["ampm"]?.value?.lowercase()?.replace(".", "")
                    val hasAmpm = !ampmRaw.isNullOrEmpty()
                    val hasColon = m.groups["minute"] != null

                    // Require am/pm or a colon. A bare number plus a zone token is
                    // not evidence of a time — it is how people write font sizes
                    // ("12 pt font"), carats ("1 ct diamond"), scores ("won 3-1 pt"),
                    // abbreviations ("top 5 est. results") and street addresses
                    // ("5 London Road"). Every one of those was being rewritten into
                    // the middle of a real sentence. A zone alone is too weak a
                    // signal; requiring a clock-shaped time costs us "5 ET", which
                    // almost nobody writes, and buys back the entire class.
                    if (!hasAmpm && !hasColon) continue

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
