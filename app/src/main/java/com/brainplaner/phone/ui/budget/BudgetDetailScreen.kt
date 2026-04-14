package com.brainplaner.phone.ui.budget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brainplaner.phone.LocalStore
import com.brainplaner.phone.ui.home.HomeViewModel
import com.brainplaner.phone.ui.theme.BrainplanerTheme
import com.brainplaner.phone.ui.theme.BudgetGreen
import com.brainplaner.phone.ui.theme.BudgetRed
import com.brainplaner.phone.ui.theme.BudgetYellow

@Composable
fun BudgetDetailScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
) {
    val spacing = BrainplanerTheme.spacing
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val score = state.readinessScore?.toIntOrNull() ?: 0

    LaunchedEffect(Unit) {
        viewModel.refreshCloudData()
    }

    val breakdown = state.readinessBreakdown
    val pendingRecovery = LocalStore.getPendingRecovery(context)

    val sleepHoursAdj = breakdown["sleep_hours"] ?: 0f
    val sleepScoreAdj = breakdown["sleep_score"] ?: 0f
    val rhrAdj = breakdown["rhr"] ?: 0f
    val healthAdj = (sleepHoursAdj + sleepScoreAdj + rhrAdj).toInt()

    val loadSessionAdj = breakdown["session_load"] ?: 0f
    val loadDrainAdj = breakdown["drain_score"] ?: 0f
    val loadCooldownAdj = breakdown["cooldown_index"] ?: 0f
    val loadAdj = (loadSessionAdj + loadDrainAdj + loadCooldownAdj).toInt()

    val recoveryAdj = (breakdown["recovery_actions"] ?: 0f).toInt()
    val hasRecoveryBoost = breakdown.containsKey("recovery_actions")

    val healthSubFactors = buildList {
        if (breakdown.containsKey("sleep_hours")) add(SubFactor("Sleep hours", sleepHoursAdj.toInt()))
        if (breakdown.containsKey("sleep_score")) add(SubFactor("Sleep quality", sleepScoreAdj.toInt()))
        if (breakdown.containsKey("rhr")) add(SubFactor("Resting HR", rhrAdj.toInt()))
    }

    val loadSubFactors = buildList {
        if (breakdown.containsKey("session_load")) add(SubFactor("Session load", loadSessionAdj.toInt()))
        if (breakdown.containsKey("drain_score")) add(SubFactor("Drain score", loadDrainAdj.toInt()))
        if (breakdown.containsKey("cooldown_index")) add(SubFactor("Cooldown", loadCooldownAdj.toInt()))
    }

    val categories = listOf(
        Category(
            emoji = "🌙",
            title = "Health",
            valueLabel = signedLabel(healthAdj),
            points = healthAdj,
            description = if (healthSubFactors.isEmpty()) "Complete your morning check-in" else "Sleep + sleep quality + RHR baseline impact",
            subFactors = healthSubFactors,
        ),
        Category(
            emoji = "⚡",
            title = "Load",
            valueLabel = signedLabel(loadAdj),
            points = loadAdj,
            description = state.planningAccuracyLine ?: "Session load + drain score + cooldown behavior impact",
            subFactors = loadSubFactors,
        ),
        Category(
            emoji = "💚",
            title = "Recovery",
            valueLabel = if (hasRecoveryBoost) signedLabel(recoveryAdj) else "TIP",
            points = recoveryAdj,
            description = when {
                hasRecoveryBoost -> "Confirmed recovery actions boosting today\'s budget"
                !state.readinessMessage.isNullOrBlank() -> state.readinessMessage.orEmpty()
                pendingRecovery != null -> "${pendingRecovery.type} available - confirm on home screen"
                else -> "Follow the readiness guidance and add recovery after demanding sessions"
            },
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = spacing.lg, end = spacing.lg, top = 48.dp, bottom = spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("<- Back", color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "Budget Detail",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(64.dp))
        }

        Spacer(modifier = Modifier.height(spacing.md))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            BudgetGauge(score = score, modifier = Modifier.size(180.dp))
        }

        Spacer(modifier = Modifier.height(spacing.xs))

        Text(
            text = when {
                score >= 80 -> "Fully charged - great day for deep work"
                score >= 60 -> "Good capacity - pace yourself"
                score >= 40 -> "Moderate - lighter tasks recommended"
                score >= 20 -> "Low energy - protect what\'s left"
                else -> "Depleted - rest is the priority"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(spacing.xl))

        Text(
            text = "TODAY\'S BREAKDOWN",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(spacing.sm))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BrainplanerTheme.surfaceRoles.surface2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                categories.forEachIndexed { index, category ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        )
                    }

                    CategoryRow(category = category)
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.md))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = BrainplanerTheme.surfaceRoles.surface2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "How this works",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "You start each day at 100. Sleep, session load, and recovery actions adjust the score. It guides how much deep work to plan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.xxl))
    }
}

@Composable
private fun CategoryRow(category: Category) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(category.emoji, fontSize = 28.sp)

        Column(modifier = Modifier.weight(1f)) {
            Text(category.title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))

            if (category.subFactors.isNotEmpty()) {
                category.subFactors.forEach { subFactor ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = subFactor.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = signedLabel(subFactor.points),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = pointsColor(subFactor.points),
                        )
                    }
                }
            } else {
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = category.valueLabel,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = pointsColor(category.points),
        )
    }
}

@Composable
private fun BudgetGauge(score: Int, modifier: Modifier = Modifier) {
    val fraction = (score / 100f).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 800),
        label = "budget_gauge",
    )

    val gaugeColor = when {
        score >= 70 -> BudgetGreen
        score >= 40 -> BudgetYellow
        else -> BudgetRed
    }
    val trackColor = Color.Gray.copy(alpha = 0.2f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            drawArc(
                color = gaugeColor,
                startAngle = 135f,
                sweepAngle = 270f * animatedFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$score",
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "/ 100",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class SubFactor(
    val label: String,
    val points: Int,
)

private data class Category(
    val emoji: String,
    val title: String,
    val valueLabel: String,
    val points: Int,
    val description: String,
    val subFactors: List<SubFactor> = emptyList(),
)

@Composable
private fun pointsColor(points: Int): Color {
    return when {
        points > 0 -> BudgetGreen
        points < 0 -> BudgetRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun signedLabel(value: Int): String {
    return if (value >= 0) "+$value" else "$value"
}
