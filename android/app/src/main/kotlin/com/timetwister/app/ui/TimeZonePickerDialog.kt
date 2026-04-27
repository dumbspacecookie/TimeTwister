package com.timetwister.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeZonePickerDialog(
    onDismiss: () -> Unit,
    onPick: (ZoneId) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().sorted() }
    val filtered = remember(query) {
        if (query.isBlank()) all
        else all.filter { it.contains(query, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Add timezone", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(filtered, key = { it }) { id ->
                        TextButton(
                            onClick = { onPick(ZoneId.of(id)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(id, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
