package com.timetwister.core

import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Resolves common human-written timezone names to IANA identifiers.
 * We map to IANA (e.g. America/New_York) rather than fixed offsets so
 * DST transitions are handled correctly by java.time.
 */
object TimeZoneAlias {

    /** Lowercase alias → IANA identifier. Ordered by rough messaging frequency. */
    val map: Map<String, String> = mapOf(
        // US
        "et" to "America/New_York",
        "est" to "America/New_York",
        "edt" to "America/New_York",
        "eastern" to "America/New_York",
        "ct" to "America/Chicago",
        "cst" to "America/Chicago",
        "cdt" to "America/Chicago",
        "central" to "America/Chicago",
        "mt" to "America/Denver",
        "mst" to "America/Denver",
        "mdt" to "America/Denver",
        "mountain" to "America/Denver",
        "pt" to "America/Los_Angeles",
        "pst" to "America/Los_Angeles",
        "pdt" to "America/Los_Angeles",
        "pacific" to "America/Los_Angeles",
        "akst" to "America/Anchorage",
        "akdt" to "America/Anchorage",
        "alaska" to "America/Anchorage",
        "hst" to "Pacific/Honolulu",
        "hawaii" to "Pacific/Honolulu",

        // Europe
        "utc" to "UTC",
        "gmt" to "UTC",
        "bst" to "Europe/London",
        "london" to "Europe/London",
        // "uk" and "cn" are here because we RENDER them as labels. Every label we
        // emit has to parse back to the zone it came from, or a second pass reads
        // its own output as an unzoned time and stamps it again.
        "uk" to "Europe/London",
        "cet" to "Europe/Paris",
        "cest" to "Europe/Paris",
        "central european" to "Europe/Paris",
        "eet" to "Europe/Helsinki",
        "eest" to "Europe/Helsinki",
        "eastern european" to "Europe/Helsinki",

        // Asia / Pacific
        "ist" to "Asia/Kolkata",
        "india" to "Asia/Kolkata",
        "jst" to "Asia/Tokyo",
        "tokyo" to "Asia/Tokyo",
        "kst" to "Asia/Seoul",
        "sgt" to "Asia/Singapore",
        "singapore" to "Asia/Singapore",
        "hkt" to "Asia/Hong_Kong",
        "cst_china" to "Asia/Shanghai", // intentional disambiguation; "CST" alone stays US Central
        "cn" to "Asia/Shanghai",
        "aest" to "Australia/Sydney",
        "aedt" to "Australia/Sydney",
        "sydney" to "Australia/Sydney",
        "nzst" to "Pacific/Auckland",
        "nzdt" to "Pacific/Auckland",
    )

    /**
     * Labels that are correct all year because they name a *region*, not an offset.
     * "ET" is right whether New York is on EST or EDT — which is exactly why the US
     * entries are hardcoded rather than derived.
     *
     * Zones whose conventional abbreviation is DST-specific are deliberately absent
     * (Europe/Paris, Europe/Helsinki, Australia/Sydney): a fixed "CET" or "AEST"
     * label contradicts the actual offset for half the year, and a reader who trusts
     * the abbreviation and re-derives the time lands an hour off. Those are resolved
     * against the instant instead, in [shortLabel].
     */
    private val REGION_LABELS: Map<String, String> = mapOf(
        "America/New_York" to "ET",
        "America/Chicago" to "CT",
        "America/Denver" to "MT",
        "America/Los_Angeles" to "PT",
        "Pacific/Honolulu" to "HST", // no DST here, so the abbreviation is safe year-round
        "UTC" to "UTC",
        "Europe/London" to "UK",
        "Asia/Kolkata" to "IST",
        "Asia/Tokyo" to "JST",
        "Asia/Seoul" to "KST",
        "Asia/Singapore" to "SGT",
        "Asia/Hong_Kong" to "HKT",
        "Asia/Shanghai" to "CN",
        // America/Anchorage and Pacific/Auckland are resolved against the instant
        // (AKST/AKDT, NZST/NZDT) rather than pinned: both observe DST, and both
        // abbreviations are already alias tokens, so the output stays re-readable.
    )

    /**
     * Standard/daylight abbreviations for the DST-observing zones this app
     * supports, so the label never depends on the platform's CLDR tables.
     *
     * That lookup is not portable, and the divergence is not hypothetical: the
     * Swift core asked Foundation for these and got "CEST" on iOS and an
     * offset-shaped stand-in on a Windows CI runner, so thirteen corpus rows
     * rendered differently on the two platforms. The same exposure exists here —
     * this corpus is scored on a desktop JVM, but the app runs on Android, whose
     * `zzz` resolution goes through ICU and is free to answer "GMT+02:00". If it
     * ever did, the label would silently degrade to an offset on device while
     * every test on every developer's machine stayed green.
     *
     * Every spelling here is already an alias token in [map], because these are
     * the abbreviations users write — which is also what keeps our own output
     * re-readable on a second pass. We know both halves of each pair already;
     * asking the platform for them buys nothing and costs portability.
     *
     * Zones in [REGION_LABELS] are deliberately absent: their label is DST-stable
     * by design and must not flip.
     */
    private val DST_ABBREVIATIONS: Map<String, Pair<String, String>> = mapOf(
        // id to (standard, daylight)
        "Europe/Paris" to ("CET" to "CEST"),
        "Europe/Helsinki" to ("EET" to "EEST"),
        "Australia/Sydney" to ("AEST" to "AEDT"),
        "Pacific/Auckland" to ("NZST" to "NZDT"),
        "America/Anchorage" to ("AKST" to "AKDT"),
    )

    /**
     * An explicit UTC offset, written the way we render one: "UTC+2", "GMT-5",
     * "UTC+5:30", "utc+0530".
     *
     * This exists because [offsetLabel] *emits* this shape for every zone outside
     * the tables above, and the parser has to be able to read back everything we
     * emit. Before it did, "3pm UTC+2" parsed as the zone `UTC` with a stray "+2"
     * left dangling outside the stamp, and any second pass over our own output for
     * one of the ~400 unlisted zones nested a fresh stamp inside the last one.
     *
     * It is also simply what people write. Everyone outside the handful of
     * regions with a famous abbreviation says "UTC+2", and until now that was a
     * guaranteed garble on the first pass.
     */
    private val OFFSET_TOKEN = Regex("""^(?:utc|gmt)([+-])([0-9]{1,2})(?::?([0-9]{2}))?$""")

    /**
     * True when [raw] is a zone token this app actually understands.
     *
     * Distinct from [resolve], which also accepts anything `java.time` recognises —
     * including legacy ids like "NZ" and "EST5EDT". Callers asking "did the writer
     * name a zone I support?" need this stricter answer, or an unsupported token
     * looks supported and the time gets relabelled with the system zone.
     */
    fun isKnownToken(raw: String): Boolean {
        val key = normalise(raw)
        return map.containsKey(key) || OFFSET_TOKEN.matches(key)
    }

    /** Resolve a user-written TZ token (case-insensitive) to a ZoneId. */
    fun resolve(raw: String): ZoneId? {
        // Multi-word aliases ("central european") may arrive with any run of
        // whitespace between the words, since the pattern accepts a separator there.
        val key = normalise(raw)
        map[key]?.let { iana ->
            runCatching { ZoneId.of(iana) }.getOrNull()?.let { return it }
        }
        OFFSET_TOKEN.matchEntire(key)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            val hours = m.groupValues[2].toIntOrNull() ?: return@let
            val minutes = m.groupValues[3].toIntOrNull() ?: 0
            if (hours > 18 || minutes > 59) return@let
            return runCatching {
                java.time.ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
            }.getOrNull()
        }
        // Fallback: java.time handles some abbreviations and direct IDs itself.
        return runCatching { ZoneId.of(raw) }.getOrNull()
    }

    private fun normalise(raw: String): String =
        raw.lowercase().trim().replace(Regex("\\s+"), " ")

    /**
     * Stable short label we render to users (e.g. "ET", "PT", "UTC").
     *
     * [at] is the instant being rendered. When supplied, zones outside
     * [REGION_LABELS] are labelled with the abbreviation actually in force at that
     * instant ("CEST" in July, "CET" in January). When it is absent we fall back to
     * a plain UTC offset rather than the raw IANA id — dumping "5pm America/Toronto"
     * into a chat message is not something anyone wants to send, and every Canadian,
     * Berliner, Dubliner and Australian outside the hardcoded list used to get
     * exactly that.
     */
    @JvmOverloads
    fun shortLabel(zone: ZoneId, at: ZonedDateTime? = null): String {
        REGION_LABELS[zone.id]?.let { return it }

        if (at != null) {
            DST_ABBREVIATIONS[zone.id]?.let { (standard, daylight) ->
                return if (zone.rules.isDaylightSavings(at.toInstant())) daylight else standard
            }
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
        // never wrong, and always readable back by [OFFSET_TOKEN]. That trade is
        // worth it: this label goes out in somebody's message.
        return offsetLabel(zone, at)
    }

    /** "UTC+5:30" / "UTC-4" — always meaningful, never a raw IANA id. */
    private fun offsetLabel(zone: ZoneId, at: ZonedDateTime?): String {
        val instant = at?.toInstant() ?: java.time.Instant.now()
        val offset = zone.rules.getOffset(instant)
        if (offset.totalSeconds == 0) return "UTC"
        val totalMinutes = offset.totalSeconds / 60
        val sign = if (totalMinutes < 0) "-" else "+"
        val hours = kotlin.math.abs(totalMinutes) / 60
        val minutes = kotlin.math.abs(totalMinutes) % 60
        // Padded by hand rather than with "%02d".format(): String.format uses the
        // default locale, and in a locale with non-ASCII digits (fa, ar-SA, some
        // Indic locales) that renders a label the parser cannot read back — the
        // exact round-trip failure this whole file is arranged to prevent, in a
        // form that would never show up on an en-US test machine.
        val mm = if (minutes < 10) "0$minutes" else "$minutes"
        return if (minutes == 0) "UTC$sign$hours" else "UTC$sign$hours:$mm"
    }
}
