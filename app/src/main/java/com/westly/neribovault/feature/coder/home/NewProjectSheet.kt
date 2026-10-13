package com.westly.neribovault.feature.coder.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.templates.CoderTemplate
import com.westly.neribovault.feature.coder.templates.CoderTemplates

private const val DEFAULT_TEMPLATE_ID = "hello-web"

/**
 * The sheet for starting a project: a name and a template. The name follows the chosen template's
 * name until the person types their own. [isCreating] disables the button while a project is being made.
 */
@Composable
fun NewProjectSheet(
    isCreating: Boolean,
    onCreate: (name: String, template: CoderTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val templates = CoderTemplates.all
    var selectedId by rememberSaveable { mutableStateOf(DEFAULT_TEMPLATE_ID) }
    var name by rememberSaveable {
        mutableStateOf(CoderTemplates.find(DEFAULT_TEMPLATE_ID)?.name.orEmpty())
    }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    val selected = CoderTemplates.find(selectedId) ?: templates.first()
    val canCreate = name.isNotBlank() && !isCreating

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "New project",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(spacing.md))
            NeriboTextField(
                value = name,
                onValueChange = {
                    if (it.length <= CoderLimits.MAX_PROJECT_NAME_LENGTH) {
                        name = it
                        nameEdited = true
                    }
                },
                label = "Project name",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(modifier = Modifier.height(spacing.md))
            Text(
                text = "Start from",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            templates.forEach { template ->
                TemplateCard(
                    template = template,
                    isSelected = template.id == selected.id,
                    onClick = {
                        selectedId = template.id
                        if (!nameEdited || name.isBlank()) {
                            name = template.name
                            nameEdited = false
                        }
                    },
                )
                Spacer(modifier = Modifier.height(spacing.sm))
            }
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboButton(
                text = "Create project",
                onClick = {
                    if (canCreate) onCreate(name.trim(), selected)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canCreate,
            )
        }
    }
}

@Composable
private fun TemplateCard(template: CoderTemplate, isSelected: Boolean, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Box(modifier = Modifier.size(24.dp)) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Selected",
                        tint = colors.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
