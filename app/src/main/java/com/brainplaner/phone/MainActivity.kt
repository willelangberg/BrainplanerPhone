package com.brainplaner.phone

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val CLOUD_API_URL = "http://192.168.0.23:8501"
    private val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1obW1pYXFhcW9kZGxreXppYXRpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc5NjQ2NDcsImV4cCI6MjA4MzU0MDY0N30.zN5bUHUWDqo2RASQkd-FQyTy01pwi_xFLVs2CpPZMXg"
    private val USER_TOKEN = SUPABASE_ANON_KEY  // For beta

    // Supabase for legacy polling
    private val SUPABASE_URL = "https://mhmmiaqaqoddlkyziati.supabase.co"

    // Track active session (phone is source of truth)
    private var activeSessionId: String? = null
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
                    uiStateCallback?.invoke(UiState.Success("✓ Session auto-started\n${sessionId?.take(8)}...\n🖥️ Initiated by PC"))
                }
                "com.brainplaner.phone.SESSION_AUTO_STOPPED" -> {
                    activeSessionId = null
                    uiStateCallback?.invoke(UiState.Success("✓ Session auto-stopped\n🖥️ Stopped by PC"))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is logged in
        if (!UserAuth.isLoggedIn(this)) {
            // Show login screen
            setContent {
                MaterialTheme {
                    LoginScreen(
                        onLogin = { userId ->
                            UserAuth.saveUserId(this, userId)
                            recreate() // Restart activity after login
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

        val currentUserId = UserAuth.getUserId(this) ?: ""

        setContent {
            MaterialTheme {
                SessionControllerScreen(
                    onStart = { startSession() },
                    onStop  = { stopSession() },
                    onLogout = {
                        PhoneAwarenessService.stop(this)
                        UserAuth.clearUserId(this)
                        recreate()
                    },
                    userId = currentUserId,
                    onStateChanged = { callback -> uiStateCallback = callback }
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
    private suspend fun createSessionDirectly(userId: String): Result<String> = withContext(Dispatchers.IO) {
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
    private suspend fun startSession(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val userId = UserAuth.getUserId(this@MainActivity) ?: return@withContext Result.failure(
                Exception("No user logged in")
            )

            val url = "$CLOUD_API_URL/sessions/start"
            android.util.Log.i("MainActivity", "Starting session for user: $userId")
            android.util.Log.i("MainActivity", "POST $url")

            val json = """
                {
                  "planned_minutes": 60
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

                    // Cloud API returned error - fall back to Supabase directly
                    android.util.Log.i("MainActivity", "Cloud API error ${response.code}, falling back to Supabase")
                    val directResult = createSessionDirectly(userId)
                    return@withContext directResult.fold(
                        onSuccess = { sessionId ->
                            activeSessionId = sessionId
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
                val directResult = createSessionDirectly(userId)
                return@withContext directResult.fold(
                    onSuccess = { sessionId ->
                        activeSessionId = sessionId
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
              "planned_minutes": 60
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

@Composable
fun SessionControllerScreen(
    onStart: suspend () -> Result<String>,
    onStop: suspend () -> Result<String>,
    onLogout: () -> Unit,
    userId: String,
    onStateChanged: ((UiState) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }

    // Register state callback
    DisposableEffect(Unit) {
        onStateChanged { newState -> uiState = newState }
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Brainplaner Phone Controller",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            "User: ${userId.take(8)}...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Status display with proper states
        when (val state = uiState) {
            is UiState.Idle -> Text("Ready", style = MaterialTheme.typography.bodyLarge)
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Success -> Text(
                state.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            is UiState.Error -> Text(
                "Error: ${state.message}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        val isLoading = uiState is UiState.Loading

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            onClick = {
                scope.launch {
                    uiState = UiState.Loading
                    val result = onStart()
                    uiState = result.fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { UiState.Error(it.message ?: "Unknown error") }
                    )
                }
            }
        ) {
            Text("Start Session")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            onClick = {
                scope.launch {
                    uiState = UiState.Loading
                    val result = onStop()
                    uiState = result.fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { UiState.Error(it.message ?: "Unknown error") }
                    )
                }
            }
        ) {
            Text("Stop Session")
        }

        Spacer(modifier = Modifier.height(32.dp))

        TextButton(
            onClick = onLogout
        ) {
            Text("Switch User / Logout", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun LoginScreen(onLogin: (String) -> Unit) {
    var userId by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "🔐 Brainplaner Beta",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Welcome! Enter your user ID to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = userId,
            onValueChange = {
                userId = it
                errorMessage = null
            },
            label = { Text("User ID") },
            placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = errorMessage != null
        )

        if (errorMessage != null) {
            Text(
                errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                when {
                    userId.isBlank() -> {
                        errorMessage = "Please enter your user ID"
                    }
                    userId.length != 36 -> {
                        errorMessage = "Invalid format. Should be 36 characters (UUID)"
                    }
                    else -> {
                        onLogin(userId)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Login")
        }
    }
}
