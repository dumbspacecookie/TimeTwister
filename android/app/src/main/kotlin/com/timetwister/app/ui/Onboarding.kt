package com.timetwister.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.timetwister.app.R

/**
 * Onboarding surfaces.
 *
 * These exist because the app's only entry point is invisible by design: ACTION_PROCESS_TEXT
 * puts us behind the ⋮ overflow of the text-selection toolbar, where nothing about the app
 * or the OS ever hints that we're there. The correct six steps were documented only in
 * android/README.md — a file no installed user will ever open. So they ship in the binary:
 * once as a first-run screen, and permanently as a collapsible card in settings.
 */

/** Full-screen first-run tutorial. Shown once; the "seen" flag lives in UserPreferences. */
@Composable
fun FirstRunScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // No Scaffold here, so the window insets have to be applied by hand — the app
            // draws edge-to-edge and this screen would otherwise start under the status bar.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SelectionToolbarMock()

        HowToSteps()

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_got_it)) }
    }
}

/**
 * The same instructions, permanently reachable from settings. Collapsed by default after
 * first run would hide it from the people who need it most, so it starts expanded and the
 * state is saveable — rotating shouldn't slam it shut mid-read.
 */
@Composable
fun HowToUseCard() {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.section_how_to_use),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.action_collapse else R.string.action_expand,
                        ),
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SelectionToolbarMock()
                    HowToSteps()
                }
            }
        }
    }
}

/** The six steps from the README, numbered, plus the read-only caveat. */
@Composable
private fun HowToSteps() {
    val steps = listOf(
        R.string.howto_step_1,
        R.string.howto_step_2,
        R.string.howto_step_3,
        R.string.howto_step_4,
        R.string.howto_step_5,
        R.string.howto_step_6,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { index, res ->
            Row(verticalAlignment = Alignment.Top) {
                StepBadge(index + 1)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        HorizontalDivider()
        Text(
            text = stringResource(R.string.howto_readonly_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepBadge(number: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * A drawn stand-in for the text-selection toolbar.
 *
 * Deliberately Compose primitives rather than a screenshot: the real toolbar looks
 * different on every OEM skin and Android version, we can't capture one from a build
 * pipeline, and a stale PNG would be worse than an honest diagram. What matters is the
 * shape of the gesture — the obvious actions sit on the first row, and ours is in the ⋮.
 *
 * The whole illustration is one accessibility node; reading out "Copy, Paste, Share, ⋮"
 * as separate unlabelled buttons would imply they're tappable, which they aren't.
 */
@Composable
fun SelectionToolbarMock(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.howto_toolbar_mock_desc)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The faux message, with the time highlighted the way a selection would be.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.mock_message_prefix),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.mock_message_time),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        // The toolbar itself: three plausible first-row actions, then the overflow.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                listOf(R.string.mock_copy, R.string.mock_paste, R.string.mock_share).forEach {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mock_overflow),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        // The overflow sheet the ⋮ opens — this is the row people can't find.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.padding(start = 48.dp),
        ) {
            Column(Modifier.padding(PaddingValues(horizontal = 14.dp, vertical = 8.dp))) {
                Text(
                    text = stringResource(R.string.mock_overflow_other),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Text(
            text = stringResource(R.string.howto_overflow_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}
