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

    /**
     * Splice a rendered stamp back into the original text in place of the detected range.
     * Pure function: no Android types, no I/O — so it's covered by JVM tests in :core:test
     * and the host activity reduces to (detect → splice → return).
     *
     * If no time is detected the input is returned unchanged.
     */
    fun splice(
        input: String,
        targets: List<ZoneId>,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val detected = TimeParser.detectLast(input) ?: return input
        val stamp = renderStamp(detected, targets, now)
        return buildString {
            append(input, 0, detected.range.first)
            append(stamp)
            append(input, detected.range.last + 1, input.length)
        }
    }

    /**
     * Decide what to return from an ACTION_PROCESS_TEXT-style host:
     *   - null  → host should treat as "no change" (RESULT_CANCELED on Android)
     *   - else  → the new text to splice in
     *
     * Read-only selections and no-detection cases both produce null so the host
     * doesn't garble unmodifiable text. Pulling this out of the Activity body
     * keeps the decision logic JVM-testable (see SpliceTest); the Activity
     * becomes a thin shell that adapts intents to this call.
     */
    fun maybeSplice(
        input: String,
        targets: List<ZoneId>,
        readOnly: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String? {
        if (readOnly) return null
        if (TimeParser.detectLast(input) == null) return null
        return splice(input, targets, now)
    }

    private fun formatted(instant: ZonedDateTime, tz: ZoneId): String {
        val inZone = instant.withZoneSameInstant(tz)
        // "5pm" for whole hours, "5:30pm" otherwise.
        val pattern = if (inZone.minute == 0) "ha" else "h:mma"
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(inZone).lowercase(Locale.US)
    }
}
