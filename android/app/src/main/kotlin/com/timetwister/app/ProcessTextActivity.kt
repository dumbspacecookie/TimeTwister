package com.timetwister.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.timetwister.core.TimeConverter
import kotlinx.coroutines.runBlocking
import java.time.ZonedDateTime

/**
 * Receives android.intent.action.PROCESS_TEXT when the user long-presses text in any
 * app and picks "TimeTwister" from the selection toolbar. We detect the last time
 * reference in the selection, render a multi-TZ stamp, and hand the modified text
 * back to the host app — which splices it into the selection in place.
 *
 * Theme.NoDisplay keeps the activity invisible; we never draw UI, just transform + finish.
 *
 * If the host is read-only (a received message the user is trying to convert
 * for reference, say) we can't write back — but we still return RESULT_CANCELED
 * so the host doesn't silently garble the selection.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val input = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) ?: false

        // DataStore is async; we block here because the activity lifecycle is about to end
        // anyway. Worst case the user waits a few ms for first read.
        val targets = runBlocking { UserPreferences(applicationContext).targetZones() }
        val out = TimeConverter.maybeSplice(input, targets, readOnly, ZonedDateTime.now())

        if (out == null) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, out))
        }
        finish()
    }
}
