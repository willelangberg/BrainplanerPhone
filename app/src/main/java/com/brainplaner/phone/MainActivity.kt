package com.brainplaner.phone

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.brainplaner.phone.ui.login.LoginScreen
import com.brainplaner.phone.ui.navigation.AppNavigation
import com.brainplaner.phone.ui.theme.BrainplanerPhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    // Configure OkHttp with longer timeouts (Render free tier can be slow to wake up)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Cloud API Configuration
    // Use local network URL when on same Wi-Fi: http://192.168.0.23:8501
    // Use Render when remote: https://brainplaner-api-beta.onrender.com
    private val CLOUD_API_URL = "https://brainplaner-api-beta.onrender.com"
    private val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1obW1pYXFhcW9kZGxreXppYXRpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc5NjQ2NDcsImV4cCI6MjA4MzU0MDY0N30.zN5bUHUWDqo2RASQkd-FQyTy01pwi_xFLVs2CpPZMXg"
    private val USER_TOKEN = SUPABASE_ANON_KEY  // For beta

    // Supabase for legacy polling
    private val SUPABASE_URL = "https://mhmmiaqaqoddlkyziati.supabase.co"

    // Track active session (phone is source of truth)
    private var activeSessionId: String? = null
    private var activePlannedMinutes: Int = 60
    private var uiStateCallback: ((UiState) -> Unit)? = null
    private var isReceiverRegistered = false

    // Permission launcher for Android 13+
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Optional: update UI state if needed
    }

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.brainplaner.phone.SESSION_AUTO_STARTED" -> {
                    val sessionId = intent.getStringExtra("session_id")
                    activeSessionId = sessionId
                    // uiStateCallback is null when SessionControllerScreen isn't composed (user is on
                    // HomeScreen). The session is still tracked; the callback fires once the user
                    // navigates to the session screen.
                    if (uiStateCallback != null) {
                        uiStateCallback?.invoke(UiState.Success("✓ Session auto-started\n${sessionId?.take(8)}...\n🖥️ Initiated by PC"))
                    } else {
                        android.util.Log.d("MainActivity", "SESSION_AUTO_STARTED: uiStateCallback is null (user not on session screen)")
                    }
                }
                "com.brainplaner.phone.SESSION_AUTO_STOPPED" -> {
                    activeSessionId = null
                    if (uiStateCallback != null) {
                        uiStateCallback?.invoke(UiState.Success("✓ Session auto-stopped\n🖥️ Stopped by PC"))
                    } else {
                        android.util.Log.d("MainActivity", "SESSION_AUTO_STOPPED: uiStateCallback is null (user not on session screen)")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is logged in
        if (!UserAuth.isLoggedIn(this)) {
            setContent {
                BrainplanerPhoneTheme {
                    LoginScreen(
                        onLogin = { userId ->
                            UserAuth.saveUserId(this, userId)
                            recreate()
                        }
                    )
                }
            }
            return
        }

        // Request notification permission for Android 13+
        ensureNotificationPermission()

        // Register broadcast receiver for auto-detected sessions
        val filter = IntentFilter().apply {
            addAction("com.brainplaner.phone.SESSION_AUTO_STARTED")
            addAction("com.brainplaner.phone.SESSION_AUTO_STOPPED")
        }

        // Register receiver with explicit export flag on all API levels.
        // This avoids the "missing RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED" lint error.
        ContextCompat.registerReceiver(
            this,
            sessionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true

        // Start polling service in background to detect PC-initiated sessions
        PhoneAwarenessService.startPollingMode(this)

        val userId = UserAuth.getUserId(this) ?: return

        setContent {
            BrainplanerPhoneTheme {
                AppNavigation(
                    userId = userId,
                    apiUrl = CLOUD_API_URL,
                    userToken = USER_TOKEN,
                    supabaseUrl = SUPABASE_URL,
                    getActiveSessionId = { activeSessionId },
                    onStartSession = { minutes -> startSession(minutes) },
                    onStopSession = { stopSession() },
                    onLogout = {
                        PhoneAwarenessService.stop(this)
                        UserAuth.clearUserId(this)
                        recreate()
                    },
                    onStateChanged = { callback -> uiStateCallback = callback },
                )
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Create session directly via Supabase (fallback when Cloud API is down/misconfigured)
    private suspend fun createSessionDirectly(userId: String, plannedMinutes: Int = 60): Result<String> = withContext(Dispatchers.IO) {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())

            val json = """
                {
                  "user_id": "$userId",
                  "start_ts": "$timestamp",
                  "status": "active",
                  "planned_minutes": 60
                }
            """.trimIndent()

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/sessions")
                .post(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    android.util.Log.i("MainActivity", "Supabase direct: $responseBody")

                    // Extract session_id (it's just "id" in Supabase)
                    val sessionId = Regex(""""id":"([^"]+)"""")
                        .find(responseBody)
                        ?.groupValues
                        ?.get(1)

                    if (sessionId != null) {
                        Result.success(sessionId)
                    } else {
                        Result.failure(Exception("Failed to parse session ID from Supabase response"))
                    }
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Result.failure(Exception("Supabase error ${response.code}: $errorBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Start = POST to Cloud API /sessions/start
    private suspend fun startSession(plannedMinutes: Int = 60): Result<String> = withContext(Dispatchers.IO) {
        try {
            val userId = UserAuth.getUserId(this@MainActivity) ?: return@withContext Result.failure(
                Exception("No user logged in")
            )

            val url = "$CLOUD_API_URL/sessions/start"
            android.util.Log.i("MainActivity", "Starting session for user: $userId")
            android.util.Log.i("MainActivity", "POST $url")

            val json = """
                {
                  "planned_minutes": $plannedMinutes
                }
            """.trimIndent()

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Authorization", "Bearer $USER_TOKEN")
                .addHeader("X-User-ID", userId)
                .addHeader("Content-Type", "application/json")
                .build()

            android.util.Log.i("MainActivity", "Sending request...")
            client.newCall(request).execute().use { response ->
                android.util.Log.i("MainActivity", "Response code: ${response.code}")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    android.util.Log.i("MainActivity", "Response body: $responseBody")

                    // Extract session_id from response
                    val sessionId = Regex(""""session_id":"([^"]+)"""")
                        .find(responseBody)
                        ?.groupValues
                        ?.get(1)

                    if (sessionId != null) {
                        activeSessionId = sessionId
                        activePlannedMinutes = plannedMinutes
                        android.util.Log.i("MainActivity", "Session started: $sessionId")
                        // Start phone awareness tracking
                        PhoneAwarenessService.start(this@MainActivity, sessionId)
                        Result.success("✓ Started session\n${sessionId.take(8)}...\n📱 Tracking active")
                    } else {
                        android.util.Log.e("MainActivity", "Failed to parse session_id from: $responseBody")
                        Result.failure(Exception("Created but couldn't parse session_id. Response: $responseBody"))
                    }
                } else {
                    val errorBody = response.body?.string() ?: ""
                    android.util.Log.e("MainActivity", "HTTP error ${response.code}: $errorBody")

                    // 409 = active session already exists — auto-end it and retry
                    if (response.code == 409 || (response.code == 500 && errorBody.contains("active session"))) {
                        val staleId = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
                            .find(errorBody)?.value
                        if (staleId != null) {
                            android.util.Log.i("MainActivity", "Auto-ending stale session: $staleId")
                            val endReq = Request.Builder()
                                .url("$CLOUD_API_URL/sessions/$staleId/end")
                                .post("{}".toRequestBody("application/json".toMediaType()))
                                .addHeader("Authorization", "Bearer $USER_TOKEN")
                                .addHeader("X-User-ID", userId)
                                .build()
                            runCatching { client.newCall(endReq).execute().close() }
                            // Retry start
                            return@withContext startSession(plannedMinutes)
                        }
                    }

                    // Other Cloud API error - fall back to Supabase directly
                    android.util.Log.i("MainActivity", "Cloud API error ${response.code}, falling back to Supabase")
                    val directResult = createSessionDirectly(userId, plannedMinutes)
                    return@withContext directResult.fold(
                        onSuccess = { sessionId ->
                            activeSessionId = sessionId
                            activePlannedMinutes = plannedMinutes
                            PhoneAwarenessService.start(this@MainActivity, sessionId)
                            Result.success("✓ Started session (direct)\n${sessionId.take(8)}...\n📱 Tracking active")
                        },
                        onFailure = { Result.failure(Exception("Cloud API (${response.code}) & Supabase failed: ${it.message}")) }
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Exception starting session (Cloud API down?)", e)
            android.util.Log.i("MainActivity", "Falling back to direct Supabase session creation")

            // Cloud API unreachable (DNS error, timeout, etc.) - fall back to Supabase
            val userId = UserAuth.getUserId(this@MainActivity)
            if (userId != null) {
                val directResult = createSessionDirectly(userId, plannedMinutes)
                return@withContext directResult.fold(
                    onSuccess = { sessionId ->
                        activeSessionId = sessionId
                        activePlannedMinutes = plannedMinutes
                        PhoneAwarenessService.start(this@MainActivity, sessionId)
                        Result.success("✓ Started session (direct)\n${sessionId.take(8)}...\n📱 Tracking active")
                    },
                    onFailure = { Result.failure(Exception("Cloud API & Supabase failed: ${it.message}")) }
                )
            } else {
                Result.failure(Exception("No user logged in"))
            }
        }
    }

    // Stop session directly via Supabase (fallback when Cloud API is down)
    private suspend fun stopSessionDirectly(sessionId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())

            val json = """
                {
                  "status": "completed",
                  "end_ts": "$timestamp"
                }
            """.trimIndent()

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/sessions?id=eq.$sessionId")
                .patch(body)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("✓ Stopped session (direct)\n${sessionId.take(8)}...\n📱 Tracking stopped")
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Result.failure(Exception("Supabase error ${response.code}: $errorBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Stop = POST to Cloud API /sessions/{id}/end
    private suspend fun stopSession(): Result<String> = withContext(Dispatchers.IO) {
        val id = activeSessionId
        if (id == null) {
            return@withContext Result.failure(Exception("No active session"))
        }

        val userId = UserAuth.getUserId(this@MainActivity) ?: return@withContext Result.failure(
            Exception("No user logged in")
        )

        android.util.Log.i("MainActivity", "Stopping session: $id")
        val url = "$CLOUD_API_URL/sessions/$id/end"

        val json = """
            {
              "planned_minutes": $activePlannedMinutes
            }
        """.trimIndent()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $USER_TOKEN")
            .addHeader("X-User-ID", userId)
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val stoppedId = activeSessionId
                    activeSessionId = null
                    // Stop phone awareness tracking
                    PhoneAwarenessService.stop(this@MainActivity)
                    Result.success("✓ Stopped session\n${stoppedId?.take(8)}...\n📱 Tracking stopped")
                } else {
                    // Cloud API error - fall back to direct Supabase
                    android.util.Log.i("MainActivity", "Cloud API error ${response.code}, falling back to Supabase for stop")
                    val directResult = stopSessionDirectly(id)
                    return@withContext directResult.fold(
                        onSuccess = { msg ->
                            activeSessionId = null
                            PhoneAwarenessService.stop(this@MainActivity)
                            Result.success(msg)
                        },
                        onFailure = { Result.failure(Exception("Cloud API & Supabase stop failed: ${it.message}")) }
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Exception stopping session (Cloud API down?)", e)
            android.util.Log.i("MainActivity", "Falling back to direct Supabase for stop")
            val directResult = stopSessionDirectly(id)
            return@withContext directResult.fold(
                onSuccess = { msg ->
                    activeSessionId = null
                    PhoneAwarenessService.stop(this@MainActivity)
                    Result.success(msg)
                },
                onFailure = { Result.failure(Exception("Cloud API & Supabase stop failed: ${it.message}")) }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Safely unregister receiver only if it was registered
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(sessionReceiver)
                isReceiverRegistered = false
            } catch (e: IllegalArgumentException) {
                // Receiver was already unregistered, ignore
            }
        }

        // Clean up resources
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val message: String) : UiState()
    data class Error(val message: String) : UiState()
}
