package com.timetwister.app

import com.timetwister.core.TimeConverter
import com.timetwister.core.TimeParser
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What ProcessTextActivity should do about one selection.
 *
 * Split out from the activity so it can be tested at all. The decision is the whole
 * feature — which of five outcomes a given selection produces — and it used to live inside
 * `onCreate`, wrapped in Toast, clipboard and Intent calls that need either Robolectric or
 * a device to touch. This file is pure: core plus java.time, no Android imports, so plain
 * JUnit reaches every branch. The activity is left holding only the platform plumbing.
 */
internal sealed interface ProcessTextDecision {

    /** Selection is longer than the parser's cap; refuse before spending the sweep. */
    data object TooLong : ProcessTextDecision

    /** No time reference found — usually a missing am/pm or zone. */
    data object NoTimeFound : ProcessTextDecision

    /** The time named does not exist on the day it resolves to (spring-forward gap). */
    data class TimeDoesNotExist(val originalText: String) : ProcessTextDecision

    /** Editable host: hand the rewritten selection back for in-place splicing. */
    data class Replace(val text: String) : ProcessTextDecision

    /**
     * Read-only host: we cannot write back, so the whole converted selection goes to the
     * clipboard while [stamp] alone is what we show — that is the part worth reading now.
     */
    data class CopyToClipboard(val text: String, val stamp: String) : ProcessTextDecision
}

internal object ProcessTextDecider {

    /**
     * @param targets deliberately a lambda, not a list. Loading the zone list can fall
     *   through to a blocking DataStore read, and an over-long selection must be refused
     *   without paying for it — the ordering the activity had, now enforced where it can
     *   be tested.
     * @param now one clock reading for every render below, so the stamp shown can never
     *   disagree with the stamp copied across a midnight boundary.
     */
    fun decide(
        input: String,
        readOnly: Boolean,
        now: ZonedDateTime,
        targets: () -> List<ZoneId>,
    ): ProcessTextDecision {
        // "Select all" on a long document is one tap away from our menu entry, and the
        // parser is a regex sweep over the whole string on the main thread.
        if (input.length > TimeParser.MAX_INPUT_CHARS) return ProcessTextDecision.TooLong

        val detected = TimeParser.detectLast(input)
            ?: return ProcessTextDecision.NoTimeFound

        // Caught here rather than left to splice: renderStamp would happily render the
        // shifted time, and the read-only branch never calls splice for what it displays.
        if (TimeConverter.isUnrepresentable(detected, now)) {
            return ProcessTextDecision.TimeDoesNotExist(detected.originalText)
        }

        val zones = targets()
        val converted = TimeConverter.splice(input, zones, now)
        return if (readOnly) {
            ProcessTextDecision.CopyToClipboard(
                text = converted,
                stamp = TimeConverter.renderStamp(detected, zones, now),
            )
        } else {
            ProcessTextDecision.Replace(converted)
        }
    }
}
