package com.brainplaner.phone.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brainplaner.phone.ui.theme.BrainplanerPhoneTheme
import com.brainplaner.phone.ui.theme.BrainplanerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainTopBar(
    title: String,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
    )
}

@Composable
fun BrainCard(
    modifier: Modifier = Modifier,
    containerColor: Color = BrainplanerTheme.surfaceRoles.surface2,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(BrainplanerTheme.radius.md)
    val elevation = CardDefaults.cardElevation(defaultElevation = BrainplanerTheme.elevation.card)
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            elevation = elevation,
            colors = colors,
            content = { content() },
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            elevation = elevation,
            colors = colors,
            content = { content() },
        )
    }
}

@Composable
fun BrainPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(BrainplanerTheme.radius.sm),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        contentPadding = contentPadding,
    ) {
        content()
    }
}

@Composable
fun BrainPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    BrainPrimaryButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = containerColor,
        contentPadding = contentPadding,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun BrainDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(BrainplanerTheme.radius.sm),
        colors = ButtonDefaults.buttonColors(containerColor = BrainplanerTheme.semanticColors.critical),
    ) {
        content()
    }
}

@Composable
fun BrainDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BrainDangerButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun BrainChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = BrainplanerTheme.surfaceRoles.surface1,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = BrainplanerTheme.surfaceRoles.strokeSubtle,
            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            disabledBorderColor = BrainplanerTheme.surfaceRoles.strokeSubtle,
            disabledSelectedBorderColor = BrainplanerTheme.surfaceRoles.strokeSubtle,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.5.dp,
        ),
    )
}

@Preview(name = "Components Light", showBackground = true)
@Composable
private fun BrainComponentsPreviewLight() {
    BrainplanerPhoneTheme(darkTheme = false) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BrainTopBar(title = "Preview")
            BrainCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Tokenized surface card",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            BrainChoiceChip(selected = true, onClick = {}, label = { Text("45m") })
            BrainPrimaryButton(text = "Start Session", onClick = {})
            BrainDangerButton(text = "Stop Session", onClick = {})
        }
    }
}

@Preview(name = "Components Dark", showBackground = true)
@Composable
private fun BrainComponentsPreviewDark() {
    BrainplanerPhoneTheme(darkTheme = true) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BrainTopBar(title = "Preview")
            BrainCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Tokenized surface card",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            BrainChoiceChip(selected = true, onClick = {}, label = { Text("45m") })
            BrainPrimaryButton(text = "Start Session", onClick = {})
            BrainDangerButton(text = "Stop Session", onClick = {})
        }
    }
}

@Preview(name = "Components Font 1.3x", showBackground = true, fontScale = 1.3f)
@Composable
private fun BrainComponentsPreviewFontScale() {
    BrainplanerPhoneTheme(darkTheme = false) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BrainTopBar(title = "Preview")
            BrainCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Tokenized surface card",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            BrainChoiceChip(selected = true, onClick = {}, label = { Text("45m") })
            BrainPrimaryButton(text = "Start Session", onClick = {})
            BrainDangerButton(text = "Stop Session", onClick = {})
        }
    }
}
