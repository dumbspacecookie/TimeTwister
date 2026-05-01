package com.timetwister.core

import java.time.ZoneId

/**
 * Parses human-written time expressions like "5pm CT", "17:00 ET", "5:30pm pacific".
 * Deliberately conservative: we require either am/pm OR an explicit TZ token, otherwise
 * "room 5" or "5 apples" would match and the selection action would light up on noise.
 */
object TimeParser {

    // (?xi) = COMMENTS + CASE_INSENSITIVE. Extended syntax lets us annotate the groups.
    // Either a numeric time (with am/pm or TZ) or the keywords noon/midnight (which
    // self-disambiguate — no am/pm needed).
    private val pattern: Regex = Regex(
        """
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
        """.trimIndent()
    )

    fun detect(text: String): List<DetectedTime> {
        val defaultZone = ZoneId.systemDefault()
        val out = mutableListOf<DetectedTime>()

        for (m in pattern.findAll(text)) {
            val keywordRaw = m.groups["keyword"]?.value?.lowercase()
            val tzRaw = m.groups["tz"]?.value
            val hasTz = !tzRaw.isNullOrEmpty()
            val zone = if (hasTz) TimeZoneAlias.resolve(tzRaw!!) ?: defaultZone else defaultZone

            val (hour24, minute) = when (keywordRaw) {
                "noon" -> 12 to 0
                "midnight" -> 0 to 0
                else -> {
                    val hourRaw = m.groups["hour"]?.value?.toIntOrNull() ?: continue
                    val minuteRaw = m.groups["minute"]?.value?.toIntOrNull() ?: 0
                    val ampmRaw = m.groups["ampm"]?.value?.lowercase()?.replace(".", "")
                    val hasAmpm = !ampmRaw.isNullOrEmpty()

                    // Require a disambiguator to avoid false positives.
                    if (!hasAmpm && !hasTz) continue

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
}
