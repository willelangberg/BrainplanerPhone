package com.brainplaner.phone.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    val coachWhisper: String? = null,
    val handoffNextAction: String? = null,
    val readinessScore: String? = null,
    val hasCheckedInToday: Boolean = false,
    val isCheckInSubmitting: Boolean = false,
    val checkInError: String? = null,      // non-null when last submit failed
)

class HomeViewModel(
    private val userId: String,
    private val apiUrl: String,
    private val userToken: String,
) : ViewModel() {

    // Reuse the same OkHttp pattern already established in MainActivity.
    private val client = OkHttpClient.Builder()
        .connectTimeout(70, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = HomeUiState(isLoading = true)
            // Single /readiness call gives both score and has_checkin_today.
            val brief = async { fetchBrief() }
            val readinessData = async { fetchReadinessData() }
            val (score, hasCheckin) = readinessData.await()
            _state.value = brief.await().copy(
                readinessScore = score,
                hasCheckedInToday = hasCheckin,
            )
        }
    }

    private suspend fun fetchBrief(): HomeUiState = withContext(Dispatchers.IO) {
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
                    HomeUiState(
                        isLoading = false,
                        sessionSummary = extractString(body, "session_summary"),
                        coachWhisper = extractString(body, "coach_whisper"),
                        handoffNextAction = extractString(body, "handoff_next_action"),
                    )
                } else {
                    HomeUiState(isLoading = false)
                }
            }
        } catch (e: Exception) {
            HomeUiState(isLoading = false)
        }
    }

    private fun extractString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    // Single call to /readiness — returns (scoreString, hasCheckinToday).
    private suspend fun fetchReadinessData(): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiUrl/readiness")
                .get()
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("X-User-ID", userId)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use Pair(null, false)
                    val score = Regex(""""score"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)
                    val hasCheckin = Regex(""""has_checkin_today"\s*:\s*(true|false)""")
                        .find(body)?.groupValues?.get(1) == "true"
                    Pair(score, hasCheckin)
                } else Pair(null, false)
            }
        } catch (e: Exception) {
            Pair(null, false)
        }
    }

    // Posts today's sleep check-in via Cloud API (avoids Supabase RLS with anon key).
    fun submitCheckIn(sleepHours: Float, sleepScore: Int, rhr: Int?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCheckInSubmitting = true, checkInError = null)
            val success = withContext(Dispatchers.IO) {
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
                } catch (e: Exception) {
                    false
                }
            }
            if (success) {
                load()
            } else {
                _state.value = _state.value.copy(
                    isCheckInSubmitting = false,
                    checkInError = "Could not save. Server may be waking up — try again.",
                )
            }
        }
    }

    companion object {
        fun factory(userId: String, apiUrl: String, userToken: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(userId, apiUrl, userToken) as T
            }
    }
}
