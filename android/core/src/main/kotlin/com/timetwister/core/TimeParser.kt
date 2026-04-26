package com.timetwister.core

import java.time.ZoneId

/**
 * Parses human-written time expressions like "5pm CT", "17:00 ET", "5:30pm pacific".
 * Deliberately conservative: we require either am/pm OR an explicit TZ token, otherwise
 * "room 5" or "5 apples" would match and the selection action would light up on noise.
 */
object TimeParser {

    // (?xi) = COMMENTS + CASE_INSENSITIVE. Extended syntax lets us annotate the groups.
    private val pattern: Regex = Regex(
        """
        (?xi)
        \b
        (?<hour>\d{1,2})
        (?: : (?<minute>\d{2}) )?
        (?: \s* (?<ampm>am|pm|a\.m\.|p\.m\.) )?
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
        """.trimIndent()
    )

    fun detect(text: String): List<DetectedTime> {
        val defaultZone = ZoneId.systemDefault()
        val out = mutableListOf<DetectedTime>()

        for (m in pattern.findAll(text)) {
            val hourRaw = m.groups["hour"]?.value?.toIntOrNull() ?: continue
            val minuteRaw = m.groups["minute"]?.value?.toIntOrNull() ?: 0
            val ampmRaw = m.groups["ampm"]?.value?.lowercase()?.replace(".", "")
            val tzRaw = m.groups["tz"]?.value

            val hasAmpm = !ampmRaw.isNullOrEmpty()
            val hasTz = !tzRaw.isNullOrEmpty()

            // Require a disambiguator to avoid false positives.
            if (!hasAmpm && !hasTz) continue

            var hour24 = hourRaw
            if (hasAmpm) {
                if (hourRaw !in 1..12) continue
                if (ampmRaw == "pm" && hour24 < 12) hour24 += 12
                if (ampmRaw == "am" && hour24 == 12) hour24 = 0
            } else if (hour24 !in 0..23) {
                continue
            }
            if (minuteRaw !in 0..59) continue

            val zone = if (hasTz) TimeZoneAlias.resolve(tzRaw!!) ?: defaultZone else defaultZone

            out += DetectedTime(
                hour = hour24,
                minute = minuteRaw,
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
}
