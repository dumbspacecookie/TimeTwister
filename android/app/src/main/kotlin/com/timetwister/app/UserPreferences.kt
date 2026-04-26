package com.timetwister.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/**
 * Persistent target-zone list. Backed by Preferences DataStore because SharedPreferences
 * doesn't preserve insertion order on Sets, and zone order matters for our stamp.
 * We serialize the ordered list as a comma-separated IANA ID string.
 */
private val Context.dataStore by preferencesDataStore(name = "timetwister")

class UserPreferences(private val context: Context) {

    private val zonesKey = stringPreferencesKey("target_zones_ordered")

    private val defaults: List<ZoneId> by lazy {
        // User's own zone plus the four US zones, deduped, order-preserving.
        val ids = linkedSetOf(ZoneId.systemDefault().id)
        ids += listOf(
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
        )
        ids.mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
    }

    fun targetZonesFlow(): Flow<List<ZoneId>> = context.dataStore.data.map { prefs ->
        val raw = prefs[zonesKey]
        if (raw.isNullOrBlank()) defaults
        else raw.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
            .ifEmpty { defaults }
    }

    suspend fun targetZones(): List<ZoneId> = targetZonesFlow().first()

    suspend fun setTargetZones(zones: List<ZoneId>) {
        context.dataStore.edit { prefs ->
            prefs[zonesKey] = zones.joinToString(",") { it.id }
        }
    }
}
