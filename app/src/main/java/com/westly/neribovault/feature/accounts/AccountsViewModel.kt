package com.westly.neribovault.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.repository.AccountItemsRepository
import com.westly.neribovault.data.repository.AccountsRepository
import com.westly.neribovault.feature.accounts.security.CheckResult
import com.westly.neribovault.feature.accounts.security.runAccountsSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The name shown when an account somehow has no name. */
const val UNTITLED_ACCOUNT = "Untitled account"

/** One row of the list: the account and how many items live under it. */
data class AccountListItem(val account: AccountEntity, val itemCount: Int)

/** One platform chip: [key] is the stored platform value, [label] its display name. */
data class PlatformChip(val key: String, val label: String)

/**
 * Everything the Accounts list screen draws. [statusFilter] is "" for All, otherwise a stored
 * status; [platformFilter] is "" for none, otherwise a stored platform value.
 */
data class AccountsUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val statusFilter: String = "",
    val platformFilter: String = "",
    val items: List<AccountListItem> = emptyList(),
    val platformChips: List<PlatformChip> = emptyList(),
    val hasAny: Boolean = false,
    val selfTest: List<CheckResult>? = null,
    val isLoading: Boolean = true,
)

/** State and actions for the Accounts list. */
class AccountsViewModel(
    private val accounts: AccountsRepository,
    itemsRepository: AccountItemsRepository,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val statusFlow = MutableStateFlow("")
    private val platformFlow = MutableStateFlow("")
    private val selfTestFlow = MutableStateFlow<List<CheckResult>?>(null)

    private val allItems: Flow<List<AccountListItem>> = combine(
        accounts.observeAll(),
        itemsRepository.observeCountsByAccount(),
    ) { list, counts ->
        list.map { account -> AccountListItem(account, counts[account.id] ?: 0) }
    }

    private val filters: Flow<Triple<String, String, String>> = combine(
        queryFlow,
        statusFlow,
        platformFlow,
    ) { query, status, platform -> Triple(query, status, platform) }

    val state: StateFlow<AccountsUiState> = combine(
        allItems,
        filters,
        searchOpenFlow,
        selfTestFlow,
    ) { items, filter, searchOpen, selfTest ->
        val (query, status, platform) = filter
        val needle = query.trim()
        val shown = items.filter { item ->
            val account = item.account
            val matchesStatus = status.isEmpty() || account.status == status
            val matchesPlatform = platform.isEmpty() || account.platform == platform
            matchesStatus && matchesPlatform && matchesQuery(account, needle)
        }
        val chips = items
            .map { it.account.platform }
            .distinct()
            .map { PlatformChip(it, PlatformPresets.displayName(it)) }
            .sortedBy { it.label.lowercase() }
        AccountsUiState(
            query = query,
            isSearchOpen = searchOpen,
            statusFilter = status,
            platformFilter = platform,
            items = shown,
            platformChips = chips,
            hasAny = items.isNotEmpty(),
            selfTest = selfTest,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    /** Case-insensitive match on name, platform display name, login identifier and tags. */
    private fun matchesQuery(account: AccountEntity, needle: String): Boolean {
        if (needle.isEmpty()) return true
        return account.name.contains(needle, ignoreCase = true) ||
            PlatformPresets.displayName(account.platform).contains(needle, ignoreCase = true) ||
            account.loginId.contains(needle, ignoreCase = true) ||
            account.tags.any { it.contains(needle, ignoreCase = true) }
    }

    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun openSearch() {
        searchOpenFlow.value = true
    }

    fun closeSearch() {
        searchOpenFlow.value = false
        queryFlow.value = ""
    }

    /** Pass "" for All. Tapping the selected status again is handled by the screen. */
    fun onStatusFilterChange(status: String) {
        statusFlow.value = status
    }

    /** Single selection: choosing the selected platform again clears it. */
    fun onPlatformFilterToggle(platform: String) {
        platformFlow.value = if (platformFlow.value == platform) "" else platform
    }

    /**
     * Creates a new account from [preset] and calls [onCreated] with its id once it is saved.
     * [platformName] is only used for "Other platform".
     */
    fun create(preset: PlatformPreset, platformName: String, name: String, onCreated: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val isCustom = preset.id == PlatformPresets.CUSTOM_ID
        val account = AccountEntity(
            id = newId(),
            createdAt = now,
            updatedAt = now,
            platform = if (isCustom) platformName.trim() else preset.id,
            name = name.trim(),
            signInMethod = PlatformPresets.defaultSignInMethod(preset.id),
            loginId = "",
            url = preset.url,
            twoFactor = "none",
            recovery = "",
            notes = "",
            status = ACCOUNT_STATUS_ACTIVE,
            tags = emptyList(),
            isPinned = false,
        )
        viewModelScope.launch {
            accounts.upsert(account)
            onCreated(account.id)
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { accounts.setPinned(id, pinned) }
    }

    /** Copies the account, its items and their fields. The encrypted password is copied as is. */
    fun duplicate(id: String) {
        viewModelScope.launch { accounts.duplicate(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { accounts.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { accounts.restore(id) }
    }

    /** Debug menu: runs the engine checks off the main thread and shows the result. */
    fun runSelfTest() {
        viewModelScope.launch(Dispatchers.Default) {
            selfTestFlow.value = runAccountsSelfTest()
        }
    }

    fun dismissSelfTest() {
        selfTestFlow.value = null
    }
}
