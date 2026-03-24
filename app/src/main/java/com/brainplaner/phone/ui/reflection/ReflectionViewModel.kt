package com.brainplaner.phone.ui.reflection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ReflectionUiState(
    val isSubmitting: Boolean = false,
    val isSubmitted: Boolean = false,
    val error: String? = null,
)

class ReflectionViewModel(
    private val sessionId: String,
    private val userId: String,
    private val apiUrl: String,
    private val userToken: String,
) : ViewModel() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ReflectionUiState())
    val state: StateFlow<ReflectionUiState> = _state

    fun submit(
        alignmentScore: Int,        // 0 = not at all, 3 = fully aligned
        emotionLabel: String,       // proud / neutral / mixed / frustrated / relieved
        handoffNextAction: String,  // required: concrete next action
        blockerTag: String? = null, // only when alignmentScore <= 1
        note: String? = null,
    ) {
        if (_state.value.isSubmitting) return
        viewModelScope.launch {
            _state.value = ReflectionUiState(isSubmitting = true)

            // Map alignment to the legacy required execution_rating field.
            val executionRating = when {
                alignmentScore >= 2 -> "good"
                alignmentScore == 1 -> "ok"
                else -> "poor"
            }

            val result = postReflection(
                alignmentScore = alignmentScore,
                emotionLabel = emotionLabel,
                handoffNextAction = handoffNextAction,
                blockerTag = blockerTag,
                note = note,
                executionRating = executionRating,
            )

            _state.value = result.fold(
                onSuccess = { ReflectionUiState(isSubmitted = true) },
                onFailure = { ReflectionUiState(error = it.message ?: "Submit failed") },
            )
        }
    }

    private suspend fun postReflection(
        alignmentScore: Int,
        emotionLabel: String,
        handoffNextAction: String,
        blockerTag: String?,
        note: String?,
        executionRating: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val noteJson = if (note.isNullOrBlank()) "null"
                else "\"${note.trim().take(280).replace("\\", "\\\\").replace("\"", "\\\"")}\""
            val blockerJson = if (blockerTag == null) "null" else "\"$blockerTag\""
            val nextActionEscaped = handoffNextAction.take(200)
                .replace("\\", "\\\\").replace("\"", "\\\"")

            val body = """
                {
                    "execution_rating": "$executionRating",
                    "next_tuning": "same",
                    "next_action": "continue_same_task",
                    "alignment_score": $alignmentScore,
                    "emotion_label": "$emotionLabel",
                    "blocker_tag": $blockerJson,
                    "handoff_next_action": "$nextActionEscaped",
                    "note": $noteJson
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("$apiUrl/sessions/$sessionId/reflection")
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("X-User-ID", userId)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("HTTP ${response.code}: ${response.body?.string()?.take(200)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        fun factory(sessionId: String, userId: String, apiUrl: String, userToken: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReflectionViewModel(sessionId, userId, apiUrl, userToken) as T
            }
    }
}
