package com.westly.neribovault.feature.accounts.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.entity.AccountItemEntity
import com.westly.neribovault.data.repository.AccountFieldsRepository
import com.westly.neribovault.data.repository.AccountItemsRepository
import com.westly.neribovault.data.repository.AccountsRepository
import com.westly.neribovault.feature.accounts.PlatformPresets
import com.westly.neribovault.feature.accounts.security.AccountsVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val OWNER_ITEM = "item"

/** The route value that means "create a new item". */
const val NEW_ITEM_ID = "new"

/** Everything typed in the item form. */
data class ItemForm(
    val name: String = "",
    val itemType: String = "project",
    val url: String = "",
    val identifier: String = "",
    val status: String = "active",
    val notes: String = "",
)

/** [initial] is the form as loaded; the form is dirty when it differs. */
data class ItemEditorUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val isNew: Boolean = true,
    val platform: String = "",
    val form: ItemForm = ItemForm(),
    val initial: ItemForm = ItemForm(),
    val isSaving: Boolean = false,
) {
    val isDirty: Boolean get() = form != initial
}

/** State and the save for the item editor. [itemId] is "new" to create an item. */
class ItemEditorViewModel(
    private val accountId: String,
    private val itemId: String,
    private val database: NeriboDatabase,
    private val accounts: AccountsRepository,
    private val items: AccountItemsRepository,
    private val fields: AccountFieldsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ItemEditorUiState(isNew = itemId == NEW_ITEM_ID))
    val state: StateFlow<ItemEditorUiState> = _state.asStateFlow()

    /** The custom fields of this item, edited in memory until Save. */
    val fieldsState = FieldsEditorState()

    // A new item gets its id only when it is saved. An existing one keeps its id.
    private val existingId: String? = if (itemId == NEW_ITEM_ID) null else itemId

    init {
        viewModelScope.launch {
            val account = accounts.getById(accountId)
            if (account == null || account.isDeleted) {
                _state.update { it.copy(isLoading = false, notFound = true) }
                return@launch
            }
            if (existingId == null) {
                val preset = PlatformPresets.find(account.platform)
                val form = ItemForm(itemType = preset?.defaultItemType ?: "other")
                fieldsState.load(emptyList())
                _state.update {
                    it.copy(isLoading = false, platform = account.platform, form = form, initial = form)
                }
                return@launch
            }
            val item = items.getById(existingId)
            if (item == null || item.isDeleted || item.accountId != accountId) {
                _state.update { it.copy(isLoading = false, notFound = true) }
                return@launch
            }
            val form = ItemForm(
                name = item.name,
                itemType = item.itemType,
                url = item.url,
                identifier = item.identifier,
                status = item.status,
                notes = item.notes,
            )
            fieldsState.load(fields.observeForOwner(OWNER_ITEM, item.id).first())
            _state.update {
                it.copy(isLoading = false, platform = account.platform, form = form, initial = form)
            }
        }
    }

    private fun edit(change: (ItemForm) -> ItemForm) {
        _state.update { it.copy(form = change(it.form)) }
    }

    fun setName(value: String) = edit { it.copy(name = value.take(MAX_NAME_LENGTH)) }

    fun setItemType(value: String) = edit { it.copy(itemType = value) }

    fun setUrl(value: String) = edit { it.copy(url = value) }

    fun setIdentifier(value: String) = edit { it.copy(identifier = value) }

    fun setStatus(value: String) = edit { it.copy(status = value) }

    fun setNotes(value: String) = edit { it.copy(notes = value) }

    /** The first thing wrong with the form, or null. */
    fun validate(): String? {
        val form = _state.value.form
        if (form.name.isBlank()) return "Give this item a name"
        if (!isValidLink(form.url)) return LINK_ERROR_TEXT
        return fieldsState.validate()
    }

    /** True when saving has to encrypt or remove a secret, so it needs the real session. */
    fun needsSession(): Boolean = fieldsState.needsSession(AccountsVault.hasDecoyPin.value)

    /** Saves the item and its fields, or nothing at all, then reports through [onResult]. */
    fun save(onResult: (EditorSaveResult) -> Unit) {
        if (_state.value.isSaving) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val result = performSave()
            _state.update { it.copy(isSaving = false) }
            onResult(result)
        }
    }

    private suspend fun performSave(): EditorSaveResult {
        val form = _state.value.form
        val hasDecoy = AccountsVault.hasDecoyPin.value
        val now = System.currentTimeMillis()
        val finalId = existingId ?: newId()
        val stored = if (existingId == null) null else items.getById(existingId)
        if (existingId != null && stored == null) {
            return EditorSaveResult.Failed("This item no longer exists")
        }
        val base = stored ?: AccountItemEntity(
            id = finalId,
            createdAt = now,
            updatedAt = now,
            accountId = accountId,
            name = "",
            itemType = form.itemType,
            url = "",
            identifier = "",
            status = form.status,
            notes = "",
        )
        val updated = base.copy(
            name = form.name.trim(),
            itemType = form.itemType,
            url = form.url.trim(),
            identifier = form.identifier.trim(),
            status = form.status,
            notes = form.notes.trim(),
        )
        val built = fieldsState.build(OWNER_ITEM, finalId, hasDecoy) { AccountsVault.encrypt(it) }
            ?: return EditorSaveResult.Failed(ENCRYPT_FAILED_MESSAGE)
        try {
            database.withTransaction {
                items.upsert(updated)
                built.removedIds.forEach { fields.deletePermanently(it) }
                built.fields.forEach { fields.upsert(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return EditorSaveResult.Failed("Couldn't save. Nothing was changed.")
        }
        if (built.secretsChanged) AccountsVault.logEvent("secret_saved", accountId)
        AccountsVault.touch()
        return EditorSaveResult.Saved
    }

    /** Moves the item to the trash (it can be restored from Recently deleted), then calls [onDone]. */
    fun delete(onDone: () -> Unit) {
        val id = existingId ?: return
        viewModelScope.launch {
            items.softDelete(id)
            onDone()
        }
    }

    override fun onCleared() {
        fieldsState.rows.forEach {
            it.value = ""
            it.decoy = ""
        }
        super.onCleared()
    }
}
