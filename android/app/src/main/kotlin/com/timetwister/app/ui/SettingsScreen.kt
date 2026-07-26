package com.timetwister.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.timetwister.app.ContactZoneInferencer
import com.timetwister.app.R
import com.timetwister.app.UserPreferences
import com.timetwister.core.TimeConverter
import com.timetwister.core.TimeParser
import com.timetwister.core.TimeZoneAlias
import kotlinx.coroutines.launch
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { UserPreferences(ctx) }
    val scope = rememberCoroutineScope()

    // null = still loading. Distinguishing "not read yet" from "read, and it's empty"
    // matters now that an empty zone list is a legitimate, persisted state.
    //
    // The `remember` is load-bearing. collectAsState keys its collection on the flow
    // object, so obtaining the flow inline restarted the DataStore subscription on every
    // recomposition — and closing the picker after an add is a recomposition, which is how
    // a successful write could fail to reach the screen. UserPreferences also caches the
    // flow now; both ends are pinned because either one alone silently fixes the other.
    val zonesFlow = remember(prefs) { prefs.targetZonesFlow() }
    val loadedZones by zonesFlow.collectAsState(initial = null)
    val zones = loadedZones.orEmpty()

    // Keep the synchronous mirror ProcessTextActivity reads in step with DataStore, even
    // when the list was never edited on this device (fresh install using the defaults).
    LaunchedEffect(loadedZones) {
        loadedZones?.let { prefs.cacheTargetZones(it) }
    }

    // rememberSaveable throughout: rotating used to close an open picker and discard state.
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var suggestionsOpen by rememberSaveable { mutableStateOf(false) }
    var suggestionsEmpty by rememberSaveable { mutableStateOf(false) }
    var rationaleOpen by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    // Shown BEFORE the system permission dialog, never after. Play's Prominent Disclosure
    // requirement is specifically about the moment before the request: the user has to be
    // told what personal data is accessed and why while they can still decline without
    // spending their one-and-only "ask again" chance. The existing `rationaleOpen` dialog
    // only ever appeared *after* a denial, so nothing explained contacts access up front.
    var disclosureOpen by rememberSaveable { mutableStateOf(false) }

    // Suggestions themselves aren't Saveable (ZoneId isn't parcelable), so they stay in
    // remember — a rotation just re-runs the scan, which is cheap and permission-gated.
    var suggestions by remember { mutableStateOf<List<ContactZoneInferencer.Suggestion>>(emptyList()) }
    val inferencer = remember { ContactZoneInferencer(ctx) }

    fun loadSuggestions() {
        scope.launch {
            // suggest() swallows provider failures itself; an empty result is the only
            // failure mode the UI has to explain.
            val result = inferencer.suggest()
            suggestions = result
            suggestionsEmpty = result.isEmpty()
            suggestionsOpen = result.isNotEmpty()
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            loadSuggestions()
        } else {
            // Inside the deny callback, shouldShowRequestPermissionRationale() == false means
            // "Android will never show this dialog again" — the state in which the button was
            // previously, and silently, dead forever.
            val activity = ctx.findActivity()
            permanentlyDenied = activity == null ||
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)
            rationaleOpen = true
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HowToUseCard()

            HorizontalDivider()

            // Preview sits ABOVE the zone list on purpose: it's the only place the effect of
            // adding a seventh zone is visible, and it should be visible while you add it,
            // not after you scroll back up.
            SectionHeading(stringResource(R.string.section_preview))
            InteractivePreview(zones = zones)

            HorizontalDivider()

            SectionHeading(stringResource(R.string.section_your_timezones))

            zones.forEach { tz ->
                ListItem(
                    headlineContent = {
                        Text(
                            TimeZoneAlias.shortLabel(tz),
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    supportingContent = { Text(tz.id) },
                    trailingContent = {
                        IconButton(onClick = {
                            scope.launch { prefs.removeTargetZone(tz) }
                        }) {
                            Icon(
                                Icons.Default.Close,
                                // Friendly label, not the raw id: TalkBack used to announce
                                // "Remove America slash New underscore York".
                                contentDescription = stringResource(
                                    R.string.remove_zone_desc,
                                    zoneLabel(tz),
                                ),
                            )
                        }
                    },
                )
            }

            if (loadedZones != null && zones.isEmpty()) {
                // An empty list is now honoured rather than silently replaced by the
                // defaults, so it needs saying out loud.
                Text(
                    text = stringResource(R.string.zones_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (zones.size > UserPreferences.MAX_RECOMMENDED_ZONES) {
                // Soft cap: some people really do coordinate across six zones, so warn and
                // let them through rather than refusing the add.
                Text(
                    text = stringResource(
                        R.string.zones_too_many,
                        zones.size,
                        UserPreferences.MAX_RECOMMENDED_ZONES,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(onClick = { pickerOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_add_timezone))
            }

            OutlinedButton(onClick = {
                // Already granted → straight to work; otherwise disclose first and let the
                // disclosure dialog be the thing that launches the system prompt.
                if (inferencer.hasPermission()) loadSuggestions() else disclosureOpen = true
            }) {
                Icon(Icons.Default.People, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_suggest_from_contacts))
            }

            Text(
                text = stringResource(R.string.contacts_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pickerOpen) {
        TimeZonePickerDialog(
            onDismiss = { pickerOpen = false },
            onPick = { z ->
                // No pre-check against `zones` any more: addTargetZone dedupes inside the
                // DataStore transaction, which is also correct when this lambda is holding
                // a list from a composition that has already been superseded.
                scope.launch { prefs.addTargetZone(z) }
                pickerOpen = false
            },
        )
    }

    if (suggestionsOpen) {
        AlertDialog(
            onDismissRequest = { suggestionsOpen = false },
            title = { Text(stringResource(R.string.suggestions_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.suggestions_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestions.forEach { s ->
                        val alreadyAdded = zones.any { it.id == s.zone.id }
                        ListItem(
                            headlineContent = { Text(zoneLabel(s.zone)) },
                            supportingContent = {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contact_count,
                                        s.contactCount,
                                        s.zone.id,
                                        s.contactCount,
                                    ),
                                )
                            },
                            trailingContent = {
                                if (alreadyAdded) {
                                    Text(
                                        stringResource(R.string.label_added),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                } else {
                                    TextButton(onClick = {
                                        scope.launch { prefs.addTargetZone(s.zone) }
                                    }) { Text(stringResource(R.string.action_add)) }
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { suggestionsOpen = false }) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }

    if (suggestionsEmpty) {
        AlertDialog(
            onDismissRequest = { suggestionsEmpty = false },
            title = { Text(stringResource(R.string.suggestions_none_title)) },
            text = { Text(stringResource(R.string.suggestions_none_body)) },
            confirmButton = {
                TextButton(onClick = { suggestionsEmpty = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    if (disclosureOpen) {
        AlertDialog(
            onDismissRequest = { disclosureOpen = false },
            title = { Text(stringResource(R.string.disclosure_title)) },
            text = { Text(stringResource(R.string.disclosure_body)) },
            confirmButton = {
                TextButton(onClick = {
                    disclosureOpen = false
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = {
                // A real way out that costs nothing. Dismissing here never reaches the
                // system dialog, so it does not burn the user's single re-ask.
                TextButton(onClick = { disclosureOpen = false }) {
                    Text(stringResource(R.string.action_not_now))
                }
            },
        )
    }

    if (rationaleOpen) {
        AlertDialog(
            onDismissRequest = { rationaleOpen = false },
            title = { Text(stringResource(R.string.permission_title)) },
            text = {
                Text(
                    stringResource(
                        if (permanentlyDenied) R.string.permission_blocked
                        else R.string.permission_rationale,
                    ),
                )
            },
            confirmButton = {
                if (permanentlyDenied) {
                    // The only route back once Android has stopped asking.
                    TextButton(onClick = {
                        rationaleOpen = false
                        ctx.openAppSettings()
                    }) { Text(stringResource(R.string.action_open_settings)) }
                } else {
                    TextButton(onClick = {
                        rationaleOpen = false
                        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }) { Text(stringResource(R.string.action_ok)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { rationaleOpen = false }) {
                    Text(stringResource(R.string.action_not_now))
                }
            },
        )
    }
}

/**
 * Live preview of the actual splice.
 *
 * This was hardcoded to "5pm CT", which made it a screenshot rather than a tool. Editable,
 * it becomes the place a user who just failed in WhatsApp can find out whether the problem
 * is the app or their phrasing — and it teaches the parser's requirements (am/pm or a zone
 * token) faster than any help text.
 */
@Composable
private fun InteractivePreview(zones: List<ZoneId>) {
    val default = stringResource(R.string.preview_default)
    var input by rememberSaveable { mutableStateOf(default) }

    // Runs the real parser and the real splice — not a mock — so what's shown here is
    // exactly what the selection action would produce.
    val output = remember(input, zones) {
        if (TimeParser.detectLast(input) == null) null
        else runCatching { TimeConverter.splice(input, zones) }.getOrNull()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.preview_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (output == null) {
            Text(
                text = stringResource(R.string.preview_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = output,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(R.string.preview_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        // Lets screen-reader users jump between sections instead of swiping through
        // every zone row to reach the next one.
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * Compose's LocalContext isn't guaranteed to be the Activity (it can be a themed wrapper),
 * and shouldShowRequestPermissionRationale is an Activity-only API.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // A missing settings activity (heavily modified OEM ROM) shouldn't crash the app.
    runCatching { startActivity(intent) }
}
