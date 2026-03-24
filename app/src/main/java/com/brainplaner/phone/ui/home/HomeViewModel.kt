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
)

class HomeViewModel(
    private val userId: String,
    private val apiUrl: String,
    private val userToken: String,
    private val supabaseUrl: String,
) : ViewModel() {

    // Reuse the same OkHttp pattern already established in MainActivity.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = HomeUiState(isLoading = true)
            // Fetch brief, readiness, and today's check-in status in parallel.
            val brief = async { fetchBrief() }
            val readiness = async { fetchReadiness() }
            val checkedIn = async { fetchCheckedInToday() }
            _state.value = brief.await().copy(
                readinessScore = readiness.await(),
                hasCheckedInToday = checkedIn.await(),
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
                    // Degrade gracefully — user is not blocked if the endpoint is unavailable.
                    HomeUiState(isLoading = false)
                }
            }
        } catch (e: Exception) {
            HomeUiState(isLoading = false)
        }
    }

    private fun extractString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)

    // Fetches the user's readiness score from the backend. Returns null when the
    // /readiness endpoint isn't available yet — HomeScreen shows "—" in that case.
    private suspend fun fetchReadiness(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$apiUrl/readiness")
                .get()
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("X-User-ID", userId)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    // score is a JSON number, e.g. "score": 74
                    Regex(""""score"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Returns true if there is already a daily_inputs row for today (UTC date).
    private suspend fun fetchCheckedInToday(): Boolean = withContext(Dispatchers.IO) {
        try {
            val today = java.time.LocalDate.now().toString() // yyyy-MM-dd
            val request = Request.Builder()
                .url("$supabaseUrl/rest/v1/daily_inputs?user_id=eq.$userId&date=eq.$today&select=id&limit=1")
                .get()
                .addHeader("apikey", userToken)
                .addHeader("Authorization", "Bearer $userToken")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    body.trim() != "[]"
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }

    // Posts (or upserts) today's sleep check-in to Supabase. Refreshes the full state on success.
    fun submitCheckIn(sleepHours: Float, sleepScore: Int, rhr: Int?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCheckInSubmitting = true)
            val success = withContext(Dispatchers.IO) {
                try {
                    val today = java.time.LocalDate.now().toString()
                    val rhrJson = if (rhr != null) ""","rhr":$rhr""" else ""
                    val json = """{"user_id":"$userId","date":"$today","sleep_hours":$sleepHours,"sleep_score":$sleepScore$rhrJson}"""
                    val body = json.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("$supabaseUrl/rest/v1/daily_inputs")
                        .post(body)
                        .addHeader("apikey", userToken)
                        .addHeader("Authorization", "Bearer $userToken")
                        .addHeader("Content-Type", "application/json")
                        // Upsert: if a row already exists for this user+date, update it.
                        .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                        .build()
                    client.newCall(request).execute().use { it.isSuccessful }
                } catch (e: Exception) {
                    false
                }
            }
            if (success) {
                // Re-fetch so readiness can update once the backend endpoint exists.
                load()
            } else {
                _state.value = _state.value.copy(isCheckInSubmitting = false)
            }
        }
    }

    companion object {
        fun factory(userId: String, apiUrl: String, userToken: String, supabaseUrl: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(userId, apiUrl, userToken, supabaseUrl) as T
            }
    }
}
