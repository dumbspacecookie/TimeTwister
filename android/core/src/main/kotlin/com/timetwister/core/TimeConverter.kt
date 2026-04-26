package com.timetwister.core

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeConverter {

    /**
     * Build a stamp string for a detected time, rendered into each target zone.
     * The source zone is always first so the reader sees the sender's original time
     * with conversions adjacent.
     *
     * Example: for "5pm CT" with targets = [ET, PT]
     *   → "5pm CT (6pm ET · 3pm PT)"
     */
    fun renderStamp(
        detected: DetectedTime,
        targets: List<ZoneId>,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val source = absoluteInstant(detected, now)

        val sourceLabel = formatted(source, detected.zone)
        val sourceTag = TimeZoneAlias.shortLabel(detected.zone)

        val others = targets
            .filter { it.id != detected.zone.id }
            .map { tz -> "${formatted(source, tz)} ${TimeZoneAlias.shortLabel(tz)}" }

        return if (others.isEmpty()) "$sourceLabel $sourceTag"
        else "$sourceLabel $sourceTag (${others.joinToString(" · ")})"
    }

    /**
     * Produce the actual instant the user meant. We assume "today" in the source zone,
     * and if that instant is already in the past, roll forward one day so suggestions
     * stay useful in evening chats.
     */
    internal fun absoluteInstant(detected: DetectedTime, now: ZonedDateTime): ZonedDateTime {
        val todayInSource = now.withZoneSameInstant(detected.zone).toLocalDate()
        val candidate = ZonedDateTime.of(
            todayInSource,
            LocalTime.of(detected.hour, detected.minute),
            detected.zone,
        )
        return if (candidate.isBefore(now.minusMinutes(30))) candidate.plusDays(1) else candidate
    }

    private fun formatted(instant: ZonedDateTime, tz: ZoneId): String {
        val inZone = instant.withZoneSameInstant(tz)
        // "5pm" for whole hours, "5:30pm" otherwise.
        val pattern = if (inZone.minute == 0) "ha" else "h:mma"
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(inZone).lowercase(Locale.US)
    }
}
