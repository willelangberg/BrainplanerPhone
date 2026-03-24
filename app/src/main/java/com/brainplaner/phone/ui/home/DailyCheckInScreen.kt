package com.brainplaner.phone.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

@Composable
fun DailyCheckInScreen(
    viewModel: HomeViewModel,
    onContinue: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var sleepHours by remember { mutableFloatStateOf(7f) }
    var sleepScore by remember { mutableIntStateOf(70) }
    var rhrText by remember { mutableStateOf("") }

    val rhrValue: Int? = rhrText.trim().toIntOrNull()?.takeIf { it in 20..250 }
    val rhrIsError = rhrText.isNotBlank() && rhrValue == null

    // If already checked in today (or after successful submit), proceed automatically.
    LaunchedEffect(state.hasCheckedInToday) {
        if (state.hasCheckedInToday) onContinue()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Morning check-in", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This is once per day. After this, you'll land on session launch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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

        OutlinedTextField(
            value = rhrText,
            onValueChange = { rhrText = it.filter { c -> c.isDigit() }.take(3) },
            label = { Text("Resting HR (bpm)") },
            placeholder = { Text("e.g. 58") },
            singleLine = true,
            isError = rhrIsError,
            supportingText = {
                if (rhrIsError) Text("Enter a value between 20 and 250")
                else Text("Optional")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { viewModel.submitCheckIn(sleepHours, sleepScore, rhrValue) },
            enabled = !state.isCheckInSubmitting && !rhrIsError,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isCheckInSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Save daily check-in")
            }
        }

        state.checkInError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

    }
}
