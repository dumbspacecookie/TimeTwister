package com.timetwister.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.timetwister.app.R
import com.timetwister.core.TimeZoneAlias
import java.time.ZoneId

/**
 * One searchable timezone.
 *
 * `haystack` is a pre-lowercased bag of everything a person might plausibly type for this
 * zone: the IANA id with separators turned into spaces (so "New York" finds
 * America/New_York, which raw substring matching never did), the short label we render in
 * stamps, every TimeZoneAlias key that resolves here ("pst", "eastern", "et"), and a small
 * set of country/colloquial names. Built once per dialog and reused for every keystroke.
 */
private data class ZoneEntry(
    val zone: ZoneId,
    val id: String,
    val label: String,
    val haystack: String,
)

/**
 * Colloquial names that aren't IANA city segments and aren't TimeZoneAlias keys, because
 * TimeZoneAlias exists to parse what people *write in messages* — nobody types "meet at 5pm
 * Britain". This map is purely search sugar for the picker, which is why it lives in the UI
 * layer rather than in :core.
 */
private val SEARCH_SYNONYMS: Map<String, String> = mapOf(
    "britain" to "Europe/London",
    "great britain" to "Europe/London",
    "england" to "Europe/London",
    "scotland" to "Europe/London",
    "wales" to "Europe/London",
    "uk" to "Europe/London",
    "ireland" to "Europe/Dublin",
    "france" to "Europe/Paris",
    "germany" to "Europe/Berlin",
    "spain" to "Europe/Madrid",
    "italy" to "Europe/Rome",
    "portugal" to "Europe/Lisbon",
    "netherlands" to "Europe/Amsterdam",
    "holland" to "Europe/Amsterdam",
    "poland" to "Europe/Warsaw",
    "sweden" to "Europe/Stockholm",
    "norway" to "Europe/Oslo",
    "denmark" to "Europe/Copenhagen",
    "finland" to "Europe/Helsinki",
    "switzerland" to "Europe/Zurich",
    "greece" to "Europe/Athens",
    "turkey" to "Europe/Istanbul",
    "russia" to "Europe/Moscow",
    "israel" to "Asia/Jerusalem",
    "uae" to "Asia/Dubai",
    "china" to "Asia/Shanghai",
    "japan" to "Asia/Tokyo",
    "korea" to "Asia/Seoul",
    "taiwan" to "Asia/Taipei",
    "vietnam" to "Asia/Ho_Chi_Minh",
    "thailand" to "Asia/Bangkok",
    "philippines" to "Asia/Manila",
    "indonesia" to "Asia/Jakarta",
    "pakistan" to "Asia/Karachi",
    "bangladesh" to "Asia/Dhaka",
    "egypt" to "Africa/Cairo",
    "kenya" to "Africa/Nairobi",
    "nigeria" to "Africa/Lagos",
    "south africa" to "Africa/Johannesburg",
    "brazil" to "America/Sao_Paulo",
    "mexico" to "America/Mexico_City",
    "canada" to "America/Toronto",
    "argentina" to "America/Argentina/Buenos_Aires",
    "australia" to "Australia/Sydney",
    "new zealand" to "Pacific/Auckland",
    "east coast" to "America/New_York",
    "west coast" to "America/Los_Angeles",
)

/**
 * Curated shortlist pinned above the full ~600-entry list. Scrolling an alphabetical dump
 * of IANA ids to find "the one my colleague is in" is a research task; these twelve cover
 * the overwhelming majority of real picks. The device's own zone leads because "add my
 * current zone back" is the single most common recovery action.
 */
private val COMMON_ZONE_IDS: List<String> = buildList {
    add(ZoneId.systemDefault().id)
    addAll(
        listOf(
            "America/New_York",
            "America/Chicago",
            "America/Denver",
            "America/Los_Angeles",
            "UTC",
            "Europe/London",
            "Europe/Paris",
            "Asia/Kolkata",
            "Asia/Singapore",
            "Asia/Tokyo",
            "Australia/Sydney",
        ),
    )
}.distinct()

/**
 * Human label for a zone: "New York (ET)", "Berlin", "UTC". Used by the picker and by the
 * settings list's accessibility labels — a screen reader announcing "America slash New
 * underscore York" is technically the id and practically useless.
 */
internal fun zoneLabel(zone: ZoneId): String {
    val id = zone.id
    val city = id.substringAfterLast('/').replace('_', ' ')
    val short = TimeZoneAlias.shortLabel(zone)
    // shortLabel falls back to the raw id for zones it doesn't know, and equals the city for
    // single-segment ids like UTC — in both cases "City (LABEL)" would be noise.
    return if (short == id || short == city) city else "$city ($short)"
}

private fun buildIndex(): List<ZoneEntry> {
    // alias key(s) per IANA id, e.g. America/New_York -> "et est edt eastern"
    val aliasesById = mutableMapOf<String, MutableList<String>>()
    TimeZoneAlias.map.forEach { (alias, iana) ->
        aliasesById.getOrPut(iana) { mutableListOf() } += alias
    }
    SEARCH_SYNONYMS.forEach { (term, iana) ->
        aliasesById.getOrPut(iana) { mutableListOf() } += term
    }

    return ZoneId.getAvailableZoneIds().sorted().mapNotNull { id ->
        val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return@mapNotNull null
        val short = TimeZoneAlias.shortLabel(zone)
        val haystack = buildString {
            append(id.replace('_', ' ').replace('/', ' '))
            append(' ').append(short)
            aliasesById[id]?.forEach { append(' ').append(it) }
        }.lowercase()
        ZoneEntry(zone = zone, id = id, label = zoneLabel(zone), haystack = haystack)
    }
}

/**
 * Every whitespace-separated token must appear somewhere in the haystack. AND rather than
 * OR so "new york" doesn't drag in every zone containing "new", and token-wise rather than
 * whole-string so word order doesn't matter.
 */
private fun List<ZoneEntry>.search(query: String): List<ZoneEntry> {
    val tokens = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return this
    return filter { entry -> tokens.all { entry.haystack.contains(it) } }
}

/**
 * Full-screen zone picker.
 *
 * It was a `Dialog` until 2026-07-27, and that was the bug. A dialog gets its own window,
 * which never receives IME insets here — so `imePadding()` inside it was silently a no-op,
 * `WindowInsets.ime` stayed zero, and neither `decorFitsSystemWindows = false` nor
 * `SOFT_INPUT_ADJUST_RESIZE` on the dialog's window changed that. In landscape the dialog
 * therefore extended under the keyboard: rows were laid out at y=380..851 against a keyboard
 * top of ~390, present in the accessibility tree and completely inert when tapped, with
 * Cancel off the bottom. Shrinking the dialog instead only moved the damage around (list at
 * zero height, Cancel clipped off the edge).
 *
 * A screen in the activity's own window gets the insets the activity gets, so one
 * `imePadding()` on the Scaffold is all this needs — plus `adjustResize` in the manifest,
 * which is the documented other half of that contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZonePickerScreen(
    onDismiss: () -> Unit,
    onPick: (ZoneId) -> Unit,
) {
    // rememberSaveable: a rotation mid-search used to wipe what the user had typed.
    var query by rememberSaveable { mutableStateOf("") }

    val index = remember { buildIndex() }
    val common = remember(index) {
        // Preserve COMMON_ZONE_IDS order rather than the index's alphabetical order.
        COMMON_ZONE_IDS.mapNotNull { id -> index.firstOrNull { it.id == id } }
    }
    val filteredCommon = remember(query, common) { common.search(query) }

    // "All" excludes whatever "Common" is already showing. Without this every result
    // appeared TWICE — once per section — which for a search like "new york" meant two
    // identical-looking rows and a real "did I mis-tap?" moment. Deduping here rather than
    // dropping the sections keeps the curated shortlist doing its job while browsing.
    val filteredAll = remember(query, index, filteredCommon) {
        val shown = filteredCommon.mapTo(HashSet()) { it.id }
        index.search(query).filter { it.id !in shown }
    }
    val nothingMatched = filteredCommon.isEmpty() && filteredAll.isEmpty()

    // Back must leave the picker, not the app. As a Dialog this came free; a screen has to
    // say so. Note the keyboard eats the first back press itself, which is what makes the
    // short-viewport flow below work: back dismisses the IME, the results appear, back again
    // leaves the picker.
    BackHandler(onBack = onDismiss)

    // A landscape phone leaves ~390px above the keyboard, and a top bar plus a search field
    // is all of it -- measured, with the result list at literally zero height. So in a short
    // viewport the top bar goes and Close moves inline with the field, which costs nothing
    // vertically and buys the list a row it can actually show while you type.
    val isShort = LocalConfiguration.current.screenHeightDp < 480

    Scaffold(
        topBar = {
            if (isShort) return@Scaffold
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.picker_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    // The only way out now that there is no scrim to tap. Reachable with a
                    // switch or a screen reader, and it cannot be covered by the keyboard
                    // because the top bar is above it by construction -- which is exactly
                    // what the old footer Cancel could not promise.
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
            )
        },
        // The whole fix, in one modifier that now actually applies: the scaffold ends where
        // the keyboard begins, so the list below gets real height instead of being drawn
        // underneath it.
        modifier = Modifier.imePadding(),
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            if (isShort) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.picker_search_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.picker_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Section headers are suppressed in a short viewport. With ~150px of list
                // above a landscape keyboard, a 45px header is a third of the only row you
                // get -- it was reducing the first result to a sliver.
                if (filteredCommon.isNotEmpty()) {
                    if (!isShort) {
                        item { SectionHeader(stringResource(R.string.picker_section_common)) }
                    }
                    items(filteredCommon, key = { "common:${it.id}" }) { ZoneRow(it, onPick) }
                }
                // Header only when there is something under it. It used to print
                // unconditionally alongside Common, so a search matching only a common
                // zone showed an "All" heading with nothing beneath it.
                if (filteredAll.isNotEmpty()) {
                    if (filteredCommon.isNotEmpty() && !isShort) {
                        item { SectionHeader(stringResource(R.string.picker_section_all)) }
                    }
                    items(filteredAll, key = { "all:${it.id}" }) { ZoneRow(it, onPick) }
                }
                // Keyed off BOTH lists: this was `filteredAll.isEmpty()`, which claimed
                // "no matches" while matching common rows sat directly above it.
                if (nothingMatched) {
                    item {
                        Text(
                            text = stringResource(R.string.picker_no_matches, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun ZoneRow(entry: ZoneEntry, onPick: (ZoneId) -> Unit) {
    // Merged into one node with an explicit description: a screen reader otherwise reads a
    // bare IANA id such as "America slash Argentina slash Buenos underscore Aires".
    val description = stringResource(R.string.picker_row_desc, entry.label, entry.id)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(entry.zone) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(entry.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = entry.id,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
