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

        // If the app process was restarted during an active session, resume tracking immediately.
        LocalStore.getActiveSession(this)?.let { localSession ->
            activeSessionId = localSession.id
            activePlannedMinutes = localSession.plannedMinutes
            PhoneAwarenessService.start(this, localSession.id)
            android.util.Log.i("MainActivity", "Restored active session tracking: ${localSession.id}")
        }

        val userId = UserAuth.getUserId(this) ?: return

        setContent {
            BrainplanerPhoneTheme {
                AppNavigation(
                    userId = userId,
                    apiUrl = CLOUD_API_URL,
                    userToken = USER_TOKEN,
                    getActiveSessionId = { activeSessionId },
                    onStartSession = { minutes -> startSession(minutes) },
                    onStopSession = { stopSession() },
                    onPauseSession = { pauseSession() },
                    onResumeSession = { resumeSession() },
                    onLogout = {
                        PhoneAwarenessService.stop(this)
                        UserAuth.clearUserId(this)
                        recreate()
                    },
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

    // LOCAL-FIRST session start: saves locally immediately, syncs to cloud in background.
    private suspend fun startSession(plannedMinutes: Int = 60): Result<String> = withContext(Dispatchers.IO) {
        // Re-check notification permission right before tracking starts.
        withContext(Dispatchers.Main) {
            ensureNotificationPermission()
        }

        val userId = UserAuth.getUserId(this@MainActivity) ?: return@withContext Result.failure(
            Exception("No user logged in")
        )

        // 1. Create a local session ID so the user can start immediately.
        val localId = LocalStore.generateLocalSessionId()
        LocalStore.saveSessionStart(this@MainActivity, localId, plannedMinutes, cloudSynced = false)
        activeSessionId = localId
        activePlannedMinutes = plannedMinutes
        PhoneAwarenessService.start(this@MainActivity, localId)
        android.util.Log.i("MainActivity", "Local session started: $localId")

        // 2. Try cloud sync in background — upgrade local ID to cloud ID if successful.
        try {
            val json = """{"planned_minutes": $plannedMinutes}"""
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$CLOUD_API_URL/sessions/start")
                .post(body)
                .addHeader("Authorization", "Bearer $USER_TOKEN")
                .addHeader("X-User-ID", userId)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = runCatching { client.newCall(request).execute() }.getOrNull()
            if (response != null) {
                response.use { res ->
                    if (res.isSuccessful) {
                        val responseBody = res.body?.string() ?: ""
                        val cloudId = Regex(""""session_id":"([^"]+)"""")
                            .find(responseBody)?.groupValues?.get(1)
                        if (cloudId != null) {
                            activeSessionId = cloudId
                            LocalStore.markSessionCloudSynced(this@MainActivity, cloudId)
                            PhoneAwarenessService.start(this@MainActivity, cloudId)
                            android.util.Log.i("MainActivity", "Cloud session synced: $cloudId")
                            return@withContext Result.success("✓ Session started\n${cloudId.take(8)}...")
                        }
                    } else {
                        val errorBody = res.body?.string() ?: ""
                        // 409 = stale session — auto-end and retry once.
                        if (res.code == 409 || (res.code == 500 && errorBody.contains("active session", ignoreCase = true))) {
                            val staleId = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
                                .find(errorBody)?.value
                            if (staleId != null) {
                                val endReq = Request.Builder()
                                    .url("$CLOUD_API_URL/sessions/$staleId/end")
                                    .post("{}".toRequestBody("application/json".toMediaType()))
                                    .addHeader("Authorization", "Bearer $USER_TOKEN")
                                    .addHeader("X-User-ID", userId)
                                    .build()
                                runCatching { client.newCall(endReq).execute().close() }
                                // Retry start once.
                                val retryResp = runCatching { client.newCall(request).execute() }.getOrNull()
                                retryResp?.use { r ->
                                    if (r.isSuccessful) {
                                        val rb = r.body?.string() ?: ""
                                        val cid = Regex(""""session_id":"([^"]+)"""").find(rb)?.groupValues?.get(1)
                                        if (cid != null) {
                                            activeSessionId = cid
                                            LocalStore.markSessionCloudSynced(this@MainActivity, cid)
                                            PhoneAwarenessService.start(this@MainActivity, cid)
                                            return@withContext Result.success("✓ Session started\n${cid.take(8)}...")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Cloud sync failed for session start, running offline", e)
        }

        // Cloud unavailable — session is running locally.
        Result.success("✓ Session started (offline)\n📱 Will sync when online")
    }

    // LOCAL-FIRST session stop: clears local state immediately, syncs to cloud in background.
    private suspend fun stopSession(): Result<String> = withContext(Dispatchers.IO) {
        val id = activeSessionId
        if (id == null) {
            return@withContext Result.failure(Exception("No active session"))
        }

        val userId = UserAuth.getUserId(this@MainActivity) ?: return@withContext Result.failure(
            Exception("No user logged in")
        )

        // 1. Clear local state immediately — user is never blocked.
        val stoppedId = id
        activeSessionId = null
        LocalStore.clearActiveSession(this@MainActivity)
        PhoneAwarenessService.stop(this@MainActivity)
        android.util.Log.i("MainActivity", "Local session stopped: $stoppedId")

        // 2. Try cloud sync in background (only for cloud-synced sessions).
        if (!stoppedId.startsWith("local-")) {
            try {
                val json = """{"planned_minutes": $activePlannedMinutes}"""
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$CLOUD_API_URL/sessions/$stoppedId/end")
                    .post(body)
                    .addHeader("Authorization", "Bearer $USER_TOKEN")
                    .addHeader("X-User-ID", userId)
                    .addHeader("Content-Type", "application/json")
                    .build()
                val response = runCatching { client.newCall(request).execute() }.getOrNull()
                response?.use { res ->
                    if (res.isSuccessful) {
                        android.util.Log.i("MainActivity", "Cloud session end synced for $stoppedId")
                    } else {
                        android.util.Log.w("MainActivity", "Cloud session end failed HTTP ${res.code}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Cloud sync failed for session stop", e)
            }
        }

        Result.success("✓ Session stopped\n${stoppedId.take(8)}...")
    }

    // LOCAL-FIRST pause: saves locally immediately, syncs to cloud in background.
    private suspend fun pauseSession(): Result<String> = withContext(Dispatchers.IO) {
        val id = activeSessionId
            ?: return@withContext Result.failure(Exception("No active session"))

        val userId = UserAuth.getUserId(this@MainActivity)
            ?: return@withContext Result.failure(Exception("No user logged in"))

        // 1. Save pause state locally immediately.
        LocalStore.saveSessionPaused(this@MainActivity)
        android.util.Log.i("MainActivity", "Local session paused: $id")

        // 2. Notify PhoneAwarenessService immediately via broadcast.
        sendBroadcast(Intent("com.brainplaner.phone.SESSION_PAUSED").apply {
            setPackage(packageName)
            putExtra("session_id", id)
        })

        // 3. Fire-and-forget cloud sync.
        if (!id.startsWith("local-")) {
            try {
                val body = "{}".toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$CLOUD_API_URL/sessions/$id/pause")
                    .post(body)
                    .addHeader("Authorization", "Bearer $USER_TOKEN")
                    .addHeader("X-User-ID", userId)
                    .addHeader("Content-Type", "application/json")
                    .build()
                val response = runCatching { client.newCall(request).execute() }.getOrNull()
                response?.use { res ->
                    if (res.isSuccessful) {
                        android.util.Log.i("MainActivity", "Cloud session pause synced for $id")
                    } else {
                        android.util.Log.w("MainActivity", "Cloud session pause failed HTTP ${res.code}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Cloud sync failed for session pause", e)
            }
        }

        Result.success("⏸ Session paused")
    }

    // LOCAL-FIRST resume: saves locally immediately, syncs to cloud in background.
    private suspend fun resumeSession(): Result<String> = withContext(Dispatchers.IO) {
        val id = activeSessionId
            ?: return@withContext Result.failure(Exception("No active session"))

        val userId = UserAuth.getUserId(this@MainActivity)
            ?: return@withContext Result.failure(Exception("No user logged in"))

        // 1. Save resume state locally immediately.
        LocalStore.saveSessionResumed(this@MainActivity)
        android.util.Log.i("MainActivity", "Local session resumed: $id")

        // 2. Notify PhoneAwarenessService immediately via broadcast.
        sendBroadcast(Intent("com.brainplaner.phone.SESSION_RESUMED").apply {
            setPackage(packageName)
            putExtra("session_id", id)
        })

        // 3. Fire-and-forget cloud sync.
        if (!id.startsWith("local-")) {
            try {
                val body = "{}".toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$CLOUD_API_URL/sessions/$id/resume")
                    .post(body)
                    .addHeader("Authorization", "Bearer $USER_TOKEN")
                    .addHeader("X-User-ID", userId)
                    .addHeader("Content-Type", "application/json")
                    .build()
                val response = runCatching { client.newCall(request).execute() }.getOrNull()
                response?.use { res ->
                    if (res.isSuccessful) {
                        android.util.Log.i("MainActivity", "Cloud session resume synced for $id")
                    } else {
                        android.util.Log.w("MainActivity", "Cloud session resume failed HTTP ${res.code}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Cloud sync failed for session resume", e)
            }
        }

        Result.success("▶ Session resumed")
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
