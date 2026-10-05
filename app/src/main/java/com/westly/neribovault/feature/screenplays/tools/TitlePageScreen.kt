package com.westly.neribovault.feature.screenplays.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.util.LifecycleSaveEffect

/** Title, author, contact details and the copyright page, with a live preview of the printed pages. */
@Composable
fun TitlePageScreen(screenplayId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "title-page-$screenplayId") { c ->
        TitlePageViewModel(c.screenplaysRepository, screenplayId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    LifecycleSaveEffect(onSave = { vm.flush() })

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Title page",
                onBack = onBack,
                subtitle = if (state.isLoading || state.notFound) null else if (state.isSaving) "Saving\u2026" else "Saved",
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Spacer(modifier = Modifier.fillMaxSize().padding(padding))
            state.notFound -> EmptyState(
                icon = Icons.Outlined.Description,
                title = "Screenplay not found",
                message = "It may have been removed. Go back and pick another one.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                NeriboTextField(
                    value = state.title,
                    onValueChange = vm::onTitleChange,
                    label = "Title",
                    placeholder = "The Last Danfo",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                NeriboTextField(
                    value = state.author,
                    onValueChange = vm::onAuthorChange,
                    label = "Author",
                    placeholder = "Your name",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                NeriboTextField(
                    value = state.contact,
                    onValueChange = vm::onContactChange,
                    label = "Contact details",
                    placeholder = "Phone, email, city: it prints at the bottom left of the title page",
                    singleLine = false,
                    minLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Include copyright page",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.noticeEnabled,
                        onCheckedChange = vm::onNoticeEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onPrimary,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.outline,
                            uncheckedTrackColor = colors.surfaceVariant,
                            uncheckedBorderColor = colors.outline,
                        ),
                    )
                }
                if (state.noticeEnabled) {
                    NeriboTextField(
                        value = state.noticeField,
                        onValueChange = vm::onNoticeChange,
                        label = "Copyright notice",
                        singleLine = false,
                        minLines = 4,
                    )
                    NeriboButton(
                        text = "Reset to default",
                        onClick = vm::resetNotice,
                        style = ButtonStyle.Text,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                TitlePagePreview(
                    title = state.title,
                    author = state.author,
                    contact = state.contact,
                    showNotice = state.noticeEnabled,
                    notice = state.effectiveNotice,
                )
                Spacer(modifier = Modifier.height(spacing.xl))
            }
        }
    }
}
