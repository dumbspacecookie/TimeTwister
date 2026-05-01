package com.timetwister.core

import java.time.ZoneId

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
        "cet" to "Europe/Paris",
        "cest" to "Europe/Paris",
        "eet" to "Europe/Helsinki",
        "eest" to "Europe/Helsinki",

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
        "aest" to "Australia/Sydney",
        "aedt" to "Australia/Sydney",
        "sydney" to "Australia/Sydney",
        "nzst" to "Pacific/Auckland",
        "nzdt" to "Pacific/Auckland",
    )

    /** Resolve a user-written TZ token (case-insensitive) to a ZoneId. */
    fun resolve(raw: String): ZoneId? {
        val key = raw.lowercase().trim()
        map[key]?.let { iana ->
            runCatching { ZoneId.of(iana) }.getOrNull()?.let { return it }
        }
        // Fallback: java.time handles some abbreviations and direct IDs itself.
        return runCatching { ZoneId.of(raw) }.getOrNull()
    }

    /** Stable short label we render to users (e.g. "ET", "PT", "UTC"). */
    fun shortLabel(zone: ZoneId): String = when (zone.id) {
        "America/New_York" -> "ET"
        "America/Chicago" -> "CT"
        "America/Denver" -> "MT"
        "America/Los_Angeles" -> "PT"
        "America/Anchorage" -> "AK"
        "Pacific/Honolulu" -> "HI"
        "UTC" -> "UTC"
        "Europe/London" -> "UK"
        "Europe/Paris" -> "CET"
        "Europe/Helsinki" -> "EET"
        "Asia/Kolkata" -> "IST"
        "Asia/Tokyo" -> "JST"
        "Asia/Seoul" -> "KST"
        "Asia/Singapore" -> "SGT"
        "Asia/Hong_Kong" -> "HKT"
        "Asia/Shanghai" -> "CN"
        "Australia/Sydney" -> "AEST"
        "Pacific/Auckland" -> "NZ"
        else -> zone.id
    }
}
