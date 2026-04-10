package com.brainplaner.phone.ui.reflection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brainplaner.phone.LocalStore
import com.brainplaner.phone.ui.theme.BudgetGreen
import com.brainplaner.phone.ui.theme.BudgetRed
import com.brainplaner.phone.ui.theme.BudgetYellow

@Composable
fun ReflectionScreen(
    viewModel: ReflectionViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showRecovery by remember { mutableStateOf(false) }
    var submittedDrainScore by remember { mutableIntStateOf(3) }

    LaunchedEffect(state.isSubmitted) {
        if (state.isSubmitted) {
            showRecovery = true
        }
    }

    if (showRecovery) {
        RecoverySuggestionsScreen(
            drainScore = submittedDrainScore,
            onDone = onDone,
        )
        return
    }

    var focusScore by remember { mutableIntStateOf(0) }
    var drainScore by remember { mutableIntStateOf(0) }
    var alignmentScore by remember { mutableIntStateOf(0) }
    var handoffNextAction by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    data class AnchoredScore(val score: Int, val label: String, val anchor: String)

    val focusAnchors = listOf(
        AnchoredScore(5, "Flow state", "Lost track of time, deep in the work"),
        AnchoredScore(4, "Focused", "Mostly uninterrupted, few breaks"),
        AnchoredScore(3, "Moderate", "Some distractions but got work done"),
        AnchoredScore(2, "Scattered", "Started working but frequently drifted"),
        AnchoredScore(1, "Couldn't focus", "Kept picking up phone, couldn't settle"),
    )
    val drainAnchors = listOf(
        AnchoredScore(5, "Depleted", "Done for the day, can't concentrate"),
        AnchoredScore(4, "Drained", "Brain feels slow, making mistakes"),
        AnchoredScore(3, "Tired", "Need a real break before more work"),
        AnchoredScore(2, "Mild fatigue", "Fine but wouldn't want a hard task next"),
        AnchoredScore(1, "Energized", "Could do another session right now"),
    )
    val alignmentAnchors = listOf(
        AnchoredScore(3, "Fully aligned", "I did the exact next step I planned"),
        AnchoredScore(2, "Mostly aligned", "I worked in the same direction with minor drift"),
        AnchoredScore(1, "Partly aligned", "I touched it, but drifted to other work"),
        AnchoredScore(0, "Not aligned", "I didn't follow the previous next step"),
    )

    val canSubmit = focusScore > 0 && drainScore > 0 && handoffNextAction.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Session Reflection",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Primary validator: Focus (1–5)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "HOW FOCUSED WERE YOU?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                focusAnchors.forEach { item ->
                    FilterChip(
                        selected = focusScore == item.score,
                        onClick = { focusScore = item.score },
                        label = {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("${item.score}. ${item.label}", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    item.anchor,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Secondary validator: Mental drain (1–5)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "HOW MENTALLY DRAINED DO YOU FEEL?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                drainAnchors.forEach { item ->
                    FilterChip(
                        selected = drainScore == item.score,
                        onClick = { drainScore = item.score },
                        label = {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("${item.score}. ${item.label}", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    item.anchor,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Next step + note card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "HANDOFF",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
                OutlinedTextField(
                    value = handoffNextAction,
                    onValueChange = { handoffNextAction = it.take(200) },
                    label = { Text("First action next time *") },
                    placeholder = { Text("e.g. Open file X and write the intro paragraph") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    supportingText = { Text("${handoffNextAction.length}/200") },
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(280) },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Anything else worth capturing?") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    supportingText = { Text("${note.length}/280") },
                )
            }
        }

        if (state.error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Error: ${state.error}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                viewModel.submit(
                    focusScore = focusScore,
                    drainScore = drainScore,
                    alignmentScore = alignmentScore,
                    handoffNextAction = handoffNextAction,
                    note = note.takeIf { it.isNotBlank() },
                )
                submittedDrainScore = drainScore
            },
            enabled = canSubmit && !state.isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Submit Reflection", style = MaterialTheme.typography.titleLarge)
            }
        }

        TextButton(onClick = onDone) {
            Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Session Truth Screen ──

@Composable
private fun SessionTruthScreen(
    truth: SessionTruthData,
    focusScore: Int,
    onContinue: () -> Unit,
) {
    val pct = (truth.completionRatio * 100).toInt()
    val accuracyColor = when {
        pct in 80..120 -> BudgetGreen
        pct in 60..79 || pct in 121..140 -> BudgetYellow
        else -> BudgetRed
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Session Truth",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Here's what actually happened",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Duration card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "PLAN VS EXECUTION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "⏱  ${truth.plannedMinutes} min planned → ${truth.focusedMinutes.toInt()} min focused",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = accuracyColor,
                    )
                }
                if (truth.pauseCount > 0) {
                    Text(
                        "${truth.pauseCount} pause${if (truth.pauseCount > 1) "s" else ""} · ${formatSeconds(truth.totalPauseSeconds.toInt())} paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Phone behavior card
        if (truth.phoneUnlocks != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PHONE BEHAVIOR",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 2.sp,
                    )
                    val screenPart = truth.phoneScreenOnSeconds?.let {
                        " · ${formatSeconds(it)} screen time"
                    } ?: ""
                    Text(
                        "📱  ${truth.phoneUnlocks} unlock${if (truth.phoneUnlocks != 1) "s" else ""}$screenPart",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // Coaching interpretation card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("💡", style = MaterialTheme.typography.displaySmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    buildCoachingLine(truth, focusScore),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text("Continue", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun buildCoachingLine(truth: SessionTruthData, focusScore: Int): String {
    val pct = (truth.completionRatio * 100).toInt()
    val unlocks = truth.phoneUnlocks

    // Overshoot / undershoot take priority
    if (pct >= 120) {
        val over = (truth.actualMinutes - truth.plannedMinutes).toInt()
        return "You worked ${over}min past your plan. Overshoot costs tomorrow's budget — try a shorter session or set an alarm."
    }
    if (pct <= 60) {
        return "Session hit only $pct% of plan. Consider shorter planned blocks so you finish what you start."
    }

    // Cross-reference subjective focus with phone behavior
    if (unlocks != null) {
        if (focusScore >= 4 && unlocks > 5) {
            return "You rated focus high, but $unlocks phone unlocks tell a different story. Notice the gap."
        }
        if (focusScore <= 2 && unlocks <= 2) {
            return "Phone stayed quiet but you felt scattered. The distraction might be internal — try a mid-session checkpoint next time."
        }
        if (focusScore >= 4 && unlocks <= 2) {
            return "Clean session. Focus matched behavior — keep building the streak."
        }
    }

    // Default based on completion
    return when {
        pct in 90..110 -> "Solid execution. Plan and reality aligned well."
        pct in 80..89 -> "Close to plan. Small drift is normal — good session."
        else -> "Decent session. Review what caused the gap and adjust next time."
    }
}

private fun formatSeconds(totalSeconds: Int): String {
    val min = totalSeconds / 60
    val sec = totalSeconds % 60
    return if (min > 0) "${min}m${if (sec > 0) " ${sec}s" else ""}" else "${sec}s"
}

// ── Recovery Suggestions Screen ──

private data class RecoveryAction(
    val emoji: String,
    val title: String,
    val description: String,
    val budgetBoost: String,
    val boostPoints: Int,
    val duration: String,
)

@Composable
private fun RecoverySuggestionsScreen(
    drainScore: Int,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val actions = buildRecoveryActions(drainScore)
    var selectedIndex by remember { mutableIntStateOf(-1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Recovery Plan",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Pick one — confirm when you're back",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))

        actions.forEachIndexed { index, action ->
            val isSelected = selectedIndex == index
            Card(
                onClick = { selectedIndex = if (isSelected) -1 else index },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        BudgetGreen.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                ),
                border = if (isSelected)
                    androidx.compose.foundation.BorderStroke(2.dp, BudgetGreen)
                else null,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        if (isSelected) "✅" else action.emoji,
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            action.title,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            action.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                action.budgetBoost,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = BudgetGreen,
                            )
                            Text(
                                action.duration,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "💡",
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    when {
                        drainScore >= 4 -> "You're running on empty. Prioritize rest — tomorrow's budget depends on it."
                        drainScore == 3 -> "Good session! A short break will help you bounce back faster."
                        else -> "Great energy! You could start another session or bank recovery for later."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (selectedIndex >= 0) {
                    val picked = actions[selectedIndex]
                    LocalStore.savePendingRecovery(
                        context,
                        type = picked.title,
                        emoji = picked.emoji,
                        boostPoints = picked.boostPoints,
                    )
                }
                onDone()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedIndex >= 0) BudgetGreen else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                if (selectedIndex >= 0) "Start ${actions[selectedIndex].title} — Back to Home"
                else "Skip — Back to Home",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Cooldown Reminder Screen ──

@Composable
private fun CooldownReminderScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "🧠",
            style = MaterialTheme.typography.displayLarge,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "Let your brain finish the job",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Your brain keeps working after a session ends. " +
                        "During quiet rest, it replays what you just learned, " +
                        "consolidates memory, and connects ideas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Picking up your phone interrupts this process. " +
                        "Even a few minutes of rest before your first " +
                        "screen unlock helps you retain more.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📵", style = MaterialTheme.typography.headlineMedium)
                Column {
                    Text(
                        "Try to wait before your next unlock",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "We'll track your cooldown quietly in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                "Put phone down — Back to Home",
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun buildRecoveryActions(drainScore: Int): List<RecoveryAction> {
    // Boosts calibrated to the readiness formula (0-100 scale).
    // Heavy session load costs -12 to -18 pts; drain 4-5 costs -4 to -8 pts.
    // Recovery should offset 20-65% of combined depletion, not fully cancel it.
    val walkBoost = when {
        drainScore >= 4 -> 5   // RHR -1-2 bpm → +1.5-3 pts, mild sleep benefit
        drainScore == 3 -> 3
        else -> 2
    }
    val eatBoost = when {
        drainScore >= 4 -> 4   // blood-sugar stability → RHR + sleep benefit
        drainScore == 3 -> 3
        else -> 2
    }
    val trainBoost = when {
        drainScore >= 4 -> 10  // RHR -2-4 bpm → +3-6 pts, better sleep +3-4 pts
        drainScore == 3 -> 7
        else -> 4
    }
    val napBoost = when {
        drainScore >= 4 -> 12  // ~0.5h sleep → +3 pts, RHR recovery, drain offset
        drainScore == 3 -> 8
        else -> 5
    }

    return listOf(
        RecoveryAction(
            emoji = "🚶",
            title = "Walk",
            description = "Fresh air + light movement. Clears mental fog without taxing your body.",
            budgetBoost = "+$walkBoost budget",
            boostPoints = walkBoost,
            duration = "15–20 min",
        ),
        RecoveryAction(
            emoji = "🍽️",
            title = "Eat",
            description = "Balanced meal or snack. Glucose restores decision-making capacity.",
            budgetBoost = "+$eatBoost budget",
            boostPoints = eatBoost,
            duration = "20–30 min",
        ),
        RecoveryAction(
            emoji = "🏋️",
            title = "Train",
            description = "Moderate exercise. BDNF release boosts neuroplasticity and clears cortisol.",
            budgetBoost = "+$trainBoost budget",
            boostPoints = trainBoost,
            duration = "30–45 min",
        ),
        RecoveryAction(
            emoji = "😴",
            title = "Power Nap",
            description = "Short sleep resets working memory. Best ROI for high drain scores.",
            budgetBoost = "+$napBoost budget",
            boostPoints = napBoost,
            duration = "20–30 min",
        ),
    )
}
