package com.timetwister.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.timetwister.core.CountryZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Reads phone numbers from the user's contacts and groups them by inferred timezone.
 * Returns zones ordered by how many distinct contacts live in each one — so the
 * "Suggest from contacts" UI can offer the top-N as additional target zones.
 *
 * On-device only. The numbers themselves never leave the process; we only keep
 * the resulting ZoneIds. No network call, no analytics.
 */
class ContactZoneInferencer(private val context: Context) {

    data class Suggestion(val zone: ZoneId, val contactCount: Int)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Scan the Contacts provider once and return suggested zones ranked by frequency.
     * Never throws: an empty list means "nothing to suggest", and the UI shows the
     * grant / no-matches affordance instead.
     *
     * hasPermission() is a check, not a guarantee — it's TOCTOU by construction. The
     * permission can be revoked between the check and the query (user toggles it in
     * Settings while we're backgrounded, or Android auto-revokes it for an unused app),
     * which surfaces as a SecurityException from the provider. The provider can also
     * throw SQLiteException or die outright on some OEM builds. All of those used to
     * crash the settings screen, because the caller launched this in a bare coroutine.
     */
    suspend fun suggest(): List<Suggestion> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        runCatching { queryZoneCounts() }.getOrDefault(emptyList())
    }

    private fun queryZoneCounts(): List<Suggestion> {
        // One row per (contact_id, phone_number). We de-dupe by contact_id so a person
        // with three numbers in the same country only counts once toward that zone.
        val counts = mutableMapOf<ZoneId, MutableSet<Long>>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        ) ?: return emptyList()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val numCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val number = c.getString(numCol) ?: continue
                val zone = CountryZone.zoneForPhone(number) ?: continue
                val contactId = c.getLong(idCol)
                counts.getOrPut(zone) { mutableSetOf() } += contactId
            }
        }

        return counts.entries
            .map { Suggestion(it.key, it.value.size) }
            .sortedByDescending { it.contactCount }
    }
}
