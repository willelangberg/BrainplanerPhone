package com.brainplaner.phone.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brainplaner.phone.UiState
import kotlinx.coroutines.launch

@Composable
fun SessionControllerScreen(
    onStart: suspend () -> Result<String>,
    onStop: suspend () -> Result<String>,
    onLogout: () -> Unit,
    userId: String,
    onStateChanged: ((UiState) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }

    // Register state callback so PC-initiated events (BroadcastReceiver) can update this screen.
    DisposableEffect(Unit) {
        onStateChanged { newState -> uiState = newState }
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Brainplaner Phone Controller", style = MaterialTheme.typography.titleLarge)

        Text(
            "User: ${userId.take(8)}...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val state = uiState) {
            is UiState.Idle -> Text("Ready", style = MaterialTheme.typography.bodyLarge)
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Success -> Text(
                state.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            is UiState.Error -> Text(
                "Error: ${state.message}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
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
                        onFailure = { UiState.Error(it.message ?: "Unknown error") },
                    )
                }
            },
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
                        onFailure = { UiState.Error(it.message ?: "Unknown error") },
                    )
                }
            },
        ) {
            Text("Stop Session")
        }

        Spacer(modifier = Modifier.height(32.dp))

        TextButton(onClick = onLogout) {
            Text("Switch User / Logout", color = MaterialTheme.colorScheme.error)
        }
    }
}
