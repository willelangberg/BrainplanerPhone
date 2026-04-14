package com.brainplaner.phone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brainplaner.phone.LocalStore
import com.brainplaner.phone.ui.components.BrainCard
import com.brainplaner.phone.ui.components.BrainChoiceChip
import com.brainplaner.phone.ui.components.BrainDangerButton
import com.brainplaner.phone.ui.components.BrainPrimaryButton
import com.brainplaner.phone.ui.theme.BrainplanerPhoneTheme
import com.brainplaner.phone.ui.theme.BrainplanerTheme

@Composable
fun SettingsScreen(
    onResetCheckIn: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val spacing = BrainplanerTheme.spacing
    var warmupEnabled by remember { mutableStateOf(LocalStore.isWarmupEnabled(context)) }
    var readinessProfile by remember {
        mutableStateOf(LocalStore.getReadinessTuningProfile(context))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg, top = 48.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(spacing.xl))

        // ── Features ──
        Text(
            "FEATURES",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.height(spacing.xs))

        BrainCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cognitive Warm-up", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "5-tap reaction time test at app launch. Establishes your daily cognitive baseline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = warmupEnabled,
                        onCheckedChange = {
                            warmupEnabled = it
                            LocalStore.setWarmupEnabled(context, it)
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.md))

        BrainCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                Text("Readiness tuning profile", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Choose how strongly the Brain Budget reacts to recovery and load signals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = spacedBy(spacing.xs),
                ) {
                    BrainChoiceChip(
                        selected = readinessProfile == LocalStore.READINESS_PROFILE_DEFAULT,
                        onClick = {
                            readinessProfile = LocalStore.READINESS_PROFILE_DEFAULT
                            LocalStore.setReadinessTuningProfile(context, readinessProfile)
                        },
                        label = { Text("Default") },
                    )
                    BrainChoiceChip(
                        selected = readinessProfile == LocalStore.READINESS_PROFILE_CONSERVATIVE,
                        onClick = {
                            readinessProfile = LocalStore.READINESS_PROFILE_CONSERVATIVE
                            LocalStore.setReadinessTuningProfile(context, readinessProfile)
                        },
                        label = { Text("Conservative") },
                    )
                    BrainChoiceChip(
                        selected = readinessProfile == LocalStore.READINESS_PROFILE_AGGRESSIVE,
                        onClick = {
                            readinessProfile = LocalStore.READINESS_PROFILE_AGGRESSIVE
                            LocalStore.setReadinessTuningProfile(context, readinessProfile)
                        },
                        label = { Text("Aggressive") },
                    )
                }
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    "Applies on next cloud refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.xl))

        // ── Demo / Debug ──
        Text(
            "DEMO",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.height(spacing.xs))

        BrainCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                Text(
                    "Reset daily check-in",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Clears today's check-in so you can re-enter sleep data. Useful for demos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(spacing.sm))
                BrainPrimaryButton(
                    text = "↩ Reset Check-in",
                    onClick = {
                        LocalStore.clearCheckIn(context)
                        onResetCheckIn()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.xl))

        // ── Account ──
        Text(
            "ACCOUNT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.height(spacing.xs))

        BrainCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                BrainDangerButton(
                    text = "Logout",
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.xxl))
    }
}

@Preview(name = "Settings Light", showBackground = true)
@Composable
private fun SettingsPreviewLight() {
    BrainplanerPhoneTheme(darkTheme = false) {
        SettingsScreen(onResetCheckIn = {}, onLogout = {})
    }
}

@Preview(name = "Settings Dark", showBackground = true)
@Composable
private fun SettingsPreviewDark() {
    BrainplanerPhoneTheme(darkTheme = true) {
        SettingsScreen(onResetCheckIn = {}, onLogout = {})
    }
}

@Preview(name = "Settings Font 1.3x", showBackground = true, fontScale = 1.3f)
@Composable
private fun SettingsPreviewFontScale() {
    BrainplanerPhoneTheme(darkTheme = false) {
        SettingsScreen(onResetCheckIn = {}, onLogout = {})
    }
}
