package com.timetwister.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.timetwister.app.ContactZoneInferencer
import com.timetwister.app.UserPreferences
import com.timetwister.core.DetectedTime
import com.timetwister.core.TimeConverter
import com.timetwister.core.TimeZoneAlias
import kotlinx.coroutines.launch
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { UserPreferences(ctx) }
    val scope = rememberCoroutineScope()

    val zones by prefs.targetZonesFlow().collectAsState(initial = emptyList())
    var pickerOpen by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<ContactZoneInferencer.Suggestion>>(emptyList()) }
    var suggestionsOpen by remember { mutableStateOf(false) }
    var suggestionsEmpty by remember { mutableStateOf(false) }
    val inferencer = remember { ContactZoneInferencer(ctx) }

    fun loadSuggestions() {
        scope.launch {
            val result = inferencer.suggest()
            suggestions = result
            suggestionsEmpty = result.isEmpty()
            suggestionsOpen = result.isNotEmpty()
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) loadSuggestions() }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("TimeTwister") }) },
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
                "Long-press a time (e.g. \"5pm CT\") in any messaging app, pick TimeTwister from the selection menu, and your zones get appended inline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("Your timezones", style = MaterialTheme.typography.titleMedium)

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
                            scope.launch { prefs.setTargetZones(zones - tz) }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove ${tz.id}")
                        }
                    },
                )
            }

            Button(onClick = { pickerOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add timezone")
            }

            OutlinedButton(onClick = {
                if (inferencer.hasPermission()) loadSuggestions()
                else contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }) {
                Icon(Icons.Default.People, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Suggest from contacts")
            }

            Text(
                "Reads country codes from your contacts to suggest zones. Numbers don't leave the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("Preview", style = MaterialTheme.typography.titleMedium)
            val sample = DetectedTime(
                hour = 17, minute = 0,
                zone = ZoneId.of("America/Chicago"),
                hadExplicitZone = true,
                range = 0..5,
                originalText = "5pm CT",
            )
            Text(
                TimeConverter.renderStamp(sample, zones),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    if (pickerOpen) {
        TimeZonePickerDialog(
            onDismiss = { pickerOpen = false },
            onPick = { z ->
                if (zones.none { it.id == z.id }) {
                    scope.launch { prefs.setTargetZones(zones + z) }
                }
                pickerOpen = false
            },
        )
    }

    if (suggestionsOpen) {
        AlertDialog(
            onDismissRequest = { suggestionsOpen = false },
            title = { Text("Suggested zones") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Based on country codes in your contacts. Tap to add — already-added zones are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestions.forEach { s ->
                        val alreadyAdded = zones.any { it.id == s.zone.id }
                        ListItem(
                            headlineContent = { Text(TimeZoneAlias.shortLabel(s.zone)) },
                            supportingContent = { Text("${s.zone.id} · ${s.contactCount} contact${if (s.contactCount == 1) "" else "s"}") },
                            trailingContent = {
                                if (alreadyAdded) Text("added", style = MaterialTheme.typography.labelSmall)
                                else TextButton(onClick = {
                                    scope.launch { prefs.setTargetZones(zones + s.zone) }
                                }) { Text("Add") }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { suggestionsOpen = false }) { Text("Done") }
            },
        )
    }

    if (suggestionsEmpty) {
        AlertDialog(
            onDismissRequest = { suggestionsEmpty = false },
            title = { Text("No suggestions") },
            text = { Text("None of your contacts have international country codes we could match. You can add zones manually.") },
            confirmButton = { TextButton(onClick = { suggestionsEmpty = false }) { Text("OK") } },
        )
    }
}
