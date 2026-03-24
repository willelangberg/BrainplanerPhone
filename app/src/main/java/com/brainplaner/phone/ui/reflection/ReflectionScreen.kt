package com.brainplaner.phone.ui.reflection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReflectionScreen(
    viewModel: ReflectionViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isSubmitted) {
        if (state.isSubmitted) onDone()
    }

    var alignmentScore by remember { mutableIntStateOf(-1) }
    var emotionLabel by remember { mutableStateOf("") }
    var handoffNextAction by remember { mutableStateOf("") }
    var blockerTag by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    val alignmentLabels = listOf("Not at all", "Partly", "Mostly", "Fully")
    val emotions = listOf(
        "proud" to "😊 Proud",
        "neutral" to "😐 Neutral",
        "mixed" to "🤔 Mixed",
        "frustrated" to "😤 Frustrated",
        "relieved" to "😮‍💨 Relieved",
    )
    val blockers = listOf(
        "scope_too_big" to "Scope too big",
        "unclear_next_step" to "Unclear next step",
        "low_energy" to "Low energy",
        "distractions" to "Distractions",
        "wrong_task" to "Wrong task",
        "other" to "Other",
    )

    val canSubmit = alignmentScore >= 0 && emotionLabel.isNotEmpty() && handoffNextAction.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Session Reflection", style = MaterialTheme.typography.headlineMedium)

        // Alignment score (0–3)
        Text(
            "How aligned was this session with your plan?",
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            alignmentLabels.forEachIndexed { i, label ->
                FilterChip(
                    selected = alignmentScore == i,
                    onClick = {
                        alignmentScore = i
                        // Clear blocker when user picks high alignment
                        if (i > 1) blockerTag = null
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        // Blocker — shown only when alignment is 0 or 1
        if (alignmentScore in 0..1) {
            Text("What got in the way?", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                blockers.forEach { (key, label) ->
                    FilterChip(
                        selected = blockerTag == key,
                        onClick = { blockerTag = if (blockerTag == key) null else key },
                        label = { Text(label) },
                    )
                }
            }
        }

        // Emotion
        Text("How do you feel about this session?", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            emotions.forEach { (key, label) ->
                FilterChip(
                    selected = emotionLabel == key,
                    onClick = { emotionLabel = if (emotionLabel == key) "" else key },
                    label = { Text(label) },
                )
            }
        }

        // Next step (required)
        Text("What's the concrete first action next time? *", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = handoffNextAction,
            onValueChange = { handoffNextAction = it.take(200) },
            placeholder = { Text("e.g. Open file X and write the intro paragraph") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            supportingText = { Text("${handoffNextAction.length}/200") },
        )

        // Optional note
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(280) },
            label = { Text("Note (optional)") },
            placeholder = { Text("Anything else worth capturing?") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            supportingText = { Text("${note.length}/280") },
        )

        if (state.error != null) {
            Text(
                text = "Error: ${state.error}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = {
                viewModel.submit(
                    alignmentScore = alignmentScore,
                    emotionLabel = emotionLabel,
                    handoffNextAction = handoffNextAction,
                    blockerTag = blockerTag,
                    note = note.takeIf { it.isNotBlank() },
                )
            },
            enabled = canSubmit && !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Submit & Wrap Up")
            }
        }

        TextButton(
            onClick = onDone,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Skip for now")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
