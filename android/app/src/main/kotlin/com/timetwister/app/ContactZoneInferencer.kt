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
     * Caller is responsible for ensuring permission was granted; if it wasn't, we
     * return an empty list rather than throwing — the UI then shows the "grant"
     * affordance instead.
     */
    suspend fun suggest(): List<Suggestion> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()

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
        ) ?: return@withContext emptyList()

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

        counts.entries
            .map { Suggestion(it.key, it.value.size) }
            .sortedByDescending { it.contactCount }
    }
}
