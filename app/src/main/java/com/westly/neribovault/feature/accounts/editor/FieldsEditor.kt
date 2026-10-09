package com.westly.neribovault.feature.accounts.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import com.westly.neribovault.feature.accounts.detail.SECRET_MASK
import com.westly.neribovault.feature.accounts.detail.SecretAccess
import com.westly.neribovault.feature.accounts.security.AccountsCrypto

/**
 * One row of the custom-fields form. [existing] is the stored field, or null for a new row.
 * A secret value is held here only while the form is open.
 */
@Stable
class FieldRowState(val id: String, val existing: AccountFieldEntity?) {
    var label by mutableStateOf(existing?.label.orEmpty())
    var isSecret by mutableStateOf(existing?.isSecret ?: true)
    var value by mutableStateOf(
        if (existing != null && !existing.isSecret) existing.valuePlain.orEmpty() else "",
    )
    var decoy by mutableStateOf(existing?.valueDecoy.orEmpty())

    /** False for a stored secret whose value has not been opened for editing. */
    var editingValue by mutableStateOf(existing == null || !existing.isSecret)

    /** Switches between secret and plain, keeping the stored value untouched where possible. */
    fun switchSecret(on: Boolean) {
        if (on == isSecret) return
        isSecret = on
        val old = existing ?: return
        if (old.isSecret) {
            value = ""
            editingValue = !on
        } else {
            editingValue = true
        }
    }

    /** True when saving this row has to encrypt a value. */
    fun needsEncryption(): Boolean {
        if (!isSecret) return false
        val old = existing
        return old == null || !old.isSecret || (editingValue && value.isNotEmpty())
    }

    /** True when this row differs from what is stored. */
    fun isChanged(): Boolean {
        val old = existing ?: return true
        if (label != old.label || isSecret != old.isSecret) return true
        if (decoy.trim() != old.valueDecoy.orEmpty().trim()) return true
        return if (old.isSecret) {
            !isSecret || (editingValue && value.isNotEmpty())
        } else {
            value != old.valuePlain.orEmpty()
        }
    }
}

/** What a successful [FieldsEditorState.build] hands to the save. */
class BuiltFields(
    val fields: List<AccountFieldEntity>,
    val removedIds: List<String>,
    val secretsChanged: Boolean,
)

/**
 * The custom fields of one account or item, held in memory until the form is saved. Create it in
 * the ViewModel, call [load] once with the stored fields, and read [build] when saving.
 */
@Stable
class FieldsEditorState {
    /** The rows in order. */
    val rows = mutableStateListOf<FieldRowState>()

    private var originalIds: List<String> = emptyList()
    private var originalSecretIds: Set<String> = emptySet()
    private var loaded = false

    /** Loads the stored fields. Only the first call has an effect. */
    fun load(existing: List<AccountFieldEntity>) {
        if (loaded) return
        loaded = true
        rows.clear()
        existing.forEach { rows.add(FieldRowState(it.id, it)) }
        originalIds = existing.map { it.id }
        originalSecretIds = existing.filter { it.isSecret }.map { it.id }.toSet()
    }

    fun addRow(label: String = "") {
        val row = FieldRowState(newId(), null)
        row.label = label.take(MAX_FIELD_LABEL_LENGTH)
        rows.add(row)
    }

    fun removeRow(row: FieldRowState) {
        rows.remove(row)
    }

    /** True when anything differs from what is stored. */
    val isDirty: Boolean
        get() = rows.map { it.id } != originalIds || rows.any { it.isChanged() }

    /** True while a secret value field is open for typing (the window is then kept secure). */
    val hasOpenSecret: Boolean
        get() = rows.any { it.isSecret && it.editingValue }

    private fun isBlankNew(row: FieldRowState): Boolean =
        row.existing == null && row.label.isBlank() && row.value.isBlank()

    private fun removedExistingIds(): List<String> =
        originalIds.filter { id -> rows.none { it.id == id } }

    /** The first problem with the rows, or null when they can be saved. */
    fun validate(): String? {
        for (row in rows) {
            if (isBlankNew(row)) continue
            if (row.label.isBlank()) return "Give every custom field a label"
            val keepsStoredSecret = row.existing?.isSecret == true && row.isSecret
            if (!keepsStoredSecret && row.value.isBlank()) {
                return "Add a value for \"${row.label.trim()}\" or remove the field"
            }
        }
        return null
    }

    /** True when saving needs the real Accounts session (a secret is added, changed or removed). */
    fun needsSession(hasDecoyPin: Boolean): Boolean {
        if (originalSecretIds.any { id -> rows.none { it.id == id } }) return true
        for (row in rows) {
            if (isBlankNew(row)) continue
            if (row.needsEncryption()) return true
            val old = row.existing
            if (old != null && old.isSecret) {
                if (!row.isSecret) return true
                if (hasDecoyPin && row.decoy.trim() != old.valueDecoy.orEmpty().trim()) return true
            }
        }
        return false
    }

    /**
     * Turns the rows into entities for [ownerType] and [ownerId]. [encrypt] seals a secret value;
     * if it ever returns null the whole build is cancelled (null), so nothing half-saved can
     * happen. Blank new rows are skipped.
     */
    fun build(
        ownerType: String,
        ownerId: String,
        hasDecoyPin: Boolean,
        encrypt: (String) -> AccountsCrypto.Sealed?,
    ): BuiltFields? {
        val now = System.currentTimeMillis()
        val out = ArrayList<AccountFieldEntity>()
        var secretsChanged = originalSecretIds.any { id -> rows.none { it.id == id } }
        var order = 0
        for (row in rows) {
            if (isBlankNew(row)) continue
            val old = row.existing
            val label = row.label.trim()
            val base = old ?: AccountFieldEntity(
                id = row.id,
                createdAt = now,
                updatedAt = now,
                ownerType = ownerType,
                ownerId = ownerId,
                label = label,
                isSecret = row.isSecret,
                sortOrder = order,
            )
            val entity = if (row.isSecret) {
                var cipher: String? = if (old != null && old.isSecret) old.valueCipher else null
                var iv: String? = if (old != null && old.isSecret) old.valueIv else null
                if (row.needsEncryption()) {
                    val sealed = encrypt(row.value) ?: return null
                    cipher = sealed.ciphertext
                    iv = sealed.iv
                    secretsChanged = true
                }
                val decoy: String? = if (hasDecoyPin) {
                    row.decoy.trim().ifEmpty { null }
                } else {
                    if (old != null && old.isSecret) old.valueDecoy else null
                }
                if (old != null && old.isSecret && decoy != old.valueDecoy) secretsChanged = true
                base.copy(
                    label = label,
                    isSecret = true,
                    valuePlain = null,
                    valueCipher = cipher,
                    valueIv = iv,
                    valueDecoy = decoy,
                    sortOrder = order,
                )
            } else {
                if (old != null && old.isSecret) secretsChanged = true
                base.copy(
                    label = label,
                    isSecret = false,
                    valuePlain = row.value.trim(),
                    valueCipher = null,
                    valueIv = null,
                    valueDecoy = null,
                    sortOrder = order,
                )
            }
            out.add(entity)
            order += 1
        }
        return BuiltFields(out, removedExistingIds(), secretsChanged)
    }
}

/**
 * The custom-fields section used by the account editor and the item editor. [ownerType] and
 * [ownerId] say who the fields belong to (the rows themselves live in [state]). Opening or
 * changing a stored secret value goes through [access], so it needs the real Accounts session.
 * [hasDecoyPin] shows the optional second-PIN text field.
 */
@Composable
fun FieldsEditor(
    ownerType: String,
    ownerId: String,
    state: FieldsEditorState,
    access: SecretAccess,
    hasDecoyPin: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(text = "Custom fields", modifier = Modifier.padding(bottom = spacing.xs))
        Text(
            text = "Add anything else you need to keep for this ${if (ownerType == "item") "item" else "account"}. " +
                "Secret values are encrypted; the others are plain text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        for (row in state.rows) {
            key(ownerId, row.id) {
                FieldRowEditor(
                    row = row,
                    access = access,
                    hasDecoyPin = hasDecoyPin,
                    onRemove = { state.removeRow(row) },
                )
            }
            Spacer(modifier = Modifier.height(spacing.md))
        }
        NeriboButton(
            text = "Add field",
            onClick = { state.addRow() },
            modifier = Modifier.fillMaxWidth(),
            style = ButtonStyle.Secondary,
            leadingIcon = Icons.Outlined.Add,
        )
    }
}

@Composable
private fun FieldRowEditor(
    row: FieldRowState,
    access: SecretAccess,
    hasDecoyPin: Boolean,
    onRemove: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    var showValue by remember { mutableStateOf(false) }
    val storedSecretClosed = row.isSecret && row.existing?.isSecret == true && !row.editingValue

    NeriboCard {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            NeriboTextField(
                value = row.label,
                onValueChange = { row.label = it.take(MAX_FIELD_LABEL_LENGTH) },
                label = "Label",
                placeholder = "API key",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (row.existing == null && row.label.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    for (suggestion in FIELD_LABEL_SUGGESTIONS) {
                        NeriboChip(
                            label = suggestion,
                            selected = false,
                            onClick = { row.label = suggestion },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "This value is secret",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = row.isSecret, onCheckedChange = { row.switchSecret(it) })
            }
            if (storedSecretClosed) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = SECRET_MASK,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    NeriboButton(
                        text = "Edit value",
                        onClick = { access.run(true) { row.editingValue = true } },
                        style = ButtonStyle.Text,
                    )
                }
            } else {
                NeriboTextField(
                    value = row.value,
                    onValueChange = { row.value = it },
                    modifier = Modifier.padding(top = spacing.sm),
                    label = if (row.existing?.isSecret == true && row.isSecret) "New value" else "Value",
                    singleLine = row.isSecret,
                    maxLines = if (row.isSecret) 1 else 4,
                    keyboardOptions = if (row.isSecret) {
                        KeyboardOptions(keyboardType = KeyboardType.Password)
                    } else {
                        KeyboardOptions.Default
                    },
                    visualTransformation = if (row.isSecret && !showValue) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    trailingIcon = {
                        if (row.isSecret) {
                            NeriboIconButton(
                                icon = if (showValue) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showValue) "Hide value" else "Show value",
                                onClick = { showValue = !showValue },
                            )
                        }
                    },
                )
            }
            if (row.isSecret && hasDecoyPin) {
                NeriboTextField(
                    value = row.decoy,
                    onValueChange = { row.decoy = it },
                    modifier = Modifier.padding(top = spacing.sm),
                    label = "Text to show instead of the value",
                )
            }
            NeriboButton(
                text = "Remove",
                onClick = onRemove,
                style = ButtonStyle.Text,
            )
        }
    }
}
