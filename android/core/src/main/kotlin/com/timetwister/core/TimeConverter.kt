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
     *
     * Conversions that land on a different calendar day than the source carry a
     * "+1d" / "-1d" marker. Without it "meet at 11pm PT (1am CT · 2am ET)" reads to
     * an ET recipient as 2am *tonight*, which is the single most consequential way
     * this tool can be quietly wrong.
     */
    @JvmOverloads
    fun renderStamp(
        detected: DetectedTime,
        targets: List<ZoneId>,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val source = absoluteInstant(detected, now)

        val sourceLabel = formatted(source, detected.zone)
        val sourceTag = TimeZoneAlias.shortLabel(detected.zone, source)
        val sourceDate = source.withZoneSameInstant(detected.zone).toLocalDate()
        val sourceOffset = detected.zone.rules.getOffset(source.toInstant())

        val others = targets
            .asSequence()
            .filter { it.id != detected.zone.id }
            // Two zones on the same offset at this instant render identically, so a
            // second one adds width and no information ("5pm ET (5pm America/Toronto)").
            .filter { it.rules.getOffset(source.toInstant()) != sourceOffset }
            .distinctBy { it.rules.getOffset(source.toInstant()) }
            .map { tz ->
                val label = "${formatted(source, tz)} ${TimeZoneAlias.shortLabel(tz, source)}"
                val dayShift = source.withZoneSameInstant(tz).toLocalDate().toEpochDay() -
                    sourceDate.toEpochDay()
                when {
                    dayShift > 0 -> "$label +${dayShift}d"
                    dayShift < 0 -> "$label ${dayShift}d"
                    else -> label
                }
            }
            .toList()

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
     * True when the wall-clock time the user wrote does not exist in their zone on
     * the day we resolved it — the one-hour hole a spring-forward DST transition
     * punches out of the local calendar.
     *
     * `ZonedDateTime.of` does not fail there; per its contract it silently shifts the
     * result *later* by the gap. That turned "deploy at 2:30am ET" into "deploy at
     * 3:30am ET (…)" — rewriting the user's own words to a time they did not type.
     * We would rather leave the text untouched and let a human sort it out.
     */
    internal fun isUnrepresentable(detected: DetectedTime, now: ZonedDateTime): Boolean {
        val local = LocalTime.of(detected.hour, detected.minute)
        val resolved = absoluteInstant(detected, now)
        return resolved.toLocalTime() != local
    }

    /**
     * Splice a rendered stamp back into the original text in place of the detected range.
     * Pure function: no Android types, no I/O — so it's covered by JVM tests in :core:test
     * and the host activity reduces to (detect → splice → return).
     *
     * If no time is detected the input is returned unchanged.
     */
    @JvmOverloads
    fun splice(
        input: String,
        targets: List<ZoneId>,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String {
        val detected = TimeParser.detectLast(input) ?: return input
        if (isUnrepresentable(detected, now)) return input

        val stamp = renderStamp(detected, targets, now)

        // If our own stamp already follows this time, replace it rather than nesting
        // a second one inside it. This is what makes a second pass a no-op, and it
        // also means re-running after a settings change refreshes the conversions.
        val trailing = TimeParser.trailingStampRange(input, detected.range.last + 1)
        val replaceEnd = trailing?.last ?: detected.range.last

        return buildString {
            append(input, 0, detected.range.first)
            append(stamp)
            append(input, replaceEnd + 1, input.length)
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
     *
     * Hosts that want to *show* the conversion for a read-only selection (rather
     * than silently doing nothing) should call [splice] directly — this function
     * only answers "may I rewrite the buffer".
     */
    @JvmOverloads
    fun maybeSplice(
        input: String,
        targets: List<ZoneId>,
        readOnly: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): String? {
        if (readOnly) return null
        val detected = TimeParser.detectLast(input) ?: return null
        if (isUnrepresentable(detected, now)) return null
        val spliced = splice(input, targets, now)
        // Already-stamped text splices back to itself; report "no change" so the
        // host can skip the edit entirely.
        return if (spliced == input) null else spliced
    }

    private fun formatted(instant: ZonedDateTime, tz: ZoneId): String {
        val inZone = instant.withZoneSameInstant(tz)
        // "5pm" for whole hours, "5:30pm" otherwise.
        val pattern = if (inZone.minute == 0) "ha" else "h:mma"
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(inZone).lowercase(Locale.US)
    }
}
