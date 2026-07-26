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
     * a write-through cache that mutateTargetZones keeps in lockstep.
     */
    private val local = appContext.getSharedPreferences(LOCAL_FILE, Context.MODE_PRIVATE)

    /**
     * ONE Flow instance for the life of this object, deliberately — do not inline this
     * back into the function.
     *
     * `dataStore.data.map { … }` allocates a new Flow on every call. Compose's
     * `collectAsState` keys its `LaunchedEffect` on flow *identity*, so calling this from
     * inside a composable body made every recomposition cancel the DataStore collection
     * and start a fresh one. Adding a zone recomposes (the picker closes) at the same
     * moment the write emits, so the emission could land on the collector being torn
     * down: the value persisted to both stores and the screen kept showing the old list,
     * about one time in four. Reads to the user as "Add timezone does nothing".
     *
     * Sharing one instance is safe because this is a *cold* flow holding no state — every
     * collector still gets its own independent read. `by lazy` rather than an eager field
     * so that ProcessTextActivity's fast path, which only wants the SharedPreferences
     * mirror, still never touches DataStore at all.
     */
    private val zonesFlow: Flow<List<ZoneId>> by lazy {
        appContext.dataStore.data.map { prefs -> decode(prefs[zonesKey]) }
    }

    fun targetZonesFlow(): Flow<List<ZoneId>> = zonesFlow

    suspend fun targetZones(): List<ZoneId> = targetZonesFlow().first()

    /** Add a zone, ignoring a duplicate. See [mutateTargetZones] for why this exists. */
    suspend fun addTargetZone(zone: ZoneId) = mutateTargetZones { current ->
        if (current.any { it.id == zone.id }) current else current + zone
    }

    /** Remove a zone by IANA id. */
    suspend fun removeTargetZone(zone: ZoneId) = mutateTargetZones { current ->
        current.filter { it.id != zone.id }
    }

    /**
     * Read-modify-write *inside* DataStore's transaction, which is why the callers say
     * "add this zone" rather than handing over a whole list.
     *
     * Every call site is a Compose lambda that had captured the zone list from its own
     * composition and passed `zones + newZone`. Two adds in quick succession — entirely
     * ordinary in the suggestions dialog, which lists several zones with an Add button
     * each — could both be built from the same pre-add snapshot, and the second write
     * would silently drop the first zone. `edit` serialises transforms, so composing from
     * the stored value instead makes the update order-independent.
     */
    private suspend fun mutateTargetZones(transform: (List<ZoneId>) -> List<ZoneId>) {
        val updated = appContext.dataStore.edit { prefs ->
            prefs[zonesKey] = encode(transform(decode(prefs[zonesKey])))
        }
        // Write-through, after the durable write succeeded, so the cache can never claim a
        // list that DataStore doesn't have.
        local.edit { putString(KEY_ZONES, updated[zonesKey].orEmpty()) }
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

        /** Geographic east→west, which is the order the stamp reads best in. */
        private val US_ZONES = listOf(
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
        )

        /**
         * Which defaults may be given up, first to go at the front, when the user's own
         * zone is not already one of the four. Mountain goes first: it is the least
         * populous US zone, and the people most likely to want it — anyone in Denver or
         * Phoenix — get a Mountain reading from their own zone regardless.
         */
        private val DROPPABLE_DEFAULTS = listOf("America/Denver", "America/Chicago")

        /**
         * Also the fallback for any failed read (see ProcessTextActivity): converting with
         * the default zones is strictly better than crashing.
         */
        val defaults: List<ZoneId> by lazy { computeDefaults(ZoneId.systemDefault()) }

        /**
         * Split out from [defaults] so it can be tested at a zone other than the one the
         * test JVM happens to be running in — `defaults` is a process-wide `lazy` read of
         * `ZoneId.systemDefault()` and can only ever be observed once.
         *
         * The trim is a fix, not tidiness: "your zone plus the four US zones" is *five*
         * entries for everyone outside the US — and for Phoenix, Anchorage and Honolulu
         * too, which is the part that made this easy to miss from a US desk. Five is over
         * MAX_RECOMMENDED_ZONES, so a fresh install opened its settings screen already
         * showing the red "too many zones" warning, scolding the user about a list they
         * had never touched.
         */
        internal fun computeDefaults(systemZone: ZoneId): List<ZoneId> {
            // User's own zone first, then the US zones, deduped, order-preserving.
            val ids = linkedSetOf(systemZone.id).apply { addAll(US_ZONES) }.toMutableList()
            for (droppable in DROPPABLE_DEFAULTS) {
                if (ids.size <= MAX_RECOMMENDED_ZONES) break
                // Never drop the zone the user is actually in.
                if (droppable != systemZone.id) ids.remove(droppable)
            }
            return ids.mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
        }

        internal fun encode(zones: List<ZoneId>): String = zones.joinToString(",") { it.id }

        /**
         * Three distinct states, which the original implementation collapsed into two:
         *   null  → never configured           → the defaults
         *   ""    → user removed every zone    → an empty list (honour it)
         *   "a,b" → configured                 → those zones
         *
         * Collapsing "" into "unset" meant deleting your last zone silently resurrected all
         * the defaults, which reads as the app ignoring you.
         */
        internal fun decode(raw: String?): List<ZoneId> {
            if (raw == null) return defaults
            return raw.split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
        }
    }
}
