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
        val parsed = runCatching {
            Files.readAllLines(configPath)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
        }.getOrNull().orEmpty()
        return parsed.ifEmpty { defaults }
    }

    fun setTargetZones(zones: List<ZoneId>) {
        Files.createDirectories(configPath.parent)
        Files.writeString(configPath, zones.joinToString("\n") { it.id } + "\n")
    }

    companion object {
        fun defaultPath(): Path =
            Paths.get(System.getProperty("user.home"), ".timetwister", "zones.txt")
    }
}
