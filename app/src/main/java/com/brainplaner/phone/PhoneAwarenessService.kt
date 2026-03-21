package com.brainplaner.phone

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class PhoneAwarenessService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Cloud API Configuration
    private val CLOUD_API_URL = "http://192.168.0.23:8501"
    // For Render deployment, use: "https://brainplaner-api-beta.onrender.com"

    // Supabase for detailed event logging
    private val SUPABASE_URL = "https://mhmmiaqaqoddlkyziati.supabase.co"
    private val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1obW1pYXFhcW9kZGxreXppYXRpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc5NjQ2NDcsImV4cCI6MjA4MzU0MDY0N30.zN5bUHUWDqo2RASQkd-FQyTy01pwi_xFLVs2CpPZMXg"
    private val USER_TOKEN = SUPABASE_ANON_KEY  // For beta
    private val POLL_INTERVAL_MS = 5000L // Poll every 5 seconds

    // Live event batching
    private val liveEventBatch = mutableListOf<Map<String, Any>>()
    private val BATCH_SIZE = 10 // Send batch after 10 events
    private val BATCH_TIMEOUT_MS = 30000L // Or after 30 seconds
    private var lastBatchSubmitTime = System.currentTimeMillis()

    // Get user_id from stored preferences
    private var USER_ID: String? = null

    private var sessionId: String? = null
    private var unlockCount = 0
    private var screenOnSeconds = 0
    private var screenOnStartTime: Long? = null
    private var lastScreenState = "unknown"
    private var pollingJob: Job? = null
    private var isAutoDetected = false

    // Cooldown tracking — continues after session ends to measure post-session behavior
    private val COOLDOWN_DURATION_MS = 15 * 60 * 1000L // 15 minutes
    private var isInCooldown = false
    private var cooldownSessionId: String? = null  // session that triggered cooldown
    private var cooldownStartTime: Long? = null
    private var cooldownUnlockCount = 0
    private var cooldownScreenOnSeconds = 0
    private var cooldownTimerJob: Job? = null
    private var timeToFirstPickupSeconds: Double? = null  // null = no pickup yet

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    if (isInCooldown) {
                        // Cooldown phase: track separately
                        if (lastScreenState == "off") {
                            cooldownUnlockCount++
                            if (timeToFirstPickupSeconds == null) {
                                cooldownStartTime?.let { start ->
                                    timeToFirstPickupSeconds = (System.currentTimeMillis() - start) / 1000.0
                                    android.util.Log.i("PhoneAwareness", "Cooldown: first pickup at ${timeToFirstPickupSeconds}s")
                                }
                            }
                            android.util.Log.i("PhoneAwareness", "Cooldown: unlock (count=$cooldownUnlockCount)")
                            logEvent("unlock", mapOf("unlock_count" to cooldownUnlockCount, "phase" to "cooldown"))
                        }
                        lastScreenState = "on"
                        screenOnStartTime = System.currentTimeMillis()
                        updateNotification()
                        logEvent("screen_on", mapOf("phase" to "cooldown"))
                    } else {
                        // Active session phase: original behavior
                        if (lastScreenState == "off" && sessionId != null) {
                            unlockCount++
                            android.util.Log.i("PhoneAwareness", "Event: unlock from screen_on (count=$unlockCount)")
                            logEvent("unlock", mapOf("unlock_count" to unlockCount))
                        }
                        lastScreenState = "on"
                        screenOnStartTime = System.currentTimeMillis()
                        updateNotification()
                        android.util.Log.i("PhoneAwareness", "Event: screen_on")
                        logEvent("screen_on")
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    lastScreenState = "off"
                    screenOnStartTime?.let {
                        val duration = (System.currentTimeMillis() - it) / 1000
                        if (isInCooldown) {
                            cooldownScreenOnSeconds += duration.toInt()
                            android.util.Log.i("PhoneAwareness", "Cooldown: screen on for ${duration}s, total: ${cooldownScreenOnSeconds}s")
                        } else {
                            screenOnSeconds += duration.toInt()
                            android.util.Log.i("PhoneAwareness", "Screen was on for ${duration}s, total: ${screenOnSeconds}s")
                        }
                    }
                    screenOnStartTime = null
                    updateNotification()
                    if (isInCooldown) {
                        logEvent("screen_off", mapOf("phase" to "cooldown"))
                    } else {
                        android.util.Log.i("PhoneAwareness", "Event: screen_off")
                        logEvent("screen_off")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (isInCooldown) {
                        cooldownUnlockCount++
                        if (timeToFirstPickupSeconds == null) {
                            cooldownStartTime?.let { start ->
                                timeToFirstPickupSeconds = (System.currentTimeMillis() - start) / 1000.0
                                android.util.Log.i("PhoneAwareness", "Cooldown: first pickup (USER_PRESENT) at ${timeToFirstPickupSeconds}s")
                            }
                        }
                        android.util.Log.i("PhoneAwareness", "Cooldown: unlock USER_PRESENT (count=$cooldownUnlockCount)")
                        logEvent("unlock", mapOf("unlock_count" to cooldownUnlockCount, "phase" to "cooldown"))
                        updateNotification()
                    } else if (sessionId != null) {
                        unlockCount++
                        android.util.Log.i("PhoneAwareness", "Event: unlock from USER_PRESENT (count=$unlockCount)")
                        logEvent("unlock", mapOf("unlock_count" to unlockCount))
                        updateNotification()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Load user_id from SharedPreferences - but don't fail if missing
        // The service may start before user logs in, so we'll check again in onStartCommand
        val userId = UserAuth.getUserId(this)
        if (userId != null) {
            USER_ID = userId
            android.util.Log.i("PhoneAwareness", "Service onCreate() for user: $USER_ID")
        } else {
            android.util.Log.w("PhoneAwareness", "Service onCreate() but no user logged in yet")
            // Don't initialize USER_ID yet, will be set later
        }

        // Register screen state listeners
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        // Android 13+ requires explicit export flag for broadcast receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        createNotificationChannel()

        android.util.Log.i("PhoneAwareness", "=== Service onCreate() complete ===")

        // Note: startForeground() and startPolling() are called in onStartCommand()
        // which always runs after onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check if user is logged in - if not, don't restart automatically
        if (USER_ID == null) {
            val userId = UserAuth.getUserId(this)
            if (userId != null) {
                USER_ID = userId
                android.util.Log.i("PhoneAwareness", "User logged in, now tracking: $USER_ID")
            } else {
                android.util.Log.w("PhoneAwareness", "No user logged in, service will stop")
                stopSelf()
                return START_NOT_STICKY // Don't restart if no user
            }
        }

        val newSessionId = intent?.getStringExtra("session_id")
        val enablePolling = intent?.getBooleanExtra("enable_polling", false) ?: false

        android.util.Log.i(
            "PhoneAwareness",
            "onStartCommand(session_id=$newSessionId, enable_polling=$enablePolling, intent=${intent != null}, user=$USER_ID)"
        )

        // ALWAYS become foreground - required on Android 8+ to prevent crash
        startForeground(NOTIFICATION_ID, buildNotification())

        if (newSessionId != null) {
            // Manual session start from phone UI
            sessionId = newSessionId
            unlockCount = 0
            screenOnSeconds = 0
            screenOnStartTime = if (lastScreenState == "on") System.currentTimeMillis() else null
            isAutoDetected = false
            android.util.Log.i("PhoneAwareness", "Started tracking new session: $newSessionId")
        } else {
            // Polling mode (explicit or START_STICKY restart with null intent)
            isAutoDetected = true
            android.util.Log.i("PhoneAwareness", "Running in polling mode (foreground)")
        }

        // Ensure polling loop is always running
        if (pollingJob == null || pollingJob?.isActive != true) {
            android.util.Log.i("PhoneAwareness", "Starting/restarting polling loop")
            startPolling()
        } else {
            android.util.Log.d("PhoneAwareness", "Polling loop already active")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        pollingJob?.cancel()
        cooldownTimerJob?.cancel()

        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
            // Receiver might not be registered
        }

        // Submit metrics synchronously in background thread to avoid NetworkOnMainThreadException
        Thread {
            try {
                // If in cooldown, finish it now (submit what we have)
                if (isInCooldown) {
                    android.util.Log.i("PhoneAwareness", "Service destroyed during cooldown — submitting partial cooldown metrics")
                    finishCooldown()
                }

                // Flush any remaining live events in batch
                val flushSessionId = sessionId ?: cooldownSessionId
                flushSessionId?.let { sid ->
                    USER_ID?.let { uid ->
                        synchronized(liveEventBatch) {
                            if (liveEventBatch.isNotEmpty()) {
                                android.util.Log.i("PhoneAwareness", "Flushing remaining ${liveEventBatch.size} events on destroy")
                                submitLiveEventBatchSync(sid, uid)
                            }
                        }
                    }
                }

                submitPhoneMetricsBlocking()
            } catch (e: Exception) {
                android.util.Log.e("PhoneAwareness", "Error submitting metrics on destroy", e)
            }

            // Cleanup after metrics are sent
            try {
                serviceScope.cancel()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }.start()
    }

    private fun submitLiveEventBatchSync(currentSessionId: String, userId: String) {
        if (liveEventBatch.isEmpty()) return

        val batchToSend = liveEventBatch.toList()
        liveEventBatch.clear()

        try {
            val eventsJson = batchToSend.joinToString(",\n      ") { event ->
                val metadataJson = (event["metadata"] as? Map<*, *>)?.let { meta ->
                    if (meta.isEmpty()) "{}"
                    else "{" + meta.entries.joinToString(",") {
                        """"${it.key}":${if (it.value is String) "\"${it.value}\"" else it.value}"""
                    } + "}"
                } ?: "{}"

                """
                {
                    "event_type": "${event["event_type"]}",
                    "timestamp": "${event["timestamp"]}",
                    "device": "${event["device"]}",
                    "metadata": $metadataJson
                }
                """.trimIndent()
            }

            val json = """
                {
                    "events": [$eventsJson]
                }
            """.trimIndent()

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$CLOUD_API_URL/sessions/$currentSessionId/phone-events")
                .post(body)
                .addHeader("Authorization", "Bearer $USER_TOKEN")
                .addHeader("X-User-ID", userId)
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    android.util.Log.i("PhoneAwareness", "✓ Final batch submitted on destroy")
                } else {
                    android.util.Log.e("PhoneAwareness", "✗ Failed to submit final batch: ${response.code}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhoneAwareness", "Error in submitLiveEventBatchSync", e)
        }
    }

    private fun submitPhoneMetricsBlocking() {
        val currentSessionId = sessionId ?: return
        val userId = USER_ID ?: run {
            android.util.Log.w("PhoneAwareness", "Cannot submit metrics, no user logged in")
            return
        }

        // Calculate final screen time if screen is currently on
        screenOnStartTime?.let {
            val duration = (System.currentTimeMillis() - it) / 1000
            screenOnSeconds += duration.toInt()
        }

        android.util.Log.i(
            "PhoneAwareness",
            "Submitting phone metrics: unlocks=$unlockCount, screen_on=${screenOnSeconds}s"
        )

        try {
            val json = """
                {
                  "phone_unlock_count": $unlockCount,
                  "phone_screen_on_seconds": $screenOnSeconds
                }
            """.trimIndent()

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$CLOUD_API_URL/sessions/$currentSessionId/phone")
                .post(body)
                .addHeader("Authorization", "Bearer $USER_TOKEN")
                .addHeader("X-User-ID", userId)
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    android.util.Log.i("PhoneAwareness", "Phone metrics submitted successfully via Cloud API")
                    return // Success, no need for fallback
                } else {
                    val errorBody = response.body?.string() ?: ""
                    android.util.Log.e(
                        "PhoneAwareness",
                        "Failed to submit phone metrics via Cloud API: ${response.code} - $errorBody"
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhoneAwareness", "Cloud API unreachable for phone metrics", e)
        }

        // Fallback: submit phone metrics directly to Supabase session_metrics table
        android.util.Log.i("PhoneAwareness", "Falling back to Supabase for phone metrics")
        submitPhoneMetricsToSupabase(currentSessionId, userId)
    }

    private fun submitPhoneMetricsToSupabase(currentSessionId: String, userId: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val metrics = listOf(
                """{"session_id":"$currentSessionId","user_id":"$userId","metric_key":"phone_unlock_count","value_num":$unlockCount,"unit":"count","observed_ts":"$timestamp","created_at":"$timestamp"}""",
                """{"session_id":"$currentSessionId","user_id":"$userId","metric_key":"phone_screen_on_seconds","value_num":$screenOnSeconds,"unit":"seconds","observed_ts":"$timestamp","created_at":"$timestamp"}"""
            )

            val json = "[${metrics.joinToString(",")}]"
            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/session_metrics")
                .post(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    android.util.Log.i("PhoneAwareness", "✓ Phone metrics submitted directly to Supabase")
                } else {
                    val errorBody = response.body?.string() ?: ""
                    android.util.Log.e("PhoneAwareness", "✗ Supabase fallback failed: ${response.code} - $errorBody")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhoneAwareness", "Error in Supabase fallback for phone metrics", e)
        }
    }

    // ==================== Cooldown Phase ====================

    private fun startCooldown(completedSessionId: String) {
        isInCooldown = true
        cooldownSessionId = completedSessionId
        cooldownStartTime = System.currentTimeMillis()
        cooldownUnlockCount = 0
        cooldownScreenOnSeconds = 0
        timeToFirstPickupSeconds = null
        // If screen is currently on, start tracking screen time from now
        if (lastScreenState == "on") {
            screenOnStartTime = System.currentTimeMillis()
        }

        android.util.Log.i("PhoneAwareness", "=== Cooldown started for session $completedSessionId (${COOLDOWN_DURATION_MS / 60000} min) ===")
        updateNotification()

        // Start a timer that auto-stops cooldown after COOLDOWN_DURATION_MS
        cooldownTimerJob?.cancel()
        cooldownTimerJob = serviceScope.launch {
            delay(COOLDOWN_DURATION_MS)
            android.util.Log.i("PhoneAwareness", "Cooldown timer expired for session $completedSessionId")
            finishCooldown()
        }
    }

    private fun finishCooldown() {
        val cdSessionId = cooldownSessionId ?: return
        val userId = USER_ID ?: return

        // Finalize screen-on time if screen is currently on
        screenOnStartTime?.let {
            val duration = (System.currentTimeMillis() - it) / 1000
            cooldownScreenOnSeconds += duration.toInt()
            screenOnStartTime = null
        }

        val actualCooldownSeconds = cooldownStartTime?.let {
            ((System.currentTimeMillis() - it) / 1000).toInt()
        } ?: (COOLDOWN_DURATION_MS / 1000).toInt()

        android.util.Log.i(
            "PhoneAwareness",
            "=== Cooldown finished: session=$cdSessionId, unlocks=$cooldownUnlockCount, " +
            "screen_on=${cooldownScreenOnSeconds}s, first_pickup=${timeToFirstPickupSeconds}s, " +
            "duration=${actualCooldownSeconds}s ==="
        )

        // Flush remaining events
        synchronized(liveEventBatch) {
            if (liveEventBatch.isNotEmpty()) {
                submitLiveEventBatchSync(cdSessionId, userId)
            }
        }

        // Submit cooldown metrics to Cloud API
        submitCooldownMetrics(
            sessionId = cdSessionId,
            userId = userId,
            unlockCount = cooldownUnlockCount,
            screenOnSeconds = cooldownScreenOnSeconds,
            cooldownDurationSeconds = actualCooldownSeconds,
            timeToFirstPickupSeconds = timeToFirstPickupSeconds
        )

        // Reset cooldown state
        isInCooldown = false
        cooldownSessionId = null
        cooldownStartTime = null
        cooldownUnlockCount = 0
        cooldownScreenOnSeconds = 0
        cooldownTimerJob = null
        timeToFirstPickupSeconds = null
        unlockCount = 0
        updateNotification()
    }

    private fun submitCooldownMetrics(
        sessionId: String,
        userId: String,
        unlockCount: Int,
        screenOnSeconds: Int,
        cooldownDurationSeconds: Int,
        timeToFirstPickupSeconds: Double?
    ) {
        // Try Cloud API first
        try {
            val firstPickupJson = timeToFirstPickupSeconds?.let { "$it" } ?: "null"
            val json = """
                {
                  "cooldown_unlock_count": $unlockCount,
                  "cooldown_screen_on_seconds": $screenOnSeconds,
                  "cooldown_duration_seconds": $cooldownDurationSeconds,
                  "time_to_first_pickup_seconds": $firstPickupJson
                }
            """.trimIndent()

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$CLOUD_API_URL/sessions/$sessionId/cooldown")
                .post(body)
                .addHeader("Authorization", "Bearer $USER_TOKEN")
                .addHeader("X-User-ID", userId)
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    android.util.Log.i("PhoneAwareness", "✓ Cooldown metrics submitted via Cloud API")
                    return
                } else {
                    val errorBody = response.body?.string() ?: ""
                    android.util.Log.e("PhoneAwareness", "✗ Cloud API cooldown failed: ${response.code} - $errorBody")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhoneAwareness", "Cloud API unreachable for cooldown metrics", e)
        }

        // Fallback: submit cooldown metrics directly to Supabase session_metrics
        android.util.Log.i("PhoneAwareness", "Falling back to Supabase for cooldown metrics")
        submitCooldownMetricsToSupabase(sessionId, userId, unlockCount, screenOnSeconds, cooldownDurationSeconds, timeToFirstPickupSeconds)
    }

    private fun submitCooldownMetricsToSupabase(
        sessionId: String,
        userId: String,
        unlockCount: Int,
        screenOnSeconds: Int,
        cooldownDurationSeconds: Int,
        timeToFirstPickupSeconds: Double?
    ) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            // Compute derived metrics locally (mirrors Cloud API logic)
            val durationMinutes = cooldownDurationSeconds / 60.0
            val screenOnRatio = if (cooldownDurationSeconds > 0)
                (screenOnSeconds.toDouble() / cooldownDurationSeconds).coerceIn(0.0, 1.0) else 0.0
            val unlockRatePerMin = if (durationMinutes > 0)
                unlockCount / durationMinutes else 0.0

            // Compute behavior index (same formula as Cloud API)
            val unlocksPerHour = unlockRatePerMin * 60
            val unlockScore = (unlocksPerHour / 20.0).coerceIn(0.0, 1.0)
            val pickupScore = if (timeToFirstPickupSeconds == null) 0.0
                else (1.0 - (timeToFirstPickupSeconds / 600.0)).coerceIn(0.0, 1.0)
            val behaviorIndex = ((0.40 * unlockScore + 0.30 * screenOnRatio + 0.30 * pickupScore) * 100)
                .coerceIn(0.0, 100.0)
            val label = when {
                behaviorIndex < 15 -> "strong_recovery"
                behaviorIndex < 35 -> "normal_recovery"
                behaviorIndex < 60 -> "moderate_fatigue"
                else -> "high_fatigue"
            }

            val firstPickupJson = timeToFirstPickupSeconds?.let { "$it" } ?: "null"
            val json = """
                {
                  "session_id": "$sessionId",
                  "user_id": "$userId",
                  "cooldown_unlock_count": $unlockCount,
                  "cooldown_screen_on_seconds": $screenOnSeconds,
                  "cooldown_duration_seconds": $cooldownDurationSeconds,
                  "time_to_first_pickup_seconds": $firstPickupJson,
                  "cooldown_screen_on_ratio": ${"%.4f".format(screenOnRatio)},
                  "cooldown_unlock_rate_per_min": ${"%.4f".format(unlockRatePerMin)},
                  "cooldown_behavior_index": ${"%.1f".format(behaviorIndex)},
                  "cooldown_label": "$label",
                  "created_at": "$timestamp"
                }
            """.trimIndent()

            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/session_cooldowns")
                .post(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal,resolution=merge-duplicates")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    android.util.Log.i("PhoneAwareness", "✓ Cooldown metrics submitted to Supabase session_cooldowns")
                } else {
                    val errorBody = response.body?.string() ?: ""
                    android.util.Log.e("PhoneAwareness", "✗ Supabase cooldown fallback failed: ${response.code} - $errorBody")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhoneAwareness", "Error in Supabase cooldown fallback", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        pollingJob?.cancel()
        android.util.Log.i("PhoneAwareness", "=== Starting polling loop (interval=${POLL_INTERVAL_MS}ms, user=$USER_ID) ===")
        pollingJob = serviceScope.launch {
            var pollCount = 0
            while (isActive) {
                pollCount++
                try {
                    android.util.Log.d("PhoneAwareness", "Poll #$pollCount starting...")
                    pollSupabaseSession()
                } catch (e: Exception) {
                    android.util.Log.e("PhoneAwareness", "Poll #$pollCount error: ${e.message}", e)
                }
                delay(POLL_INTERVAL_MS)
            }
            android.util.Log.w("PhoneAwareness", "Polling loop ended after $pollCount polls")
        }
    }

    private suspend fun pollSupabaseSession() = withContext(Dispatchers.IO) {
        // Don't poll if no user is logged in
        val userId = USER_ID ?: return@withContext

        try {
            // Query the most recent session for this user (ANY status, sorted by start_ts)
            // We need to see both active and completed to properly detect start/stop
            val url = "$SUPABASE_URL/rest/v1/sessions?user_id=eq.$userId&order=start_ts.desc&limit=1&select=id,status,start_ts"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("PhoneAwareness", "Poll HTTP error: ${response.code}")
                    return@withContext
                }

                val responseBody = response.body?.string() ?: run {
                    android.util.Log.w("PhoneAwareness", "Poll: empty response body")
                    return@withContext
                }

                android.util.Log.d("PhoneAwareness", "Poll response (${responseBody.length} chars): ${responseBody.take(200)}")

                // Empty array = no sessions at all
                if (responseBody.trim() == "[]" || responseBody.trim() == "") {
                    android.util.Log.d("PhoneAwareness", "Poll: no sessions found for user")
                    return@withContext
                }

                // Parse response (simplified JSON parsing)
                val idMatch = Regex(""""id":"([^"]+)"""").find(responseBody)
                val statusMatch = Regex(""""status":"([^"]+)"""").find(responseBody)

                val remoteId = idMatch?.groupValues?.get(1)
                val remoteStatus = statusMatch?.groupValues?.get(1)

                if (remoteId == null || remoteStatus == null) {
                    android.util.Log.w("PhoneAwareness", "Poll: could not parse response: $responseBody")
                    return@withContext
                }

                android.util.Log.d("PhoneAwareness", "Poll: remote=$remoteId/$remoteStatus, local=$sessionId")

                // Sync logic: match Supabase state
                when {
                    remoteStatus == "active" && sessionId != remoteId -> {
                        // New session started remotely (by PC) or detected on startup
                        android.util.Log.i("PhoneAwareness", "Auto-detected new session: $remoteId (was: $sessionId)")
                        sessionId = remoteId
                        isAutoDetected = true
                        unlockCount = 0
                        screenOnSeconds = 0
                        screenOnStartTime = if (lastScreenState == "on") System.currentTimeMillis() else null

                        // Update notification with new session info
                        updateNotification()

                        // Notify MainActivity if possible
                        sendBroadcast(Intent("com.brainplaner.phone.SESSION_AUTO_STARTED").apply {
                            setPackage(packageName)
                            putExtra("session_id", remoteId)
                        })
                    }
                    remoteStatus != "active" && sessionId != null && !isInCooldown -> {
                        // Session stopped remotely (by PC) — enter cooldown phase instead of stopping
                        android.util.Log.i("PhoneAwareness", "Session completed: $sessionId — entering cooldown phase (${ COOLDOWN_DURATION_MS / 60000 } min)")
                        val completedSessionId = sessionId
                        sessionId = null  // no longer an active session
                        isAutoDetected = false

                        // Start cooldown tracking
                        startCooldown(completedSessionId!!)

                        sendBroadcast(Intent("com.brainplaner.phone.SESSION_AUTO_STOPPED").apply {
                            setPackage(packageName)
                        })
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhoneAwareness", "Poll failed", e)
        }
    }

    private fun logEvent(eventType: String, metadata: Map<String, Any> = emptyMap()) {
        // During cooldown, use the cooldown session ID
        val currentSessionId = if (isInCooldown) cooldownSessionId else sessionId
        if (currentSessionId == null) {
            android.util.Log.d("PhoneAwareness", "Skipping event '$eventType' (no sessionId yet)")
            return
        }

        val userId = USER_ID ?: run {
            android.util.Log.w("PhoneAwareness", "Cannot log event, no user logged in")
            return
        }

        // Create event for batch
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val event = mapOf(
            "event_type" to eventType,
            "timestamp" to timestamp,
            "device" to "phone",
            "metadata" to metadata
        )

        synchronized(liveEventBatch) {
            liveEventBatch.add(event)
            android.util.Log.d("PhoneAwareness", "Queued event: $eventType (batch size: ${liveEventBatch.size})")

            // Submit batch if size threshold reached or timeout exceeded
            val shouldSubmit = liveEventBatch.size >= BATCH_SIZE ||
                    (System.currentTimeMillis() - lastBatchSubmitTime) > BATCH_TIMEOUT_MS

            if (shouldSubmit) {
                submitLiveEventBatch(currentSessionId, userId)
            }
        }

        // Also log to Supabase for backup/debugging
        logEventToSupabase(currentSessionId, eventType, timestamp, metadata)
    }

    private fun submitLiveEventBatch(currentSessionId: String, userId: String) {
        if (liveEventBatch.isEmpty()) return

        val batchToSend = liveEventBatch.toList()
        liveEventBatch.clear()
        lastBatchSubmitTime = System.currentTimeMillis()

        android.util.Log.i("PhoneAwareness", "Submitting batch of ${batchToSend.size} phone events to Cloud API")

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Build JSON manually to ensure correct format
                val eventsJson = batchToSend.joinToString(",\n      ") { event ->
                    val metadataJson = (event["metadata"] as? Map<*, *>)?.let { meta ->
                        if (meta.isEmpty()) "{}"
                        else "{" + meta.entries.joinToString(",") {
                            """"${it.key}":${if (it.value is String) "\"${it.value}\"" else it.value}"""
                        } + "}"
                    } ?: "{}"

                    """
                    {
                        "event_type": "${event["event_type"]}",
                        "timestamp": "${event["timestamp"]}",
                        "device": "${event["device"]}",
                        "metadata": $metadataJson
                    }
                    """.trimIndent()
                }

                val json = """
                    {
                        "events": [$eventsJson]
                    }
                """.trimIndent()

                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$CLOUD_API_URL/sessions/$currentSessionId/phone-events")
                    .post(body)
                    .addHeader("Authorization", "Bearer $USER_TOKEN")
                    .addHeader("X-User-ID", userId)
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        android.util.Log.i("PhoneAwareness", "✓ Live events batch submitted successfully: $responseBody")
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        android.util.Log.e(
                            "PhoneAwareness",
                            "✗ Failed to submit live events batch: ${response.code} - $errorBody"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PhoneAwareness", "Error submitting live events batch", e)
            }
        }
    }

    private fun logEventToSupabase(currentSessionId: String, eventType: String, timestamp: String, metadata: Map<String, Any>) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val metadataJson = if (metadata.isEmpty()) {
                    "{}"
                } else {
                    "{" + metadata.entries.joinToString(",") {
                        """"${it.key}":${if (it.value is String) "\"${it.value}\"" else it.value}"""
                    } + "}"
                }

                val json = """
                    {
                      "session_id": "$currentSessionId",
                      "event_type": "$eventType",
                      "timestamp": "$timestamp",
                      "device": "phone",
                      "metadata": $metadataJson
                    }
                """.trimIndent()

                val body = json.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$SUPABASE_URL/rest/v1/phone_events_live?select=id")
                    .post(body)
                    .addHeader("apikey", SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "No error body"
                        android.util.Log.e("PhoneAwareness", "Failed to log $eventType to Supabase: ${response.code} - $errorBody")
                    } else {
                        val responseBody = response.body?.string()
                        android.util.Log.d(
                            "PhoneAwareness",
                            "Successfully logged $eventType (HTTP ${response.code})${if (!responseBody.isNullOrBlank()) ": $responseBody" else ""}"
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PhoneAwareness", "Error logging event: $eventType", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Brainplaner Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Phone awareness tracking for Brainplaner sessions"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isInCooldown) {
            "Brainplaner Cooldown"
        } else if (sessionId != null) {
            "Brainplaner Tracking Active" + if (isAutoDetected) " (Auto)" else ""
        } else {
            "Brainplaner Monitoring"
        }

        val text = if (isInCooldown) {
            val elapsed = cooldownStartTime?.let { (System.currentTimeMillis() - it) / 60000 } ?: 0
            val remaining = (COOLDOWN_DURATION_MS / 60000) - elapsed
            "Post-session tracking: ${remaining}min left | Unlocks: $cooldownUnlockCount"
        } else if (sessionId != null) {
            "Unlocks: $unlockCount | Screen: $lastScreenState"
        } else {
            "Waiting for session..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun updateNotification() {
        if (!canPostNotifications()) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val CHANNEL_ID = "brainplaner_tracking"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, PhoneAwarenessService::class.java).apply {
                putExtra("session_id", sessionId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startPollingMode(context: Context) {
            val intent = Intent(context, PhoneAwarenessService::class.java).apply {
                putExtra("enable_polling", true)
            }
            // Must use startForegroundService on Android 8+ since onStartCommand calls startForeground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneAwarenessService::class.java))
        }
    }
}
