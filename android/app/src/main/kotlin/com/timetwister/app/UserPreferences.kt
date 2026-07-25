package com.timetwister.app

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
 *
 * The corruption handler is load-bearing, not boilerplate: DataStore's default behaviour
 * on a truncated/garbled prefs file (force-stop during a write, dirty shutdown) is to
 * throw CorruptionException out of every read, forever. ProcessTextActivity reads this on
 * every single conversion, so one bad write would brick the app's only feature with no
 * user-visible recovery short of "clear app data". Replacing the file with empty
 * preferences costs the user their zone list once; the alternative costs them the app.
 */
private val Context.dataStore by preferencesDataStore(
    name = "timetwister",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class UserPreferences(context: Context) {

    // Hold the application context: this object outlives composition and is also built
    // from a soon-to-finish Activity in ProcessTextActivity.
    private val appContext = context.applicationContext

    private val zonesKey = stringPreferencesKey("target_zones_ordered")

    /**
     * Synchronous mirror of the zone list, plus small local UI flags.
     *
     * Why a second store at all: ProcessTextActivity is a cold-started, invisible activity
     * on the main thread. A DataStore read there means disk I/O inside runBlocking during
     * app startup — a real ANR risk on a slow device with a cold page cache. SharedPreferences
     * loads its whole file once into memory and answers subsequent reads without touching
     * disk, which is exactly the shape we need. DataStore stays the source of truth; this is
     * a write-through cache that setTargetZones keeps in lockstep.
     */
    private val local = appContext.getSharedPreferences(LOCAL_FILE, Context.MODE_PRIVATE)

    fun targetZonesFlow(): Flow<List<ZoneId>> = appContext.dataStore.data.map { prefs ->
        decode(prefs[zonesKey])
    }

    suspend fun targetZones(): List<ZoneId> = targetZonesFlow().first()

    suspend fun setTargetZones(zones: List<ZoneId>) {
        val encoded = encode(zones)
        appContext.dataStore.edit { prefs -> prefs[zonesKey] = encoded }
        // Write-through, after the durable write succeeded, so the cache can never claim a
        // list that DataStore doesn't have.
        local.edit { putString(KEY_ZONES, encoded) }
    }

    /**
     * Fast path for ProcessTextActivity. Returns null on a genuine cache miss (first run
     * after install, or after the user cleared data) so the caller can decide to pay for
     * the DataStore read; an empty list is a real answer, not a miss.
     */
    fun cachedTargetZones(): List<ZoneId>? =
        if (!local.contains(KEY_ZONES)) null else decode(local.getString(KEY_ZONES, null))

    /** Populate the cache after a fallback read, so we only pay the slow path once. */
    fun cacheTargetZones(zones: List<ZoneId>) {
        local.edit { putString(KEY_ZONES, encode(zones)) }
    }

    /**
     * First-run onboarding flag. Deliberately in SharedPreferences rather than DataStore:
     * it's read during composition of the very first frame, where a suspending read would
     * mean flashing the settings screen and then covering it with the tutorial.
     */
    fun hasSeenOnboarding(): Boolean = local.getBoolean(KEY_ONBOARDED, false)

    fun markOnboardingSeen() {
        local.edit { putBoolean(KEY_ONBOARDED, true) }
    }

    private fun encode(zones: List<ZoneId>): String = zones.joinToString(",") { it.id }

    /**
     * Three distinct states, which the previous implementation collapsed into two:
     *   null  → never configured           → the defaults
     *   ""    → user removed every zone    → an empty list (honour it)
     *   "a,b" → configured                 → those zones
     *
     * Collapsing "" into "unset" meant deleting your last zone silently resurrected all
     * five defaults, which reads as the app ignoring you.
     */
    private fun decode(raw: String?): List<ZoneId> {
        if (raw == null) return defaults
        return raw.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
    }

    companion object {
        private const val LOCAL_FILE = "timetwister_local"
        private const val KEY_ZONES = "target_zones_ordered_cache"
        private const val KEY_ONBOARDED = "onboarding_seen"

        /**
         * Soft ceiling on target zones. Beyond this the stamp stops being readable inline
         * ("5pm CT (6pm ET · 3pm PT · 11pm CET · 7:30am IST · …)"), so the UI warns rather
         * than blocks — some people genuinely do coordinate across five zones.
         */
        const val MAX_RECOMMENDED_ZONES = 4

        /**
         * Also the fallback for any failed read (see ProcessTextActivity): converting with
         * the default zones is strictly better than crashing.
         */
        val defaults: List<ZoneId> by lazy {
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
    }
}
