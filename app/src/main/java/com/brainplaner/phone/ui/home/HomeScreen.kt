package com.brainplaner.phone.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brainplaner.phone.LocalStore
import com.brainplaner.phone.ui.components.BrainCard
import com.brainplaner.phone.ui.components.BrainChoiceChip
import com.brainplaner.phone.ui.components.BrainDangerButton
import com.brainplaner.phone.ui.components.BrainPrimaryButton
import com.brainplaner.phone.ui.theme.BrainTeal
import com.brainplaner.phone.ui.theme.BrainplanerPhoneTheme
import com.brainplaner.phone.ui.theme.BrainplanerTheme
import com.brainplaner.phone.ui.theme.BudgetGreen
import com.brainplaner.phone.ui.theme.BudgetRed
import com.brainplaner.phone.ui.theme.BudgetYellow
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

private val DURATION_OPTIONS = listOf(15, 30, 45, 60, 120)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onStartSession: suspend (plannedMinutes: Int) -> Result<String>,
    onStopSession: suspend () -> Result<String>,
    onPauseSession: suspend () -> Result<String>,
    onResumeSession: suspend () -> Result<String>,
    onBudgetDetail: () -> Unit,
){
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var plannedMinutes by remember { mutableIntStateOf(45) }
    var activePlannedMinutes by remember { mutableIntStateOf(45) }
    var isSessionActive by remember { mutableStateOf(false) }
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var isActionLoading by remember { mutableStateOf(false) }
    var previousOffline by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var totalPauseMs by remember { mutableLongStateOf(0L) }
    var pauseStartMs by remember { mutableLongStateOf(0L) }

    val context = LocalContext.current
    var pendingRecovery by remember { mutableStateOf(LocalStore.getPendingRecovery(context)) }
    var isRecoveryConfirming by remember { mutableStateOf(false) }
    val spacing = BrainplanerTheme.spacing

    LaunchedEffect(Unit) {
        val activeSession = LocalStore.getActiveSession(context) ?: return@LaunchedEffect
        activePlannedMinutes = activeSession.plannedMinutes
        sessionStartMs = activeSession.startMs
        isPaused = activeSession.isPaused
        totalPauseMs = activeSession.totalPauseMs
        pauseStartMs = activeSession.pauseStartMs
        isSessionActive = true
    }

    // Auto-select suggested duration when available
    LaunchedEffect(state.insightSuggestedMinutes) {
        val suggested = state.insightSuggestedMinutes ?: return@LaunchedEffect
        if (!isSessionActive) plannedMinutes = suggested
    }

    val startSessionAction: () -> Unit = {
        scope.launch {
            isActionLoading = true
            val result = onStartSession(plannedMinutes)
            actionMessage = result.fold(
                onSuccess = {
                    activePlannedMinutes = plannedMinutes
                    sessionStartMs = System.currentTimeMillis()
                    isSessionActive = true
                    it
                },
                onFailure = { e -> "Error: ${e.message ?: "Failed to start"}" },
            )
            isActionLoading = false
        }
    }

    LaunchedEffect(isSessionActive, sessionStartMs, isPaused) {
        if (!isSessionActive || sessionStartMs <= 0L) {
            elapsedSeconds = 0L
            return@LaunchedEffect
        }
        while (isSessionActive) {
            val now = System.currentTimeMillis()
            val wallClock = now - sessionStartMs
            val currentPauseTime = if (isPaused && pauseStartMs > 0L) now - pauseStartMs else 0L
            val totalPause = totalPauseMs + currentPauseTime
            elapsedSeconds = ((wallClock - totalPause) / 1000L).coerceAtLeast(0L)
            delay(1000L)
        }
    }

    // Retry cloud enrichment periodically while on Home to handle Render cold starts.
    LaunchedEffect(Unit) {
        while (true) {
            delay(45_000L)
            viewModel.refreshCloudData()
        }
    }

    // Emit a simple reconnection message when cloud becomes reachable again.
    LaunchedEffect(state.isOffline) {
        if (previousOffline && !state.isOffline) {
            actionMessage = "✅ Reconnected — cloud sync updated"
        }
        previousOffline = state.isOffline
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── App header ──
        Text(
            "Brainplaner",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        if (state.isOffline) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "📴 Offline mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.cloudErrorReason?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { viewModel.refreshCloudData() }) {
                    Text("Retry now")
                }
            }
        } else {
            state.lastCloudSyncAtMs?.let { ts ->
                val minsAgo = max(0L, (System.currentTimeMillis() - ts) / 60_000L)
                Text(
                    "☁️ Synced ${minsAgo}m ago",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.lg))

        // ── Brain Budget gauge card ──
        BrainCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = BrainplanerTheme.surfaceRoles.surface2,
            onClick = onBudgetDetail,
        ) {
            Column(
                modifier = Modifier.padding(spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "BRAIN BUDGET",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(spacing.md))

                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    val score = state.readinessScore?.toIntOrNull() ?: 0
                    BrainBudgetGauge(score = score, modifier = Modifier.size(160.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap for details",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.md))

        // ── Self-insight card ──
        val hasInsight = state.insightEvidence != null

        if (hasInsight && !isSessionActive) {
            BrainCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(modifier = Modifier.padding(spacing.md)) {
                    Text(
                        "NOTICE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        letterSpacing = 2.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    state.insightEvidence?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    state.insightPrompt?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }

        // ── Recovery boost confirmation card ──
        pendingRecovery?.let { recovery ->
            BrainCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = BudgetGreen.copy(alpha = 0.15f),
            ) {
                Column(modifier = Modifier.padding(spacing.md)) {
                    Text(
                        "RECOVERY BOOST",
                        style = MaterialTheme.typography.labelMedium,
                        color = BudgetGreen,
                        letterSpacing = 2.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${recovery.emoji} ${recovery.type} — +${recovery.boostPoints} pts",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap 'Confirm' to apply this recovery boost to your Brain Budget.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (isRecoveryConfirming) return@Button
                                isRecoveryConfirming = true
                                viewModel.confirmRecoveryAction(
                                    type = recovery.type,
                                    emoji = recovery.emoji,
                                    boostPoints = recovery.boostPoints,
                                    selectedAtMs = recovery.selectedAt,
                                ) { ok ->
                                    LocalStore.clearPendingRecovery(context)
                                    pendingRecovery = null
                                    isRecoveryConfirming = false
                                    if (ok) actionMessage = "✅ Recovery boost applied!"
                                    else actionMessage = "⚠️ Could not sync — boost cleared locally"
                                }
                            },
                            enabled = !isRecoveryConfirming,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BudgetGreen),
                        ) {
                            if (isRecoveryConfirming) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text("Confirm")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                LocalStore.clearPendingRecovery(context)
                                pendingRecovery = null
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }

        // ── Continuity / handoff card ──
        BrainCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = BrainplanerTheme.surfaceRoles.surface2,
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                Text(
                    "CONTINUITY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    state.sessionSummary?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    state.handoffNextAction?.let {
                        Text(
                            "▸ Next: $it",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (state.sessionSummary == null && state.handoffNextAction == null) {
                        Text(
                            "No previous session — start your first one!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.lg))

        // ── Session controls ──
        if (!isSessionActive) {
            Text(
                "PLANNED DURATION",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val suggested = state.insightSuggestedMinutes
                val options = if (suggested != null && suggested !in DURATION_OPTIONS)
                    (DURATION_OPTIONS + suggested).sorted()
                else DURATION_OPTIONS

                options.forEach { min ->
                    val isSuggested = min == suggested
                    BrainChoiceChip(
                        selected = plannedMinutes == min,
                        onClick = { plannedMinutes = min },
                        label = {
                            Text(if (isSuggested) "${min}m ★" else "${min}m")
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isActionLoading) return@Button
                    startSessionAction()
                },
                enabled = !isActionLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                if (isActionLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Start Session", style = MaterialTheme.typography.titleLarge)
                }
            }
        } else {
            // ── Active session with timer ring ──
            val plannedSeconds = activePlannedMinutes * 60L
            val remaining = plannedSeconds - elapsedSeconds
            val progress = (elapsedSeconds.toFloat() / plannedSeconds.toFloat()).coerceIn(0f, 2f)
            val isOvertime = remaining < 0

            BrainCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = BrainplanerTheme.surfaceRoles.surface2,
            ) {
                Column(
                    modifier = Modifier.padding(spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (isPaused) "PAUSED" else if (isOvertime) "OVERTIME" else "SESSION ACTIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isPaused) BudgetYellow else if (isOvertime) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        letterSpacing = 2.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    SessionTimerRing(
                        progress = progress,
                        isOvertime = isOvertime,
                        modifier = Modifier.size(180.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                formatDuration(elapsedSeconds),
                                style = MaterialTheme.typography.displayMedium,
                            )
                            Text(
                                if (remaining >= 0) "-${formatDuration(remaining)}"
                                else "+${formatDuration(-remaining)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isOvertime) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Planned: ${activePlannedMinutes}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Pause / Resume button ──
            Button(
                onClick = {
                    if (isActionLoading) return@Button
                    scope.launch {
                        isActionLoading = true
                        if (isPaused) {
                            val result = onResumeSession()
                            actionMessage = result.fold(
                                onSuccess = {
                                    totalPauseMs += System.currentTimeMillis() - pauseStartMs
                                    pauseStartMs = 0L
                                    isPaused = false
                                    it
                                },
                                onFailure = { e -> "Error: ${e.message ?: "Failed to resume"}" },
                            )
                        } else {
                            val result = onPauseSession()
                            actionMessage = result.fold(
                                onSuccess = {
                                    pauseStartMs = System.currentTimeMillis()
                                    isPaused = true
                                    it
                                },
                                onFailure = { e -> "Error: ${e.message ?: "Failed to pause"}" },
                            )
                        }
                        isActionLoading = false
                    }
                },
                enabled = !isActionLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPaused) BudgetGreen else BudgetYellow,
                ),
            ) {
                Text(
                    if (isPaused) "▶  Resume" else "⏸  Pause",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (isActionLoading) return@Button
                    scope.launch {
                        isActionLoading = true
                        val result = onStopSession()
                        actionMessage = result.fold(
                            onSuccess = {
                                isSessionActive = false
                                sessionStartMs = 0L
                                isPaused = false
                                totalPauseMs = 0L
                                pauseStartMs = 0L
                                viewModel.load()
                                it
                            },
                            onFailure = { e -> "Error: ${e.message ?: "Failed to stop"}" },
                        )
                        isActionLoading = false
                    }
                },
                enabled = !isActionLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                if (isActionLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Stop Session", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        // ── Status / action messages ──
        actionMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Circular Brain Budget Gauge ──

@Composable
private fun BrainBudgetGauge(score: Int, modifier: Modifier = Modifier) {
    val fraction = (score / 100f).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 800),
        label = "gauge",
    )
    val gaugeColor = when {
        score >= 70 -> BudgetGreen
        score >= 40 -> BudgetYellow
        else -> BudgetRed
    }
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            // Track
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            // Fill
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
                "$score",
                style = MaterialTheme.typography.displayLarge.copy(
                    color = gaugeColor,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                "/ 100",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Session Timer Ring ──

@Composable
private fun SessionTimerRing(
    progress: Float,
    isOvertime: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val ringColor = if (isOvertime) BudgetRed else BrainTeal
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 10.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * clampedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600L
    val minutes = (safeSeconds % 3600L) / 60L
    val seconds = safeSeconds % 60L
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePreviewContent(
    state: HomeUiState,
    plannedMinutes: Int = 45,
    isSessionActive: Boolean = false,
    activePlannedMinutes: Int = plannedMinutes,
    elapsedSeconds: Long = 0L,
    isPaused: Boolean = false,
    actionMessage: String? = null,
) {
    val spacing = BrainplanerTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Brainplaner",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        when {
            state.isOffline -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "📴 Offline mode",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.cloudErrorReason?.let { reason ->
                            Text(
                                reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = {}) {
                        Text("Retry now")
                    }
                }
            }

            state.lastCloudSyncAtMs != null -> {
                Text(
                    "☁️ Synced recently",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.lg))

        BrainCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = BrainplanerTheme.surfaceRoles.surface2,
        ) {
            Column(
                modifier = Modifier.padding(spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "BRAIN BUDGET",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(spacing.md))

                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    BrainBudgetGauge(
                        score = state.readinessScore?.toIntOrNull() ?: 0,
                        modifier = Modifier.size(160.dp),
                    )
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        "Tap for details",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.md))

        if (!state.insightEvidence.isNullOrBlank() && !isSessionActive) {
            BrainCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(modifier = Modifier.padding(spacing.md)) {
                    Text(
                        "NOTICE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        letterSpacing = 2.sp,
                    )
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        state.insightEvidence,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    state.insightPrompt?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))
        }

        BrainCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = BrainplanerTheme.surfaceRoles.surface2,
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                Text(
                    "CONTINUITY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                when {
                    state.isLoading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    !state.sessionSummary.isNullOrBlank() || !state.handoffNextAction.isNullOrBlank() -> {
                        state.sessionSummary?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        state.handoffNextAction?.let {
                            Text(
                                "▸ Next: $it",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    else -> {
                        Text(
                            "No previous session — start your first one!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.lg))

        if (!isSessionActive) {
            Text(
                "PLANNED DURATION",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp,
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                val suggested = state.insightSuggestedMinutes
                val options = if (suggested != null && suggested !in DURATION_OPTIONS) {
                    (DURATION_OPTIONS + suggested).sorted()
                } else {
                    DURATION_OPTIONS
                }

                options.forEach { minutes ->
                    BrainChoiceChip(
                        selected = plannedMinutes == minutes,
                        onClick = {},
                        label = {
                            Text(if (minutes == suggested) "${minutes}m ★" else "${minutes}m")
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.md))

            BrainPrimaryButton(
                text = if (state.isCheckInSubmitting) "Preparing..." else "Start Session",
                onClick = {},
                enabled = false,
                modifier = Modifier.height(56.dp),
            )
        } else {
            val plannedSeconds = activePlannedMinutes * 60L
            val remaining = plannedSeconds - elapsedSeconds
            val progress = if (plannedSeconds > 0) {
                (elapsedSeconds.toFloat() / plannedSeconds.toFloat()).coerceIn(0f, 2f)
            } else {
                0f
            }
            val isOvertime = remaining < 0

            BrainCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = BrainplanerTheme.surfaceRoles.surface2,
            ) {
                Column(
                    modifier = Modifier.padding(spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (isPaused) "PAUSED" else if (isOvertime) "OVERTIME" else "SESSION ACTIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isPaused) BudgetYellow else if (isOvertime) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        letterSpacing = 2.sp,
                    )
                    Spacer(modifier = Modifier.height(spacing.md))
                    SessionTimerRing(
                        progress = progress,
                        isOvertime = isOvertime,
                        modifier = Modifier.size(180.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                formatDuration(elapsedSeconds),
                                style = MaterialTheme.typography.displayMedium,
                            )
                            Text(
                                if (remaining >= 0) "-${formatDuration(remaining)}" else "+${formatDuration(-remaining)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isOvertime) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        "Planned: ${activePlannedMinutes}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            BrainPrimaryButton(
                text = if (isPaused) "▶ Resume" else "⏸ Pause",
                onClick = {},
                enabled = false,
                containerColor = if (isPaused) BudgetGreen else BudgetYellow,
                modifier = Modifier.height(48.dp),
            )

            Spacer(modifier = Modifier.height(spacing.xs))

            BrainDangerButton(
                text = "Stop Session",
                onClick = {},
                enabled = false,
                modifier = Modifier.height(56.dp),
            )
        }

        actionMessage?.let {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(spacing.md))
    }
}

@Preview(name = "Home Loading", showBackground = true)
@Composable
private fun HomePreviewLoading() {
    BrainplanerPhoneTheme(darkTheme = false) {
        HomePreviewContent(state = HomeUiState(isLoading = true))
    }
}

@Preview(name = "Home Ready", showBackground = true)
@Composable
private fun HomePreviewReady() {
    BrainplanerPhoneTheme(darkTheme = false) {
        HomePreviewContent(
            state = HomeUiState(
                isLoading = false,
                readinessScore = "76",
                sessionSummary = "Strong coding block with steady pace.",
                handoffNextAction = "Open coaching.py and finish the recovery endpoint.",
                insightEvidence = "You usually do better with a 30 minute warm start.",
                insightPrompt = "Start smaller, then extend if focus stays stable.",
                insightSuggestedMinutes = 30,
                lastCloudSyncAtMs = 1L,
            ),
            plannedMinutes = 30,
        )
    }
}

@Preview(name = "Home Offline", showBackground = true)
@Composable
private fun HomePreviewOffline() {
    BrainplanerPhoneTheme(darkTheme = false) {
        HomePreviewContent(
            state = HomeUiState(
                isLoading = false,
                readinessScore = "54",
                isOffline = true,
                cloudErrorReason = "Cloud wake-up delay",
                sessionSummary = "Last session drifted in the final 10 minutes.",
                handoffNextAction = "Review the readiness score weights.",
            ),
            actionMessage = "Using local data until sync returns",
        )
    }
}

@Preview(name = "Home Active", showBackground = true)
@Composable
private fun HomePreviewActive() {
    BrainplanerPhoneTheme(darkTheme = true) {
        HomePreviewContent(
            state = HomeUiState(
                isLoading = false,
                readinessScore = "68",
                sessionSummary = "Deep work block in progress.",
                handoffNextAction = "Summarize the recovery heuristics.",
                lastCloudSyncAtMs = 1L,
            ),
            isSessionActive = true,
            activePlannedMinutes = 45,
            elapsedSeconds = 27 * 60L,
        )
    }
}

@Preview(name = "Home Active Font 1.3x", showBackground = true, fontScale = 1.3f)
@Composable
private fun HomePreviewActiveFontScale() {
    BrainplanerPhoneTheme(darkTheme = true) {
        HomePreviewContent(
            state = HomeUiState(
                isLoading = false,
                readinessScore = "68",
                sessionSummary = "Deep work block in progress.",
                handoffNextAction = "Summarize the recovery heuristics.",
                lastCloudSyncAtMs = 1L,
            ),
            isSessionActive = true,
            activePlannedMinutes = 45,
            elapsedSeconds = 52 * 60L,
            isPaused = true,
        )
    }
}
