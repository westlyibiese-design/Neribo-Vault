package com.westly.neribovault.feature.accounts.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.accounts.PlatformPreset
import com.westly.neribovault.feature.accounts.PlatformPresets

private const val MAX_PLATFORM_NAME = 40
private const val MAX_ACCOUNT_NAME = 80

/**
 * "Add account": first a list of platforms grouped by category, then a name for the account
 * (and a platform name when "Other platform" was chosen). [onCreate] gets the chosen preset, the
 * typed platform name (empty unless "Other platform") and the account name, both trimmed.
 */
@Composable
fun NewAccountSheet(
    onDismiss: () -> Unit,
    onCreate: (preset: PlatformPreset, platformName: String, accountName: String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var platformName by rememberSaveable { mutableStateOf("") }
    var accountName by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    val selected = selectedId?.let { PlatformPresets.find(it) }

    NeriboBottomSheet(onDismiss = onDismiss) {
        if (selected == null) {
            Text(
                text = "Add account",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Choose a platform",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                for (category in PlatformPresets.categories) {
                    SectionHeader(
                        text = category,
                        modifier = Modifier.padding(top = spacing.md, bottom = spacing.xs),
                    )
                    for (preset in PlatformPresets.all.filter { it.category == category }) {
                        PresetRow(
                            preset = preset,
                            onClick = {
                                selectedId = preset.id
                                platformName = ""
                                nameEdited = false
                                accountName = if (preset.id == PlatformPresets.CUSTOM_ID) "" else preset.name
                            },
                        )
                    }
                }
            }
        } else {
            val isCustom = selected.id == PlatformPresets.CUSTOM_ID
            val canCreate = accountName.isNotBlank() && (!isCustom || platformName.isNotBlank())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformAvatar(
                        platform = if (isCustom) platformName else selected.id,
                        size = 40.dp,
                    )
                    Text(
                        text = if (isCustom) "Other platform" else selected.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = spacing.md),
                    )
                    NeriboButton(
                        text = "Change",
                        onClick = { selectedId = null },
                        style = ButtonStyle.Text,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.lg))
                if (isCustom) {
                    NeriboTextField(
                        value = platformName,
                        onValueChange = { value ->
                            platformName = value.take(MAX_PLATFORM_NAME)
                            if (!nameEdited) accountName = platformName
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Platform name",
                        placeholder = "My bank portal",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    Spacer(modifier = Modifier.height(spacing.md))
                }
                NeriboTextField(
                    value = accountName,
                    onValueChange = { value ->
                        accountName = value.take(MAX_ACCOUNT_NAME)
                        nameEdited = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Name this account",
                    placeholder = "Westly main",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Spacer(modifier = Modifier.height(spacing.xl))
                NeriboButton(
                    text = "Create",
                    onClick = { onCreate(selected, platformName.trim(), accountName.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canCreate,
                )
            }
        }
    }
}

@Composable
private fun PresetRow(preset: PlatformPreset, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        PlatformAvatar(platform = preset.id, size = 32.dp)
        Text(
            text = preset.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
