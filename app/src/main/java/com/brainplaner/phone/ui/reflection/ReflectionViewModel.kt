package com.brainplaner.phone.ui.reflection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.brainplaner.phone.LocalStore
import com.brainplaner.phone.PhoneAwarenessService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class SessionTruthData(
    val plannedMinutes: Int = 0,
    val actualMinutes: Float = 0f,
    val focusedMinutes: Float = 0f,
    val completionRatio: Float = 0f,
    val pauseCount: Int = 0,
    val totalPauseSeconds: Float = 0f,
    val phoneUnlocks: Int? = null,
    val phoneScreenOnSeconds: Int? = null,
)

data class ReflectionUiState(
    val isSubmitting: Boolean = false,
    val isSubmitted: Boolean = false,
    val error: String? = null,
    val previousNextStep: String? = null,
    val sessionTruth: SessionTruthData? = null,
)

class ReflectionViewModel(
    application: Application,
    private val sessionId: String,
    private val userId: String,
    private val apiUrl: String,
    private val userToken: String,
) : AndroidViewModel(application) {

    private val ctx get() = getApplication<Application>()

    companion object {
        const val SUPABASE_URL = "https://mhmmiaqaqoddlkyziati.supabase.co"
        const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1obW1pYXFhcW9kZGxreXppYXRpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc5NjQ2NDcsImV4cCI6MjA4MzU0MDY0N30.zN5bUHUWDqo2RASQkd-FQyTy01pwi_xFLVs2CpPZMXg"

        fun factory(application: Application, sessionId: String, userId: String, apiUrl: String, userToken: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReflectionViewModel(application, sessionId, userId, apiUrl, userToken) as T
            }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ReflectionUiState())
    val state: StateFlow<ReflectionUiState> = _state

    init {
        _state.value = _state.value.copy(previousNextStep = LocalStore.getLastNextAction(ctx))
        // Try to sync any previously pending reflection from a past session.
        viewModelScope.launch(Dispatchers.IO) { trySyncPending() }
        // Fetch objective session data for Session Truth display.
        fetchSessionTruth()
    }

    fun submit(
        focusScore: Int,            // 1–5: validates in-session distraction signals
        drainScore: Int,            // 1–5: validates recovery cost (overshoot + post-session)
        alignmentScore: Int,        // 0-3: how aligned execution was with previous next-step
        handoffNextAction: String,  // required: concrete next action
        note: String? = null,
    ) {
        if (_state.value.isSubmitting) return
        if (alignmentScore !in 0..3) {
            _state.value = _state.value.copy(error = "Please select an alignment score (0-3).")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isSubmitting = true, error = null)

            // Save locally first — user is never blocked.
            LocalStore.savePendingReflection(
                ctx, sessionId, focusScore, drainScore, alignmentScore, handoffNextAction, note
            )

            // Map focus to legacy execution_rating for backend compatibility.
            val executionRating = when {
                focusScore >= 4 -> "good"
                focusScore == 3 -> "ok"
                else -> "poor"
            }
            val cloudOk = withContext(Dispatchers.IO) {
                postReflection(
                    focusScore,
                    drainScore,
                    alignmentScore,
                    handoffNextAction,
                    note,
                    executionRating,
                )
            }
            if (cloudOk) LocalStore.clearPendingReflection(ctx)

            // Start 15-min post-session cooldown tracking (phone unlocks, screen time).
            PhoneAwarenessService.startCooldownForSession(ctx, sessionId)

            _state.value = _state.value.copy(isSubmitting = false, isSubmitted = true)
        }
    }

    private suspend fun trySyncPending() {
        val pending = LocalStore.getPendingReflection(ctx) ?: return
        val rating = when {
            pending.focusScore >= 4 -> "good"
            pending.focusScore == 3 -> "ok"
            else -> "poor"
        }
        val ok = postReflection(
            pending.focusScore,
            pending.drainScore,
            pending.alignmentScore,
            pending.handoffNextAction,
            pending.note, rating,
            overrideSessionId = pending.sessionId
        )
        if (ok) LocalStore.clearPendingReflection(ctx)
    }

    private suspend fun postReflection(
        focusScore: Int,
        drainScore: Int,
        alignmentScore: Int,
        handoffNextAction: String,
        note: String?,
        executionRating: String,
        overrideSessionId: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val sid = overrideSessionId ?: sessionId
            // Don't try to sync local-only sessions to cloud.
            if (sid.startsWith("local-")) return@withContext false

            val noteJson = if (note.isNullOrBlank()) "null"
            else "\"${note.trim().take(280).replace("\\", "\\\\").replace("\"", "\\\"")}\""
            val nextActionEscaped = handoffNextAction.take(200)
                .replace("\\", "\\\\").replace("\"", "\\\"")

            val body = """
                {
                    "execution_rating": "$executionRating",
                    "next_tuning": "same",
                    "next_action": "continue_same_task",
                    "focus_score": $focusScore,
                    "drain_score": $drainScore,
                    "alignment_score": $alignmentScore,
                    "handoff_next_action": "$nextActionEscaped",
                    "note": $noteJson
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("$apiUrl/sessions/$sid/reflection")
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("X-User-ID", userId)
                .build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchSessionTruth() {
        if (sessionId.startsWith("local-")) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch session duration metrics from sessions table
                val sessionReq = Request.Builder()
                    .url(
                        "$SUPABASE_URL/rest/v1/sessions" +
                            "?id=eq.$sessionId" +
                            "&select=planned_minutes,actual_minutes,focused_minutes,completion_ratio,pause_count,total_pause_seconds"
                    )
                    .get()
                    .addHeader("apikey", SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .build()

                val sessionJson = client.newCall(sessionReq).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    response.body?.string()
                } ?: return@launch

                val sessions = JSONArray(sessionJson)
                if (sessions.length() == 0) return@launch
                val session = sessions.getJSONObject(0)

                // 2. Fetch phone metrics from session_metrics table
                val metricsReq = Request.Builder()
                    .url(
                        "$SUPABASE_URL/rest/v1/session_metrics" +
                            "?session_id=eq.$sessionId" +
                            "&metric_key=in.(phone_unlock_count,phone_screen_on_seconds)" +
                            "&select=metric_key,value_num"
                    )
                    .get()
                    .addHeader("apikey", SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .build()

                var phoneUnlocks: Int? = null
                var phoneScreenOnSeconds: Int? = null

                runCatching {
                    client.newCall(metricsReq).execute().use { response ->
                        if (response.isSuccessful) {
                            val metricsJson = response.body?.string()
                            if (metricsJson != null) {
                                val metrics = JSONArray(metricsJson)
                                for (i in 0 until metrics.length()) {
                                    val m = metrics.getJSONObject(i)
                                    when (m.getString("metric_key")) {
                                        "phone_unlock_count" -> phoneUnlocks = m.optInt("value_num")
                                        "phone_screen_on_seconds" -> phoneScreenOnSeconds = m.optInt("value_num")
                                    }
                                }
                            }
                        }
                    }
                }

                val truth = SessionTruthData(
                    plannedMinutes = session.optInt("planned_minutes", 0),
                    actualMinutes = session.optDouble("actual_minutes", 0.0).toFloat(),
                    focusedMinutes = session.optDouble("focused_minutes", 0.0).toFloat(),
                    completionRatio = session.optDouble("completion_ratio", 0.0).toFloat(),
                    pauseCount = session.optInt("pause_count", 0),
                    totalPauseSeconds = session.optDouble("total_pause_seconds", 0.0).toFloat(),
                    phoneUnlocks = phoneUnlocks,
                    phoneScreenOnSeconds = phoneScreenOnSeconds,
                )

                _state.value = _state.value.copy(sessionTruth = truth)
            } catch (e: Exception) {
                android.util.Log.w("ReflectionVM", "Failed to fetch session truth", e)
            }
        }
    }

}
