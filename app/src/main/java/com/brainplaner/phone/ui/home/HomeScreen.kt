package com.brainplaner.phone.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
        // Readiness score — populated once the /readiness backend endpoint exists.
        Text("Estimated Readiness", style = MaterialTheme.typography.labelMedium)
        Text(state.readinessScore ?: "—", style = MaterialTheme.typography.displaySmall)

        HorizontalDivider()

        // Morning check-in card: shown until the user has submitted today's data.
        if (!state.hasCheckedInToday) {
            CheckInCard(
                isSubmitting = state.isCheckInSubmitting,
                onSubmit = { hours, score, rhr -> viewModel.submitCheckIn(hours, score, rhr) },
            )
            HorizontalDivider()
        }

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

@Composable
private fun CheckInCard(
    isSubmitting: Boolean,
    onSubmit: (sleepHours: Float, sleepScore: Int, rhr: Int?) -> Unit,
) {
    var sleepHours by remember { mutableFloatStateOf(7f) }
    var sleepScore by remember { mutableIntStateOf(70) }
    var rhrText by remember { mutableStateOf("") }

    val rhrValue: Int? = rhrText.trim().toIntOrNull()?.takeIf { it in 20..250 }
    val rhrIsError = rhrText.isNotBlank() && rhrValue == null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Morning check-in", style = MaterialTheme.typography.labelMedium)

        // Sleep hours: 4h – 12h in 0.5h steps
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Sleep", style = MaterialTheme.typography.bodySmall)
            Text(
                "${(sleepHours * 2).roundToInt() / 2.0}h",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sleepHours,
            onValueChange = { sleepHours = (it * 2).roundToInt() / 2f },
            valueRange = 4f..12f,
            steps = 15,
            modifier = Modifier.fillMaxWidth(),
        )

        // Sleep score: 0–100 (matches Oura, Garmin, Fitbit scale)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Sleep score", style = MaterialTheme.typography.bodySmall)
            Text(
                "$sleepScore",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sleepScore.toFloat(),
            onValueChange = { sleepScore = it.roundToInt() },
            valueRange = 0f..100f,
            steps = 99,
            modifier = Modifier.fillMaxWidth(),
        )

        // RHR: manual text field — easy to measure without a device
        OutlinedTextField(
            value = rhrText,
            onValueChange = { rhrText = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Resting HR (bpm)") },
            placeholder = { Text("e.g. 58") },
            singleLine = true,
            isError = rhrIsError,
            supportingText = {
                if (rhrIsError) Text("Enter a value between 20 and 250")
                else Text("Optional — measure before getting up")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = { onSubmit(sleepHours, sleepScore, rhrValue) },
            enabled = !isSubmitting && !rhrIsError,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Log morning data")
            }
        }
    }
}

