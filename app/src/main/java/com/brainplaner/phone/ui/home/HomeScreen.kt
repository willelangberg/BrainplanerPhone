package com.brainplaner.phone.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val DURATION_OPTIONS = listOf(15, 30, 45, 60)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onStartSession: (plannedMinutes: Int) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var plannedMinutes by remember { mutableIntStateOf(45) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Brain Budget score
        Text("Brain Budget", style = MaterialTheme.typography.labelMedium)
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        } else {
            Text(
                state.readinessScore?.let { "$it / 100" } ?: "—",
                style = MaterialTheme.typography.displaySmall,
            )
        }

        HorizontalDivider()

        // Previous session continuity
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            state.sessionSummary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            state.handoffNextAction?.let {
                Text(
                    "Next step: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.sessionSummary == null && state.handoffNextAction == null) {
                Text(
                    "No previous session",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // Planned duration selector
        Text("Planned duration", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DURATION_OPTIONS.forEach { min ->
                FilterChip(
                    selected = plannedMinutes == min,
                    onClick = { plannedMinutes = min },
                    label = { Text("${min}m") },
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { onStartSession(plannedMinutes) },
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .align(Alignment.CenterHorizontally),
        ) {
            Text("Start Session")
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

