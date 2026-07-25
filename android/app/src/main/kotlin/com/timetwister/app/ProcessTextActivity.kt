package com.timetwister.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.timetwister.core.TimeConverter
import com.timetwister.core.TimeParser
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Receives android.intent.action.PROCESS_TEXT when the user long-presses text in any
 * app and picks "TimeTwister" from the selection toolbar. We detect the last time
 * reference in the selection, render a multi-TZ stamp, and hand the modified text
 * back to the host app — which splices it into the selection in place.
 *
 * The activity is translucent rather than Theme.NoDisplay: NoDisplay structurally forbids
 * drawing anything, including a Toast, which meant both failure modes (no time found, and
 * a read-only host) were indistinguishable from "the app is broken". Every path through
 * onCreate now tells the user something before finishing.
 *
 * Read-only selections — someone else's message, the single most intuitive thing to try
 * first — are handled rather than dropped: we can't write back into text we don't own, so
 * we convert it, put the result on the clipboard, and show the stamp. RESULT_CANCELED is
 * still returned so the host text stays untouched.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val input = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) == true

        setResult(RESULT_CANCELED)

        // "Select all" on a long document is one tap away from our menu entry, and the
        // parser is a regex sweep over the whole string on the main thread. Refuse
        // absurd inputs instead of stalling the UI thread of whatever app invoked us.
        if (input.length > MAX_INPUT_CHARS) {
            toast(getString(R.string.toast_selection_too_long), Toast.LENGTH_SHORT)
            finish()
            return
        }

        val targets = loadTargets()
        val detected = TimeParser.detectLast(input)
        // One clock reading for every render below, so the stamp we show can't disagree with
        // the stamp we copy across a midnight boundary.
        val now = ZonedDateTime.now()

        when {
            detected == null ->
                // Specific, not generic: the failure is almost always "the parser needs a
                // disambiguator", so show what a parseable selection looks like.
                toast(getString(R.string.toast_no_time_found), Toast.LENGTH_LONG)

            readOnly -> {
                val stamp = TimeConverter.renderStamp(detected, targets, now)
                // Clipboard gets the whole converted selection, not just the stamp: the user
                // selected a sentence, so pasting back a sentence is what they meant. The
                // Toast shows the stamp alone because that's the part they want to read now.
                copyToClipboard(TimeConverter.splice(input, targets, now))
                toast(getString(R.string.toast_readonly_copied, stamp), Toast.LENGTH_LONG)
            }

            else -> {
                val out = TimeConverter.splice(input, targets, now)
                setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, out))
            }
        }

        finish()
    }

    /**
     * Zone list without blocking on disk if we can help it. The SharedPreferences mirror
     * answers from memory; DataStore is only consulted on a genuine cache miss (first
     * conversion after install), and we then seed the cache so it stays a one-time cost.
     *
     * The runCatching is the second half of the corruption fix in UserPreferences: even
     * with a corruption handler installed, any read failure here would otherwise propagate
     * out of onCreate and crash the host's selection action. Defaults are a fine answer.
     */
    private fun loadTargets(): List<ZoneId> {
        val prefs = UserPreferences(applicationContext)
        prefs.cachedTargetZones()?.let { return it }
        return runCatching {
            runBlocking { prefs.targetZones() }.also { prefs.cacheTargetZones(it) }
        }.getOrDefault(UserPreferences.defaults)
    }

    private fun copyToClipboard(text: String) {
        // Best-effort: a missing/failing clipboard service must not take down the Toast path.
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        }
    }

    private fun toast(message: String, length: Int) {
        Toast.makeText(applicationContext, message, length).show()
    }

    private companion object {
        /** Roughly a page of text; anything larger is a "select all", not a time reference. */
        const val MAX_INPUT_CHARS = 5000
    }
}
