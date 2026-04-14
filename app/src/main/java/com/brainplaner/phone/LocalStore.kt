package com.brainplaner.phone

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Calendar
import java.util.UUID

/**
 * Local-first storage using SharedPreferences.
 * Saves check-ins, sessions, and reflections locally so the app
 * never blocks on cloud availability. Cloud sync is best-effort.
 */
object LocalStore {
    private const val PREFS = "brainplaner_local"
    private const val KEY_READINESS_TUNING_PROFILE = "readiness_tuning_profile"
    const val REFLECTION_STAGE_FORM = "form"
    const val REFLECTION_STAGE_SESSION_TRUTH = "session_truth"
    const val REFLECTION_STAGE_RECOVERY = "recovery"
    const val REFLECTION_STAGE_COOLDOWN = "cooldown"
    const val READINESS_PROFILE_DEFAULT = "default"
    const val READINESS_PROFILE_CONSERVATIVE = "conservative"
    const val READINESS_PROFILE_AGGRESSIVE = "aggressive"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    // ── Daily check-in ──────────────────────────────────────────

    fun hasCheckedInToday(ctx: Context): Boolean =
        prefs(ctx).getBoolean("checkin_${todayKey()}", false)

    fun saveCheckIn(ctx: Context, sleepHours: Float, sleepScore: Int, rhr: Int?) {
        prefs(ctx).edit()
            .putBoolean("checkin_${todayKey()}", true)
            .putFloat("checkin_sleep_hours_${todayKey()}", sleepHours)
            .putInt("checkin_sleep_score_${todayKey()}", sleepScore)
            .apply {
                if (rhr != null) putInt("checkin_rhr_${todayKey()}", rhr)
            }
            .putBoolean("checkin_synced_${todayKey()}", false)
            .apply()
    }

    fun isCheckInSynced(ctx: Context): Boolean =
        prefs(ctx).getBoolean("checkin_synced_${todayKey()}", false)

    fun markCheckInSynced(ctx: Context) {
        prefs(ctx).edit().putBoolean("checkin_synced_${todayKey()}", true).apply()
    }

    fun getCheckInData(ctx: Context): Triple<Float, Int, Int?>? {
        val p = prefs(ctx)
        val key = todayKey()
        if (!p.getBoolean("checkin_$key", false)) return null
        val sleep = p.getFloat("checkin_sleep_hours_$key", 7f)
        val score = p.getInt("checkin_sleep_score_$key", 70)
        val rhr = if (p.contains("checkin_rhr_$key")) p.getInt("checkin_rhr_$key", 0) else null
        return Triple(sleep, score, rhr)
    }

    fun clearCheckIn(ctx: Context) {
        val key = todayKey()
        prefs(ctx).edit()
            .remove("checkin_$key")
            .remove("checkin_sleep_hours_$key")
            .remove("checkin_sleep_score_$key")
            .remove("checkin_rhr_$key")
            .remove("checkin_synced_$key")
            .apply()
    }

    // ── Local readiness estimate ────────────────────────────────

    /** Simple offline readiness score based on check-in data. */
    fun estimateReadiness(sleepHours: Float, sleepScore: Int): Int {
        // Weighted: sleep_score contributes 60%, sleep_hours 40%
        val hoursNorm = ((sleepHours - 4f) / 6f).coerceIn(0f, 1f) * 100f
        return ((sleepScore * 0.6f) + (hoursNorm * 0.4f)).toInt().coerceIn(0, 100)
    }

    // ── Session tracking ────────────────────────────────────────

    fun getActiveSession(ctx: Context): ActiveSession? {
        val p = prefs(ctx)
        val id = p.getString("session_id", null) ?: return null
        return ActiveSession(
            id = id,
            startMs = p.getLong("session_start_ms", 0L),
            plannedMinutes = p.getInt("session_planned_min", 45),
            cloudSynced = p.getBoolean("session_cloud_synced", false),
            isPaused = p.getBoolean("session_paused", false),
            totalPauseMs = p.getLong("session_total_pause_ms", 0L),
            pauseStartMs = p.getLong("session_pause_start_ms", 0L),
        )
    }

    fun saveSessionStart(ctx: Context, sessionId: String, plannedMinutes: Int, cloudSynced: Boolean) {
        prefs(ctx).edit()
            .putString("session_id", sessionId)
            .putLong("session_start_ms", System.currentTimeMillis())
            .putInt("session_planned_min", plannedMinutes)
            .putBoolean("session_cloud_synced", cloudSynced)
            .apply()
    }

    fun markSessionCloudSynced(ctx: Context, cloudSessionId: String) {
        prefs(ctx).edit()
            .putString("session_id", cloudSessionId)
            .putBoolean("session_cloud_synced", true)
            .apply()
    }

    fun clearActiveSession(ctx: Context) {
        prefs(ctx).edit()
            .remove("session_id")
            .remove("session_start_ms")
            .remove("session_planned_min")
            .remove("session_cloud_synced")
            .remove("session_paused")
            .remove("session_total_pause_ms")
            .remove("session_pause_start_ms")
            .apply()
    }

    fun saveSessionPaused(ctx: Context) {
        prefs(ctx).edit()
            .putBoolean("session_paused", true)
            .putLong("session_pause_start_ms", System.currentTimeMillis())
            .apply()
    }

    fun saveSessionResumed(ctx: Context) {
        val p = prefs(ctx)
        val pauseStartMs = p.getLong("session_pause_start_ms", 0L)
        val previousTotal = p.getLong("session_total_pause_ms", 0L)
        val thisPauseMs = if (pauseStartMs > 0L) System.currentTimeMillis() - pauseStartMs else 0L
        p.edit()
            .putBoolean("session_paused", false)
            .putLong("session_total_pause_ms", previousTotal + thisPauseMs)
            .putLong("session_pause_start_ms", 0L)
            .apply()
    }

    fun generateLocalSessionId(): String = "local-${UUID.randomUUID()}"

    // ── Last reflection handoff (for continuity display) ────────

    fun saveLastReflection(ctx: Context, nextAction: String, summary: String?) {
        prefs(ctx).edit()
            .putString("last_next_action", nextAction)
            .putString("last_summary", summary)
            .apply()
    }

    fun getLastNextAction(ctx: Context): String? =
        prefs(ctx).getString("last_next_action", null)

    fun getLastSummary(ctx: Context): String? =
        prefs(ctx).getString("last_summary", null)

    // ── Pending reflection (save locally, sync later) ───────────

    fun savePendingReflection(
        ctx: Context,
        sessionId: String,
        focusScore: Int,
        drainScore: Int,
        alignmentScore: Int,
        handoffNextAction: String,
        note: String?,
    ) {
        prefs(ctx).edit()
            .putString("pending_refl_session_id", sessionId)
            .putInt("pending_refl_focus", focusScore)
            .putInt("pending_refl_drain", drainScore)
            .putInt("pending_refl_alignment", alignmentScore)
            .putString("pending_refl_next_action", handoffNextAction)
            .putString("pending_refl_note", note)
            .putBoolean("pending_refl_exists", true)
            .apply()

        // Also update continuity display
        saveLastReflection(ctx, handoffNextAction, null)
    }

    fun hasPendingReflection(ctx: Context): Boolean =
        prefs(ctx).getBoolean("pending_refl_exists", false)

    data class PendingReflection(
        val sessionId: String,
        val focusScore: Int,
        val drainScore: Int,
        val alignmentScore: Int,
        val handoffNextAction: String,
        val note: String?,
    )

    fun getPendingReflection(ctx: Context): PendingReflection? {
        val p = prefs(ctx)
        if (!p.getBoolean("pending_refl_exists", false)) return null
        return PendingReflection(
            sessionId = p.getString("pending_refl_session_id", "") ?: "",
            focusScore = p.getInt("pending_refl_focus", 0),
            drainScore = p.getInt("pending_refl_drain", 0),
            alignmentScore = p.getInt("pending_refl_alignment", -1),
            handoffNextAction = p.getString("pending_refl_next_action", "") ?: "",
            note = p.getString("pending_refl_note", null),
        )
    }

    fun clearPendingReflection(ctx: Context) {
        prefs(ctx).edit()
            .remove("pending_refl_session_id")
            .remove("pending_refl_focus")
            .remove("pending_refl_drain")

            .remove("pending_refl_next_action")
            .remove("pending_refl_note")
            .putBoolean("pending_refl_exists", false)
            .apply()
    }

    // ── Reflection resume routing ──────────────────────────────

    fun savePendingReflectionRoute(ctx: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        prefs(ctx).edit()
            .putString("pending_refl_route_session_id", sessionId)
            .putBoolean("pending_refl_route_exists", true)
            .apply()
    }

    fun getPendingReflectionRouteSessionId(ctx: Context): String? {
        val p = prefs(ctx)
        if (!p.getBoolean("pending_refl_route_exists", false)) return null
        return p.getString("pending_refl_route_session_id", null)?.takeIf { it.isNotBlank() }
    }

    fun clearPendingReflectionRoute(ctx: Context) {
        prefs(ctx).edit()
            .remove("pending_refl_route_session_id")
            .remove("pending_refl_route_stage")
            .putBoolean("pending_refl_route_exists", false)
            .apply()
    }

    fun savePendingReflectionStage(ctx: Context, stage: String) {
        prefs(ctx).edit()
            .putString("pending_refl_route_stage", stage)
            .apply()
    }

    fun getPendingReflectionStage(ctx: Context): String {
        return prefs(ctx).getString("pending_refl_route_stage", REFLECTION_STAGE_FORM)
            ?: REFLECTION_STAGE_FORM
    }

    // ── Cognitive warm-up ────────────────────────────────────────

    fun isWarmupEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("warmup_enabled", false)

    fun setWarmupEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("warmup_enabled", enabled).apply()
    }

    // ── Readiness tuning profile ───────────────────────────────

    fun getReadinessTuningProfile(ctx: Context): String {
        val value = prefs(ctx).getString(KEY_READINESS_TUNING_PROFILE, READINESS_PROFILE_DEFAULT)
            ?: READINESS_PROFILE_DEFAULT
        return when (value) {
            READINESS_PROFILE_CONSERVATIVE,
            READINESS_PROFILE_AGGRESSIVE,
            READINESS_PROFILE_DEFAULT,
            -> value
            else -> READINESS_PROFILE_DEFAULT
        }
    }

    fun setReadinessTuningProfile(ctx: Context, profile: String) {
        val normalized = when (profile.lowercase(Locale.US)) {
            READINESS_PROFILE_CONSERVATIVE -> READINESS_PROFILE_CONSERVATIVE
            READINESS_PROFILE_AGGRESSIVE -> READINESS_PROFILE_AGGRESSIVE
            else -> READINESS_PROFILE_DEFAULT
        }
        prefs(ctx).edit().putString(KEY_READINESS_TUNING_PROFILE, normalized).apply()
    }

    fun saveWarmupResult(ctx: Context, medianMs: Int) {
        prefs(ctx).edit()
            .putInt("warmup_${todayKey()}", medianMs)
            .putBoolean("warmup_synced_${todayKey()}", false)
            .apply()
    }

    fun getTodayWarmupData(ctx: Context): Pair<String, Int>? {
        val key = todayKey()
        val p = prefs(ctx)
        val metricKey = "warmup_$key"
        if (!p.contains(metricKey)) return null
        return key to p.getInt(metricKey, 0)
    }

    fun isWarmupSyncedToday(ctx: Context): Boolean =
        prefs(ctx).getBoolean("warmup_synced_${todayKey()}", false)

    fun markWarmupSyncedToday(ctx: Context) {
        prefs(ctx).edit().putBoolean("warmup_synced_${todayKey()}", true).apply()
    }

    /** Median of last 14 days' warm-up results (excluding today). */
    fun getWarmupBaseline(ctx: Context): Int? {
        val p = prefs(ctx)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val results = mutableListOf<Int>()
        for (i in 1..14) {
            cal.time = Date()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val key = "warmup_${fmt.format(cal.time)}"
            if (p.contains(key)) {
                results.add(p.getInt(key, 0))
            }
        }
        if (results.isEmpty()) return null
        results.sort()
        val n = results.size
        return if (n % 2 == 0) (results[n / 2 - 1] + results[n / 2]) / 2 else results[n / 2]
    }

    // ── Pending recovery ────────────────────────────────────────

    data class PendingRecoveryData(
        val type: String,
        val emoji: String,
        val boostPoints: Int,
        val selectedAt: Long,
    )

    fun savePendingRecovery(ctx: Context, type: String, emoji: String, boostPoints: Int) {
        prefs(ctx).edit()
            .putString("recovery_type", type)
            .putString("recovery_emoji", emoji)
            .putInt("recovery_boost", boostPoints)
            .putLong("recovery_selected_at", System.currentTimeMillis())
            .putBoolean("recovery_pending", true)
            .apply()
    }

    fun getPendingRecovery(ctx: Context): PendingRecoveryData? {
        val p = prefs(ctx)
        if (!p.getBoolean("recovery_pending", false)) return null
        return PendingRecoveryData(
            type = p.getString("recovery_type", "") ?: "",
            emoji = p.getString("recovery_emoji", "") ?: "",
            boostPoints = p.getInt("recovery_boost", 0),
            selectedAt = p.getLong("recovery_selected_at", 0L),
        )
    }

    fun clearPendingRecovery(ctx: Context) {
        prefs(ctx).edit()
            .remove("recovery_type")
            .remove("recovery_emoji")
            .remove("recovery_boost")
            .remove("recovery_selected_at")
            .putBoolean("recovery_pending", false)
            .apply()
    }

    data class ActiveSession(
        val id: String,
        val startMs: Long,
        val plannedMinutes: Int,
        val cloudSynced: Boolean,
        val isPaused: Boolean = false,
        val totalPauseMs: Long = 0L,
        val pauseStartMs: Long = 0L,
    )
}
