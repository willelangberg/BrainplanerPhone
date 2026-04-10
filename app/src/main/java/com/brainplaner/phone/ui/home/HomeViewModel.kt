package com.brainplaner.phone.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.brainplaner.phone.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class HomeUiState(
    val isLoading: Boolean = true,
    val sessionSummary: String? = null,
    val handoffNextAction: String? = null,
    val readinessScore: String? = null,
    val readinessBreakdown: Map<String, Float> = emptyMap(),
    val readinessMessage: String? = null,
    val planningAccuracyLine: String? = null,
    val hasCheckedInToday: Boolean = false,
    val isCheckInSubmitting: Boolean = false,
    val checkInError: String? = null,
    val isOffline: Boolean = false,
    val lastCloudSyncAtMs: Long? = null,
    val cloudErrorReason: String? = null,
)

class HomeViewModel(
    application: Application,
    private val userId: String,
    private val apiUrl: String,
    private val userToken: String,
) : AndroidViewModel(application) {

    private val ctx get() = getApplication<Application>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            // Immediately show local data so the user is never blocked.
            val localCheckedIn = LocalStore.hasCheckedInToday(ctx)
            val localNextAction = LocalStore.getLastNextAction(ctx)
            val localSummary = LocalStore.getLastSummary(ctx)
            val localCheckinData = LocalStore.getCheckInData(ctx)
            val localScore = localCheckinData?.let { (sleep, score, _) ->
                LocalStore.estimateReadiness(sleep, score).toString()
            }

            _state.value = HomeUiState(
                isLoading = false,
                hasCheckedInToday = localCheckedIn,
                readinessScore = localScore,
                handoffNextAction = localNextAction,
                sessionSummary = localSummary,
                isOffline = false,
                lastCloudSyncAtMs = null,
                cloudErrorReason = null,
            )

            // Try to enrich with cloud data in background (non-blocking).
            launch { trySyncPendingCheckIn() }
            launch { trySyncPendingWarmup() }
            launch { tryEnrichFromCloud() }
        }
    }

    fun refreshCloudData() {
        viewModelScope.launch {
            launch { trySyncPendingCheckIn() }
            launch { trySyncPendingWarmup() }
            launch { tryEnrichFromCloud() }
        }
    }

    /** Try to sync any pending check-in to cloud (fire-and-forget). */
    private suspend fun trySyncPendingCheckIn() = withContext(Dispatchers.IO) {
        if (LocalStore.isCheckInSynced(ctx)) return@withContext
        val data = LocalStore.getCheckInData(ctx) ?: return@withContext
        val (sleep, score, rhr) = data
        val synced = postCheckInToCloud(sleep, score, rhr)
        if (synced) LocalStore.markCheckInSynced(ctx)
    }

    /** Try to sync today's warmup result to cloud (fire-and-forget). */
    private suspend fun trySyncPendingWarmup() = withContext(Dispatchers.IO) {
        if (LocalStore.isWarmupSyncedToday(ctx)) return@withContext
        val warmup = LocalStore.getTodayWarmupData(ctx) ?: return@withContext
        val (date, medianMs) = warmup
        val synced = postWarmupToCloud(date, medianMs)
        if (synced) LocalStore.markWarmupSyncedToday(ctx)
    }

    /** Try to fetch cloud readiness/brief and update state. */
    private suspend fun tryEnrichFromCloud() {
        val brief = withContext(Dispatchers.IO) { fetchBrief() }
        val readinessData = withContext(Dispatchers.IO) { fetchReadinessData() }

        val current = _state.value
        val anyCloudSuccess = brief.success || readinessData.success
        val nowOffline = !anyCloudSuccess && current.readinessScore != null
        val nextLastSync = if (anyCloudSuccess) System.currentTimeMillis() else current.lastCloudSyncAtMs
        val nextErrorReason = if (anyCloudSuccess) {
            null
        } else {
            readinessData.errorReason ?: brief.errorReason ?: "Cloud fetch failed"
        }

        _state.value = current.copy(
            readinessScore = readinessData.score ?: current.readinessScore,
            readinessBreakdown = if (readinessData.breakdown.isNotEmpty()) readinessData.breakdown else current.readinessBreakdown,
            readinessMessage = readinessData.message ?: current.readinessMessage,
            hasCheckedInToday = readinessData.hasCheckinToday || current.hasCheckedInToday,
            planningAccuracyLine = readinessData.planningLine ?: current.planningAccuracyLine,
            sessionSummary = brief.sessionSummary ?: current.sessionSummary,
            handoffNextAction = brief.handoffNextAction ?: current.handoffNextAction,
            isOffline = nowOffline,
            lastCloudSyncAtMs = nextLastSync,
            cloudErrorReason = nextErrorReason,
        )
    }

    /** Save check-in locally first, then try cloud sync. Never blocks user. */
    fun submitCheckIn(sleepHours: Float, sleepScore: Int, rhr: Int?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCheckInSubmitting = true, checkInError = null)

            // Save locally immediately.
            LocalStore.saveCheckIn(ctx, sleepHours, sleepScore, rhr)
            val localScore = LocalStore.estimateReadiness(sleepHours, sleepScore)

            _state.value = _state.value.copy(
                isCheckInSubmitting = false,
                hasCheckedInToday = true,
                readinessScore = localScore.toString(),
            )

            // Fire-and-forget cloud sync.
            launch(Dispatchers.IO) {
                val synced = postCheckInToCloud(sleepHours, sleepScore, rhr)
                if (synced) LocalStore.markCheckInSynced(ctx)
            }
        }
    }

    /** Post check-in to cloud. Returns true on success. */
    private suspend fun postCheckInToCloud(sleepHours: Float, sleepScore: Int, rhr: Int?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val rhrJson = if (rhr != null) ""","rhr":$rhr""" else ""
                val json = """{"sleep_hours":$sleepHours,"sleep_score":$sleepScore$rhrJson}"""
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$apiUrl/readiness/checkin")
                    .post(body)
                    .addHeader("Authorization", "Bearer $userToken")
                    .addHeader("X-User-ID", userId)
                    .addHeader("Content-Type", "application/json")
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Post warmup metric to cloud. Returns true on success. */
    private suspend fun postWarmupToCloud(date: String, medianMs: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val json = """{"date":"$date","median_ms":$medianMs}"""
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$apiUrl/readiness/warmup")
                    .post(body)
                    .addHeader("Authorization", "Bearer $userToken")
                    .addHeader("X-User-ID", userId)
                    .addHeader("Content-Type", "application/json")
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Confirm a recovery action in cloud so it affects the server readiness score. */
    fun confirmRecoveryAction(
        type: String,
        emoji: String,
        boostPoints: Int,
        selectedAtMs: Long,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val previousScore = _state.value.readinessScore
            val ok = withContext(Dispatchers.IO) {
                postRecoveryActionToCloud(type, emoji, boostPoints, selectedAtMs)
            }
            if (ok) {
                // Pull updated readiness with short retries to absorb eventual consistency.
                for (attempt in 0 until 3) {
                    tryEnrichFromCloud()
                    val nextScore = _state.value.readinessScore
                    if (nextScore != null && nextScore != previousScore) {
                        break
                    }
                    if (attempt < 2) delay(900L)
                }
            }
            onResult(ok)
        }
    }

    private suspend fun postRecoveryActionToCloud(
        type: String,
        emoji: String,
        boostPoints: Int,
        selectedAtMs: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val escapedType = type.replace("\\", "\\\\").replace("\"", "\\\"")
            val escapedEmoji = emoji.replace("\\", "\\\\").replace("\"", "\\\"")
            val json =
                """{"action_type":"$escapedType","boost_points":$boostPoints,"emoji":"$escapedEmoji","selected_at_ms":$selectedAtMs}"""
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$apiUrl/readiness/recovery-action")
                .post(body)
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("X-User-ID", userId)
                .addHeader("Content-Type", "application/json")
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private data class BriefFetchResult(
        val sessionSummary: String? = null,
        val handoffNextAction: String? = null,
        val success: Boolean = false,
        val errorReason: String? = null,
    )

    private suspend fun fetchBrief(): BriefFetchResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiUrl/reflection/next-brief")
                .get()
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("X-User-ID", userId)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    BriefFetchResult(
                        sessionSummary = extractString(body, "session_summary"),
                        handoffNextAction = extractString(body, "handoff_next_action"),
                        success = true,
                    )
                } else {
                    BriefFetchResult(errorReason = "next-brief HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            BriefFetchResult(errorReason = "next-brief ${e.javaClass.simpleName}")
        }
    }

    private fun extractString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    private data class ReadinessFetchResult(
        val score: String? = null,
        val breakdown: Map<String, Float> = emptyMap(),
        val message: String? = null,
        val hasCheckinToday: Boolean = false,
        val planningLine: String? = null,
        val success: Boolean = false,
        val errorReason: String? = null,
    )

    /** Best-effort cloud readiness fetch; never throws. */
    private suspend fun fetchReadinessData(): ReadinessFetchResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiUrl/readiness")
                .get()
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("X-User-ID", userId)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext ReadinessFetchResult()
                    val score = Regex(""""score"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)
                    val message = extractString(body, "message")
                    val hasCheckin = Regex(""""has_checkin_today"\s*:\s*(true|false)""")
                        .find(body)?.groupValues?.get(1) == "true"
                    val line = buildPlanningAccuracyLine(body)
                    val breakdown = parseReadinessBreakdown(body)
                    return@withContext ReadinessFetchResult(
                        score = score,
                        breakdown = breakdown,
                        message = message,
                        hasCheckinToday = hasCheckin,
                        planningLine = line,
                        success = true,
                    )
                } else {
                    return@withContext ReadinessFetchResult(errorReason = "readiness HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            return@withContext ReadinessFetchResult(errorReason = "readiness ${e.javaClass.simpleName}")
        }
        ReadinessFetchResult()
    }

    private fun buildPlanningAccuracyLine(json: String): String? {
        val planned = parseIntField(json, "last_session_planned_minutes", "yesterday_planned_minutes")
            ?: return null
        val actual = parseIntField(json, "last_session_actual_minutes", "yesterday_actual_minutes")
            ?: return null
        val diff = actual - planned
        val verdict = when {
            diff > 5  -> "overrun +${diff}m"
            diff < -5 -> "underrun ${diff}m"
            else      -> "on target"
        }
        return "Last session: ${planned}m planned → ${actual}m actual ($verdict)"
    }

    private fun parseIntField(json: String, vararg keys: String): Int? {
        keys.forEach { key ->
            val value = Regex("\"$key\"\\s*:\\s*(\\d+)")
                .find(json)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun parseReadinessBreakdown(json: String): Map<String, Float> {
        val keys = listOf(
            "sleep_hours",
            "sleep_score",
            "rhr",
            "session_load",
            "drain_score",
            "cooldown_index",
            "recovery_action",
        )
        return buildMap {
            keys.forEach { key ->
                val match = Regex("\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                    .find(json)
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
                if (match != null) put(key, match)
            }
        }
    }

    companion object {
        fun factory(userId: String, apiUrl: String, userToken: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    // This factory is used from composables where we don't have direct Application access.
                    // The AndroidViewModel variant needs the Application, which is provided by the
                    // default ViewModelProvider mechanism once we use the correct overload.
                    throw UnsupportedOperationException("Use appFactory instead")
                }
            }

        fun appFactory(application: Application, userId: String, apiUrl: String, userToken: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(application, userId, apiUrl, userToken) as T
            }
    }
}
