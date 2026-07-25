package com.timetwister.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId

/**
 * Cross-platform user config. Stored as a tiny text file at:
 *   $HOME/.timetwister/zones.txt
 * One IANA zone id per line, in order. Deliberately plain text so the user can
 * inspect / hand-edit it without launching the app.
 */
class UserConfig(
    private val configPath: Path = defaultPath(),
) {
    private val defaults: List<ZoneId> by lazy {
        val ids = linkedSetOf(ZoneId.systemDefault().id)
        ids += listOf("America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles")
        ids.mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
    }

    fun targetZones(): List<ZoneId> {
        if (!Files.exists(configPath)) return defaults

        val lines = runCatching { Files.readAllLines(configPath) }.getOrNull() ?: return defaults

        val parsed = lines
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }

        // "Configured" vs "unset" must be distinguishable, otherwise removing the last
        // zone writes an empty file that reads back as unset and the defaults silently
        // reappear — the user's deliberate choice of "no extra zones" is unrepresentable.
        // Every write we make stamps [MARKER], so its presence means "the app wrote this,
        // trust it verbatim, even if it is empty".
        val writtenByApp = lines.any { it.trim() == MARKER }
        if (writtenByApp) return parsed

        // No marker → hand-written or legacy file. Here an empty/garbage result really is
        // ambiguous, so keep the forgiving behaviour and fall back to defaults rather than
        // leaving the user with a silently non-functional app.
        return parsed.ifEmpty { defaults }
    }

    /**
     * Persists [zones], returning failure rather than throwing.
     *
     * Called from the EDT. `createDirectories`/`writeString` throw `IOException` or
     * `AccessDeniedException` on a read-only home, a roaming profile that has not
     * synced, or an MDM-sandboxed user dir — all realistic on managed machines. An
     * uncaught throw there leaves the UI showing the new list while nothing was
     * written, so the caller must be able to tell the user it failed.
     */
    fun setTargetZones(zones: List<ZoneId>): Result<Unit> = runCatching {
        configPath.parent?.let { Files.createDirectories(it) }
        val body = buildString {
            append(MARKER).append('\n')
            zones.forEach { append(it.id).append('\n') }
        }
        Files.writeString(configPath, body)
        Unit
    }

    companion object {
        /**
         * Written as the first line of every file we save. It is a `#` comment so the
         * parser (and the user, reading the file) already ignores it, while still
         * marking the file as app-owned. See [targetZones].
         */
        internal const val MARKER = "# timetwister-config v1"

        fun defaultPath(): Path =
            Paths.get(System.getProperty("user.home"), ".timetwister", "zones.txt")
    }
}
